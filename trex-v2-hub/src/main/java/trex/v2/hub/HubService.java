package trex.v2.hub;

import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import trex.v2.core.Action;
import trex.v2.core.Actor;
import trex.v2.core.config.Account;
import trex.v2.core.config.BalanceSource;
import trex.v2.core.config.DeriveConfig;
import trex.v2.core.config.Registry;
import trex.v2.core.config.RuleSet;
import trex.v2.core.config.User;
import trex.v2.core.derive.Reconciliation;
import trex.v2.hub.api.DecisionRequest;
import trex.v2.hub.api.ErrorResponse;
import trex.v2.hub.api.HeadResponse;
import trex.v2.hub.api.LedgerPage;
import trex.v2.hub.api.PrecheckResponse;
import trex.v2.hub.api.ReconcileResponse;
import trex.v2.hub.api.RefdataResponse;
import trex.v2.hub.api.ReviewRow;
import trex.v2.hub.api.StatusResponse;
import trex.v2.hub.api.TransferJson;
import trex.v2.hub.api.UnitJson;
import trex.v2.index.IndexLock;
import trex.v2.index.Indexer;
import trex.v2.log.ConfigLoader;
import trex.v2.sequencer.api.BatchResponse;
import trex.v2.sequencer.api.DecisionBatch;
import trex.v2.sequencer.api.DecisionDraft;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * The index owner (V2-PROPOSAL.md §7.4). One process holds the {@link IndexLock}, applies the
 * journal to the mirror, re-derives on any journal or config change, and serves reads through a
 * pool. It writes the index and never the journal; the sequencer remains the only writer of the log.
 *
 * <p>Restart is safe: it catches up from the persisted offset. A missing, corrupt or
 * schema-mismatched index is rebuilt rather than repaired.
 */
public final class HubService implements HubApi, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HubService.class);

    private final HubConfig config;
    private final IndexLock lock;
    private final Indexer indexer;
    private final HubQueries reads;
    private final IndexRefresher refresher;
    private final SequencerClient sequencer;
    private final AtomicBoolean closed = new AtomicBoolean();
    private HttpServer server;

    private HubService(HubConfig config, IndexLock lock, Indexer indexer, HubQueries reads,
                       IndexRefresher refresher, SequencerClient sequencer) {
        this.config = config;
        this.lock = lock;
        this.indexer = indexer;
        this.reads = reads;
        this.refresher = refresher;
        this.sequencer = sequencer;
    }

    public static HubService start(HubConfig config) {
        ConfigLoader.Loaded loaded = ConfigLoader.load(config.configDir());
        IndexLock lock = IndexLock.acquire(config.index());
        Indexer indexer = null;
        HubQueries reads = null;
        IndexRefresher refresher = null;
        try {
            indexer = Indexer.open(config.index(), loaded.config());
            reads = new HubQueries(config.index(), 4);
            refresher = new IndexRefresher(config.journal(), config.configDir(), indexer,
                loaded.config(), config.refreshDebounceMs());
            SequencerClient sequencer = config.sequencerUrl() == null ? null : new SequencerClient(config.sequencerUrl());
            HubService service = new HubService(config, lock, indexer, reads, refresher, sequencer);
            service.server = HubHttpApi.start(config.host(), config.port(), service);
            log.info("trex hub listening on {}:{}; journal {}; index {}",
                config.host(), service.server.getAddress().getPort(), config.journal(), config.index());
            return service;
        } catch (IOException e) {
            closeQuietly(lock, indexer, reads, refresher);
            throw new UncheckedIOException("cannot start the hub", e);
        } catch (RuntimeException e) {
            closeQuietly(lock, indexer, reads, refresher);
            throw e;
        }
    }

    private static void closeQuietly(IndexLock lock, Indexer indexer, HubQueries reads, IndexRefresher refresher) {
        try {
            if (refresher != null) {
                refresher.close();
            }
            if (reads != null) {
                reads.close();
            }
            if (indexer != null) {
                indexer.close();
            }
        } finally {
            lock.close();
        }
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public HeadResponse head() {
        long head = journalSize();
        long offset = reads.offset();
        return new HeadResponse(reads.logHeadN(), offset, head, Math.max(0, head - offset));
    }

    @Override
    public StatusResponse status() {
        DeriveConfig c = refresher.config();
        long head = journalSize();
        long offset = reads.offset();
        return new StatusResponse(reads.logHeadN(), offset, head, Math.max(0, head - offset),
            reads.counts(), reads.reviewByKind(), c.configRevision(),
            DeriveConfig.DERIVE_VERSION, DeriveConfig.HASH_VERSION);
    }

    @Override
    public RefdataResponse refdata() {
        DeriveConfig c = refresher.config();
        Registry registry = c.registry();
        List<RefdataResponse.AccountJson> accounts = registry.accounts().values().stream()
            .map(a -> new RefdataResponse.AccountJson(a.ref(), a.currency(), a.balanceSource().wire(),
                a.settlementWindowDays()))
            .toList();
        List<RefdataResponse.UserJson> users = registry.users().values().stream()
            .map(u -> new RefdataResponse.UserJson(u.id(), u.name(), u.active(), u.cadence()))
            .toList();
        return new RefdataResponse(accounts, users, c.categories().declared(), c.configRevision(),
            DeriveConfig.DERIVE_VERSION, DeriveConfig.HASH_VERSION);
    }

    @Override
    public LedgerPage ledger(BlotterQuery query) {
        return reads.ledger(query);
    }

    @Override
    public List<ReviewRow> review(String kind) {
        return reads.review(kind);
    }

    @Override
    public List<TransferJson> transfers() {
        return reads.transfers();
    }

    @Override
    public List<UnitJson> units() {
        return reads.units();
    }

    @Override
    public ReconcileResponse reconcile() {
        DeriveConfig c = refresher.config();
        Set<String> declared = c.registry().accounts().values().stream()
            .filter(a -> a.balanceSource() == BalanceSource.DECLARED)
            .map(Account::ref)
            .collect(Collectors.toCollection(TreeSet::new));
        Map<String, Reconciliation.AccountResult> results = Reconciliation.reconcile(reads.currentFacts(), declared);
        List<ReconcileResponse.AccountJson> accounts = results.values().stream()
            .map(r -> new ReconcileResponse.AccountJson(r.accountRef(),
                r.status().name().toLowerCase(java.util.Locale.ROOT), r.reconcilable(), r.balances(),
                r.opening(), r.closing(), r.sum(), r.gap()))
            .toList();
        boolean ok = accounts.stream().allMatch(ReconcileResponse.AccountJson::balances);
        return new ReconcileResponse(ok, accounts);
    }

    // ---- decision path (V2-PROPOSAL.md §6.6, §6.8) -----------------------------------------

    /** Precheck against the index, apply the staleness check, and forward to the only writer. */
    @Override
    public DecisionOutcome submitDecisions(DecisionRequest request) {
        if (sequencer == null) {
            return new DecisionOutcome(503, new ErrorResponse("no sequencer configured; this hub is read-only"));
        }
        long head = reads.logHeadN();
        if (request.asOfN() != null && request.asOfN() != head) {
            return new DecisionOutcome(409, new ErrorResponse(
                "view moved: the index is at n=" + head + ", the request was built at n=" + request.asOfN()));
        }
        List<PrecheckResponse.Failure> failures = precheck(request.decisions());
        if (!failures.isEmpty()) {
            return new DecisionOutcome(422, new PrecheckResponse(failures));
        }
        try {
            BatchResponse response = sequencer.postDecisions(
                new DecisionBatch(request.allOrNone(), request.decisions()));
            return new DecisionOutcome(200, response);
        } catch (SequencerClient.Unavailable e) {
            return new DecisionOutcome(502, new ErrorResponse(e.getMessage()));
        }
    }

    private List<PrecheckResponse.Failure> precheck(List<DecisionDraft> drafts) {
        List<PrecheckResponse.Failure> failures = new ArrayList<>();
        if (drafts == null) {
            return failures;
        }
        DeriveConfig cfg = refresher.config();
        for (int i = 0; i < drafts.size(); i++) {
            String error = precheckOne(drafts.get(i), cfg);
            if (error != null) {
                failures.add(new PrecheckResponse.Failure(i, error));
            }
        }
        return failures;
    }

    private String precheckOne(DecisionDraft d, DeriveConfig cfg) {
        if (d == null) {
            return "missing decision";
        }
        if (d.action() == null || d.action().isBlank()) {
            return "action is required";
        }
        Action action;
        try {
            action = Action.fromWire(d.action());
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        if (d.actor() == null) {
            return "actor is required";
        }
        Actor actor;
        try {
            actor = Actor.fromWire(d.actor());
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        if (actor == Actor.USER) {
            if (d.user() == null || d.user().isBlank()) {
                return "a user decision must name its user";
            }
            if (cfg.registry().findUser(d.user()).isEmpty()) {
                return "unknown user '" + d.user() + "'";
            }
        }
        return switch (action) {
            case PAIR, UNPAIR -> twoFacts(d.legA(), d.legB(), "legA", "legB");
            case MARK_EXTERNAL -> oneFact(d.externalId(), "externalId");
            case SETTLE -> {
                String e = oneFact(d.pendingId(), "pendingId");
                yield e != null ? e : oneFact(d.postedId(), "postedId");
            }
            case DISMISS -> {
                String e = checkItem(d.item());
                yield e != null ? e : checkIds(d.externalIds(), "externalIds");
            }
            case PIN -> precheckPin(d, cfg);
            case UNPIN -> checkIds(d.externalIds(), "externalIds");
            case SUPERSEDE -> twoFacts(d.fromId(), d.toId(), "fromId", "toId");
            case RETIRE -> oneFact(d.externalId(), "externalId");
            case REVOKE -> {
                if (d.target() == null) {
                    yield "target is required";
                }
                yield reads.decisionKnown(d.target()) ? null
                    : "REVOKE names a decision n that does not exist: " + d.target();
            }
            case USER_ACK -> {
                if (d.period() == null || d.period().isBlank()) {
                    yield "period is required";
                }
                if (d.throughN() == null || d.configRevision() == null || d.deriveVersion() == null
                    || d.hashVersion() == null || d.stateHash() == null) {
                    yield "USER_ACK needs throughN, configRevision, deriveVersion, hashVersion and stateHash";
                }
                yield null;
            }
            case NOTE -> d.text() == null || d.text().isBlank() ? "text is required" : null;
        };
    }

    private String precheckPin(DecisionDraft d, DeriveConfig cfg) {
        String e = checkIds(d.externalIds(), "externalIds");
        if (e != null) {
            return e;
        }
        if (d.category() == null || d.category().isBlank()) {
            return "category is required";
        }
        if (RuleSet.isReserved(d.category())) {
            return "'" + d.category() + "' is reserved and cannot be pinned";
        }
        if (!cfg.categories().isDeclared(d.category())) {
            return "category '" + d.category() + "' is not declared";
        }
        for (String id : d.externalIds()) {
            if ("MATCHED".equals(reads.legOf(id))) {
                return "cannot pin a structural transfer leg '" + id + "'";
            }
        }
        return null;
    }

    private String oneFact(String id, String name) {
        if (id == null || id.isBlank()) {
            return name + " is required";
        }
        return reads.factKnown(id) ? null : name + " names an unknown fact '" + id + "'";
    }

    private String twoFacts(String a, String b, String nameA, String nameB) {
        String e = oneFact(a, nameA);
        if (e != null) {
            return e;
        }
        e = oneFact(b, nameB);
        if (e != null) {
            return e;
        }
        return a.equals(b) ? nameA + " and " + nameB + " must be different facts" : null;
    }

    private String checkIds(List<String> ids, String name) {
        if (ids == null || ids.isEmpty()) {
            return name + " is required";
        }
        for (String id : ids) {
            String e = oneFact(id, name);
            if (e != null) {
                return e;
            }
        }
        return null;
    }

    private static String checkItem(String item) {
        return item != null && Set.of("POTENTIAL_DUP", "RESTATEMENT", "AMBIGUOUS_TRANSFER",
            "AMBIGUOUS_SETTLEMENT", "UNMATCHED_LEG", "STALE_PENDING", "INEFFECTIVE_DECISION").contains(item)
            ? null
            : "item must be a review kind, not '" + item + "'";
    }

    private long journalSize() {
        try {
            return Files.exists(config.journal()) ? Files.size(config.journal()) : 0;
        } catch (IOException e) {
            return 0;
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        server.stop(0);
        refresher.close();
        reads.close();
        indexer.close();
        lock.close();
    }
}
