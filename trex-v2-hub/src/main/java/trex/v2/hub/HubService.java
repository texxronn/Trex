package trex.v2.hub;

import trex.v2.hub.api.ExpectedResponse;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import trex.v2.core.Action;
import trex.v2.core.Actor;
import trex.v2.core.Fact;
import trex.v2.core.config.Account;
import trex.v2.core.config.BalanceSource;
import trex.v2.core.config.DeriveConfig;
import trex.v2.core.config.Registry;
import trex.v2.core.config.RuleSet;
import trex.v2.core.config.TransferRules;
import trex.v2.core.config.User;
import trex.v2.core.derive.AmountKind;
import trex.v2.core.derive.Cadence;
import trex.v2.core.derive.ChainHealth;
import trex.v2.core.derive.CommitmentKind;
import trex.v2.core.derive.Commitments;
import trex.v2.core.derive.CurrentFact;
import trex.v2.core.derive.Derivation;
import trex.v2.core.derive.CategoryRow;
import trex.v2.core.derive.Opening;
import trex.v2.core.derive.Period;
import trex.v2.core.derive.Reconciliation;
import trex.v2.core.derive.ReviewItem;
import trex.v2.core.workbook.CatastrophicRegex;
import trex.v2.core.workbook.Workbook;
import trex.v2.core.Hashes;
import trex.v2.hub.api.AckJson;
import trex.v2.hub.api.AckRequest;
import trex.v2.hub.api.AccountsResponse;
import trex.v2.hub.api.ChainsResponse;
import trex.v2.hub.api.CursorRequest;
import trex.v2.hub.api.CursorResponse;
import trex.v2.hub.api.DecisionRequest;
import trex.v2.hub.api.EyeballResponse;
import trex.v2.hub.api.ReflowPreview;
import trex.v2.core.workbook.Workbook;
import trex.v2.log.Yaml;
import trex.v2.hub.api.ErrorResponse;
import trex.v2.hub.api.HeadResponse;
import trex.v2.hub.api.LedgerPage;
import trex.v2.hub.api.OpeningResponse;
import trex.v2.hub.api.PrecheckResponse;
import trex.v2.hub.api.ProjectionRequest;
import trex.v2.hub.api.ProjectionStateResponse;
import trex.v2.hub.api.ReconcileResponse;
import trex.v2.hub.api.RefdataResponse;
import trex.v2.hub.api.ReviewRow;
import trex.v2.hub.api.StatusResponse;
import trex.v2.hub.api.TransferJson;
import trex.v2.hub.api.TransferPreview;
import trex.v2.hub.api.UnitsResponse;
import trex.v2.index.IndexLock;
import trex.v2.index.Indexer;
import trex.v2.index.ProjectionRow;
import trex.v2.log.ConfigLoader;
import trex.v2.sequencer.api.BatchResponse;
import trex.v2.sequencer.api.DecisionBatch;
import trex.v2.sequencer.api.DecisionDraft;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
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

    /** A walk is one period; this is a guard against an unbounded scan, not a page size. */
    private static final int MAX_WALK_ROWS = 100_000;

    /** The Accounts window choices: months back, or {@code all} for every fact on record. */
    private static final Set<String> ACCOUNT_WINDOWS = Set.of("3m", "6m", "12m", "24m", "all");

    /** The commitment id slug (V2-COMMITMENTS-PLAN.md §2.1): lowercase, hyphenated, frozen. */
    private static final Pattern COMMITMENT_ID = Pattern.compile("[a-z0-9][a-z0-9-]*");

    private final HubConfig config;
    private final IndexLock lock;
    private final Indexer indexer;
    private final HubQueries reads;
    private final IndexRefresher refresher;
    private final SequencerClient sequencer;
    private final HubEvents events;
    private final RunnerClient runner;
    private final AtomicBoolean closed = new AtomicBoolean();
    private HttpServer server;

    private HubService(HubConfig config, IndexLock lock, Indexer indexer, HubQueries reads,
                       IndexRefresher refresher, SequencerClient sequencer, HubEvents events,
                       RunnerClient runner) {
        this.config = config;
        this.lock = lock;
        this.indexer = indexer;
        this.reads = reads;
        this.refresher = refresher;
        this.sequencer = sequencer;
        this.events = events;
        this.runner = runner;
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
            HubEvents events = new HubEvents();
            refresher = new IndexRefresher(config.journal(), config.configDir(), indexer,
                loaded.config(), config.refreshDebounceMs(), events, config.evidenceDir());
            SequencerClient sequencer = config.sequencerUrl() == null ? null
                : new SequencerClient(config.sequencerUrl(), hubSource());
            RunnerClient runner = config.runnerUrl() == null ? null : new RunnerClient(config.runnerUrl(), config.runnerToken());
            HubService service = new HubService(config, lock, indexer, reads, refresher, sequencer, events, runner);
            service.server = HubHttpApi.start(config.host(), config.port(), service, events, runner);
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

    /** The hub's 8-char writing-process id (§6); {@code TREX_HUB_SOURCE} overrides {@code HUB_0001}. */
    private static String hubSource() {
        String raw = System.getenv("TREX_HUB_SOURCE");
        String value = raw == null || raw.isBlank() ? "HUB_0001" : raw.trim();
        if (value.length() != 8) {
            throw new IllegalArgumentException("TREX_HUB_SOURCE must be exactly 8 characters: '" + value + "'");
        }
        return value;
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
        LocalDate today = LocalDate.now();
        Map<String, LocalDate> frontiers = reads.frontiers(today);
        Registry registry = c.registry();
        return new StatusResponse(reads.logHeadN(), offset, head, Math.max(0, head - offset),
            reads.counts(), reads.reviewByKind(), c.configRevision(),
            DeriveConfig.DERIVE_VERSION, DeriveConfig.HASH_VERSION,
            oldestFrontier(frontiers, statementBudget(registry)),
            staleAccounts(registry, frontiers, today));
    }

    /** The oldest frontier among {@code accounts}, from an already-read frontiers map; null if none. */
    private static LocalDate oldestFrontier(Map<String, LocalDate> frontiers, java.util.Set<String> accounts) {
        LocalDate oldest = null;
        for (Map.Entry<String, LocalDate> entry : frontiers.entrySet()) {
            if (accounts.contains(entry.getKey())
                && (oldest == null || entry.getValue().isBefore(oldest))) {
                oldest = entry.getValue();
            }
        }
        return oldest;
    }

    /**
     * The statement-age nudge (QOL_Improvements.md §2): every account whose frontier is older than
     * its effective fetch cadence, oldest first (ties by ref). A null or 0 cadence never nudges —
     * declared/clearing accounts unless one is set explicitly, and a closed account silenced with
     * {@code fetchEveryDays: 0} — and an account with no frontier has nothing to fetch yet. The day
     * count is today − frontier, so days > cadence is strictly past the cadence.
     */
    private static List<StatusResponse.Stale> staleAccounts(Registry registry,
            Map<String, LocalDate> frontiers, LocalDate today) {
        List<StatusResponse.Stale> stale = new ArrayList<>();
        for (Map.Entry<String, LocalDate> entry : frontiers.entrySet()) {
            Account account = registry.accounts().get(entry.getKey());
            Integer cadence = account == null ? null : account.fetchEveryDays();
            if (cadence == null || cadence == 0) {
                continue;
            }
            int days = (int) java.time.temporal.ChronoUnit.DAYS.between(entry.getValue(), today);
            if (days > cadence) {
                stale.add(new StatusResponse.Stale(account.ref(), entry.getValue(), days, cadence));
            }
        }
        stale.sort(java.util.Comparator.comparingInt(StatusResponse.Stale::days).reversed()
            .thenComparing(StatusResponse.Stale::account));
        return stale;
    }

    /**
     * The budget accounts that have statements: a cash account has no statement to be late, so it
     * never holds a "through" date back (V2-REVIEW-FIXES-PLAN.md §10, §11).
     */
    private static java.util.Set<String> statementBudget(Registry registry) {
        java.util.Set<String> out = new java.util.TreeSet<>();
        for (var account : registry.accounts().values()) {
            if (account.budget() && account.balanceSource() == trex.v2.core.config.BalanceSource.STATEMENT) {
                out.add(account.ref());
            }
        }
        return out;
    }

    @Override
    public RefdataResponse refdata() {
        DeriveConfig c = refresher.config();
        Registry registry = c.registry();
        List<RefdataResponse.AccountJson> accounts = registry.accounts().values().stream()
            .map(a -> new RefdataResponse.AccountJson(a.ref(), a.currency(), a.balanceSource().wire(),
                a.settlementWindowDays(), a.chipColor()))
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
    public List<ReviewRow> review(String kind, String account) {
        return reads.review(kind, account, refresher.config().transfers());
    }

    @Override
    public List<trex.v2.hub.api.CommitmentJson> commitments() {
        return reads.commitments();
    }

    @Override
    public List<trex.v2.hub.api.ActivityJson> activity(String commitmentId) {
        return reads.activity(commitmentId);
    }

    /**
     * The Expected view (§2.8): the window is a calendar period, spelled with the same grains as
     * every other mode — today is one day, week is the ISO week, month the calendar month — and the
     * occurrences themselves are the materialised rows, filtered by due date. The clock enters only
     * as the default measurement day, exactly as {@link #accounts}.
     */
    @Override
    public trex.v2.hub.api.ExpectedResponse expected(String window, LocalDate asOf) {
        String win = window == null || window.isBlank() ? "month" : window;
        LocalDate at = asOf == null ? LocalDate.now() : asOf;
        Period.Range range = switch (win) {
            case "today" -> Period.bounds(at.toString());
            case "week" -> Period.bounds(Period.weekKey(at));
            case "month" -> Period.bounds(YearMonth.from(at).toString());
            default -> throw new IllegalArgumentException(
                "window must be today, week or month, not '" + win + "'");
        };
        ExpectedResponse base = reads.expected(win, range);
        java.util.Set<String> budget = new java.util.TreeSet<>();
        for (var account : refresher.config().registry().accounts().values()) {
            if (account.budget()) {
                budget.add(account.ref());
            }
        }
        ExpectedResponse.Headroom headroom = reads.headroom(Period.bounds(YearMonth.from(at).toString()), at,
            budget, statementBudget(refresher.config().registry()));
        return new ExpectedResponse(base.window(), base.from(), base.to(), base.occurrences(), base.arrears(),
            base.totals(), headroom);
    }

    @Override
    public List<TransferJson> transfers() {
        return reads.transfers();
    }

    @Override
    public List<trex.v2.hub.api.NoteJson> notes(String externalId) {
        return reads.notes(externalId);
    }

    @Override
    public List<trex.v2.hub.api.DismissalJson> dismissals() {
        return reads.dismissals();
    }

    @Override
    public UnitsResponse units() {
        DeriveConfig c = refresher.config();
        return new UnitsResponse(reads.logHeadN(), c.configRevision(), DeriveConfig.DERIVE_VERSION,
            DeriveConfig.HASH_VERSION, reads.projectionUnits());
    }

    @Override
    public ProjectionStateResponse projection() {
        return new ProjectionStateResponse(indexer.projection());
    }

    @Override
    public DecisionOutcome putProjection(ProjectionRequest request) {
        List<ProjectionRow> rows = request.rows() == null ? List.of() : request.rows();
        if (Boolean.TRUE.equals(request.replace())) {
            indexer.replaceProjection(rows);
        } else {
            indexer.upsertProjection(rows);
        }
        return new DecisionOutcome(200, Map.of("recorded", rows.size()));
    }

    @Override
    public CursorResponse cursors() {
        return new CursorResponse(indexer.cursors());
    }

    @Override
    public DecisionOutcome putCursors(CursorRequest request) {
        Map<String, String> cursors = request.cursors() == null ? Map.of() : request.cursors();
        String at = Instant.now().toString();
        cursors.forEach((source, cursor) -> indexer.putCursor(source, cursor, at));
        return new DecisionOutcome(200, Map.of("recorded", cursors.size()));
    }

    @Override
    public OpeningResponse opening() {
        return new OpeningResponse(Opening.of(reads.currentFacts(), refresher.config().registry(),
            reads.transferRows()));
    }

    private static Set<String> declaredAccounts(DeriveConfig c) {
        return c.registry().accounts().values().stream()
            .filter(a -> a.balanceSource() == BalanceSource.DECLARED)
            .map(Account::ref)
            .collect(Collectors.toCollection(TreeSet::new));
    }

    @Override
    public ChainsResponse chains(String account) {
        DeriveConfig c = refresher.config();
        Set<String> declared = declaredAccounts(c);
        List<Fact> transactions = reads.currentFacts();
        List<Fact> noops = reads.currentNoops();
        Map<String, ChainHealth.Account> health = ChainHealth.of(transactions, noops, declared);
        List<ChainsResponse.AccountJson> accounts = new ArrayList<>();
        for (ChainHealth.Account a : health.values()) {
            if (account != null && !account.equals(a.accountRef())) {
                continue;
            }
            // Preview every row named by a fork; the resolver never picks which side for you.
            Set<String> candidates = new TreeSet<>();
            a.forks().forEach(f -> candidates.addAll(f.externalIds()));
            List<ChainsResponse.PreviewJson> previews = new ArrayList<>();
            for (String id : candidates) {
                ChainHealth.Preview p = ChainHealth.preview(transactions, noops, declared, id);
                if (p != null) {
                    previews.add(new ChainsResponse.PreviewJson(p.externalId(), statusWire(p.status()),
                        p.reconciled(), p.opening(), p.closing(), forksJson(p.remainingForks())));
                }
            }
            accounts.add(new ChainsResponse.AccountJson(a.accountRef(), statusWire(a.status()), a.reconciled(),
                a.opening(), a.closing(), a.sum(), a.gap(), a.exclusions(), forksJson(a.forks()), previews));
        }
        return new ChainsResponse(accounts);
    }

    private static String statusWire(Reconciliation.Status status) {
        return status.name().toLowerCase(java.util.Locale.ROOT);
    }

    private static List<ChainsResponse.ForkJson> forksJson(List<ChainHealth.Fork> forks) {
        return forks.stream().map(f -> new ChainsResponse.ForkJson(f.value(), f.side().name(),
            f.externalIds())).toList();
    }

    @Override
    public ReconcileResponse reconcile() {
        DeriveConfig c = refresher.config();
        Set<String> declared = declaredAccounts(c);
        List<Fact> transactions = reads.currentFacts();
        List<Fact> noops = reads.currentNoops();
        Map<String, Fact> noopById = new HashMap<>();
        for (Fact f : noops) {
            noopById.put(f.externalId(), f);
        }
        Map<String, Reconciliation.AccountResult> results =
            Reconciliation.reconcile(transactions, noops, declared);
        // A clearing account holds no facts: opening computed, closing declared, never broken (§6.10).
        List<trex.v2.core.derive.TransferRow> transferRows = reads.transferRows();
        Map<String, Long> clearingReal = clearingRealAmounts(transferRows, transactions);
        for (Account a : c.registry().accounts().values()) {
            if (a.clearing()) {
                long opening = a.closingBalance() + clearingReal.getOrDefault(a.ref(), 0L);
                results.put(a.ref(), Reconciliation.clearing(a.ref(), opening, a.closingBalance()));
            }
        }
        List<ReconcileResponse.AccountJson> accounts = results.values().stream()
            .sorted(java.util.Comparator.comparing(Reconciliation.AccountResult::accountRef))
            .map(r -> new ReconcileResponse.AccountJson(r.accountRef(),
                r.status().name().toLowerCase(java.util.Locale.ROOT), r.reconcilable(), r.balances(),
                r.opening(), r.closing(), r.sum(), r.gap(),
                r.exclusions().stream().map(id -> exclusion(id, noopById.get(id), c)).toList()))
            .toList();
        boolean ok = accounts.stream().allMatch(ReconcileResponse.AccountJson::balances);
        return new ReconcileResponse(ok, accounts);
    }

    /** Why one noop row was excluded (§6.9): a MARK_NOOP decision wins over the profile rule. */
    private ReconcileResponse.ExclusionJson exclusion(String id, Fact f, DeriveConfig c) {
        HubQueries.RoleDecision d = reads.roleDecision(id);
        if (d != null && "MARK_NOOP".equals(d.action())) {
            return new ReconcileResponse.ExclusionJson(id, "decision " + d.n(), d.reason());
        }
        String reason = f == null ? null : c.profiles().reasonFor(f.accountRef(), f.rawDescription());
        return new ReconcileResponse.ExclusionJson(id, "profile", reason);
    }

    /** Per clearing account, the sum of its real legs' amounts — the movements it absorbed (§6.10). */
    private static Map<String, Long> clearingRealAmounts(List<trex.v2.core.derive.TransferRow> transfers,
                                                         List<Fact> facts) {
        Map<String, Fact> byId = new HashMap<>();
        for (Fact f : facts) {
            byId.put(f.externalId(), f);
        }
        Map<String, Long> out = new HashMap<>();
        for (trex.v2.core.derive.TransferRow t : transfers) {
            if (t.clearingAccount() == null) {
                continue;
            }
            String realId = t.clearingAccount().equals(t.fromLeg()) ? t.toLeg() : t.fromLeg();
            Fact real = byId.get(realId);
            if (real != null) {
                out.merge(t.clearingAccount(), real.amount(), Long::sum);
            }
        }
        return out;
    }

    // ---- eyeball markers (V2-PROPOSAL.md §9.4) ----------------------------------------------

    @Override
    public List<AckJson> acks() {
        Map<String, String> current = reads.currentStateHashes();
        return reads.userAcks().stream().map(a -> {
            String hash = current.get(a.externalId());
            boolean stale = !DeriveConfig.HASH_VERSION.equals(a.hashVersion())
                || hash == null || !hash.equals(a.stateHash());
            return new AckJson(a.userId(), a.externalId(), a.stateHash(), stale, a.ackedAt());
        }).toList();
    }

    /** The eyeball walk for one period (§10.3): open items, anomalies, and day/week/month buckets. */
    @Override
    public EyeballResponse eyeball(String period, String user, LocalDate asOf, String granularity) {
        if (period == null || period.isBlank()) {
            throw new IllegalArgumentException("period is required");
        }
        String bucket = granularity == null || granularity.isBlank() ? "day" : granularity;
        if (!Set.of("day", "week", "month").contains(bucket)) {
            throw new IllegalArgumentException("bucket must be day, week or month, not '" + bucket + "'");
        }
        Period.Range range = Period.bounds(period);
        DeriveConfig c = refresher.config();
        LocalDate at = asOf == null ? LocalDate.now() : asOf;
        List<Fact> facts = reads.currentFacts();
        LedgerPage page = reads.ledger(new BlotterQuery(null, null, null, null, null, range.from(), range.to(),
            null, null, null, false, "date", "asc", MAX_WALK_ROWS, 0));
        return Eyeball.walk(period, user, at, bucket, facts, page.rows(), reads.review(null, null, c.transfers()), reads.pending(),
            c.registry(), c.transfers());
    }

    /** Read or release one row for one user: forward a USER_ACK/USER_UNACK to the writer. */
    @Override
    public DecisionOutcome postAck(AckRequest request) {
        DeriveConfig c = refresher.config();
        if (request.user() == null || c.registry().findUser(request.user()).isEmpty()) {
            return new DecisionOutcome(422, new PrecheckResponse(List.of(
                new PrecheckResponse.Failure(0, "unknown user '" + request.user() + "'"))));
        }
        if (request.externalId() == null || request.externalId().isBlank()) {
            return new DecisionOutcome(422, new PrecheckResponse(List.of(
                new PrecheckResponse.Failure(0, "externalId is required"))));
        }
        String action = request.action() == null || request.action().isBlank()
            ? "ACK" : request.action().trim().toUpperCase(java.util.Locale.ROOT);
        if (!action.equals("ACK") && !action.equals("UNACK")) {
            return new DecisionOutcome(422, new PrecheckResponse(List.of(
                new PrecheckResponse.Failure(0, "action must be ACK or UNACK, not '" + request.action() + "'"))));
        }
        String stateHash = null;
        if (action.equals("ACK")) {
            stateHash = reads.rowStateHash(request.externalId());
            if (stateHash == null) {
                return new DecisionOutcome(422, new PrecheckResponse(List.of(
                    new PrecheckResponse.Failure(0,
                        "externalId names no current row '" + request.externalId() + "'"))));
            }
        }
        DecisionDraft ack = new DecisionDraft(action.equals("ACK") ? "USER_ACK" : "USER_UNACK",
            "user", request.user(), null, request.comment(),
            null, null, request.externalId(), null, null, null, null, null, null, null, null, null,
            c.configRevision(), DeriveConfig.DERIVE_VERSION, DeriveConfig.HASH_VERSION, stateHash, null, null);
        return submitDecisions(new DecisionRequest(null, null, List.of(ack)));
    }

    // ---- reflow preview (V2-PROPOSAL.md §9.3) -----------------------------------------------

    /** Run {@code derive()} against a candidate rule set and return what would move. Writes nothing. */
    @Override
    public DecisionOutcome reflowPreview(String categoriesYaml) {
        if (categoriesYaml == null || categoriesYaml.isBlank()) {
            return new DecisionOutcome(422, new ErrorResponse("categories is required"));
        }
        DeriveConfig current = refresher.config();
        RuleSet candidate;
        try {
            RuleSet.File parsed = Yaml.mapper().readValue(categoriesYaml, RuleSet.File.class);
            candidate = RuleSet.compile("candidate categories.yaml", parsed);
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            return new DecisionOutcome(422, new ErrorResponse(
                "candidate categories failed to parse: " + e.getOriginalMessage()));
        } catch (IllegalArgumentException e) {
            return new DecisionOutcome(422, new ErrorResponse(e.getMessage()));
        }
        DeriveConfig candidateConfig = new DeriveConfig(current.registry(), candidate,
            current.transfers(), current.profiles(), Hashes.sha256(categoriesYaml));

        Instant now = Instant.now();
        Derivation before = indexer.deriveWith(current, now, Long.MAX_VALUE);
        Derivation after = indexer.deriveWith(candidateConfig, now, Long.MAX_VALUE);

        Map<String, CategoryRow> b = categoriesById(before);
        Map<String, CategoryRow> a = categoriesById(after);
        Set<String> ids = new TreeSet<>(b.keySet());
        ids.addAll(a.keySet());
        List<ReflowPreview.MovedCategory> moved = new ArrayList<>();
        for (String id : ids) {
            String from = b.containsKey(id) ? b.get(id).category() : null;
            String to = a.containsKey(id) ? a.get(id).category() : null;
            if (!java.util.Objects.equals(from, to)) {
                moved.add(new ReflowPreview.MovedCategory(id, from, to));
            }
        }
        Set<String> beforeTransfers = before.transfers().stream()
            .map(trex.v2.core.derive.TransferRow::transferId).collect(Collectors.toSet());
        Set<String> afterTransfers = after.transfers().stream()
            .map(trex.v2.core.derive.TransferRow::transferId).collect(Collectors.toSet());
        int transfersAdded = (int) afterTransfers.stream().filter(t -> !beforeTransfers.contains(t)).count();
        int transfersRemoved = (int) beforeTransfers.stream().filter(t -> !afterTransfers.contains(t)).count();
        Set<String> beforeReview = reviewKeys(before);
        Set<String> afterReview = reviewKeys(after);
        int reviewOpened = (int) afterReview.stream().filter(r -> !beforeReview.contains(r)).count();
        int reviewCleared = (int) beforeReview.stream().filter(r -> !afterReview.contains(r)).count();

        return new DecisionOutcome(200, new ReflowPreview(current.configRevision(),
            candidateConfig.configRevision(), moved.size(), moved, transfersAdded, transfersRemoved,
            reviewOpened, reviewCleared));
    }

    private static Map<String, CategoryRow> categoriesById(Derivation d) {
        Map<String, CategoryRow> out = new TreeMap<>();
        for (CategoryRow c : d.categories()) {
            out.put(c.externalId(), c);
        }
        return out;
    }

    private static Set<String> reviewKeys(Derivation d) {
        return d.review().stream().map(r -> r.kind() + "|" + r.subject())
            .collect(Collectors.toCollection(TreeSet::new));
    }

    // ---- workbook (V2-PROPOSAL.md §10.4) ----------------------------------------------------

    /** Lint, coverage and suggestions, computed from a fresh derivation at the current config. */
    @Override
    public Workbook.Report workbook() {
        DeriveConfig c = refresher.config();
        Derivation d = indexer.deriveWith(c, Instant.now(), Long.MAX_VALUE);
        return Workbook.of(c, d);
    }

    /** The evidence ids already on facts, for the Jobs view's ingested tick (§5.5, §12.5). */
    @Override
    public Set<String> ingestedEvidenceIds() {
        return reads.ingestedEvidenceIds();
    }

    /**
     * The ingest history (§12.6); {@code sinceN} keeps only batches completed after it (50 is the
     * Jobs page size, applied to the unfiltered history only).
     */
    @Override
    public trex.v2.hub.api.IngestsResponse ingests(Long sinceN) {
        return new trex.v2.hub.api.IngestsResponse(reads.ingests(50, sinceN));
    }

    // ---- accounts overview (V2-PROPOSAL.md §10.1, §10.5) -------------------------------------

    /**
     * Per-account earliest/latest and the facts-derived coverage strip. Writes nothing: the strip
     * is recomputed from the current rows on every request, and a hole means only "no rows here",
     * never "not imported" — the log records no statement periods.
     */
    @Override
    public AccountsResponse accounts(String window, String granularity, LocalDate asOf) {
        String win = window == null || window.isBlank() ? "12m" : window;
        if (!ACCOUNT_WINDOWS.contains(win)) {
            throw new IllegalArgumentException(
                "window must be one of 3m, 6m, 12m, 24m or all, not '" + win + "'");
        }
        String grain = granularity == null || granularity.isBlank() ? "week" : granularity;
        if (!Set.of("week", "month").contains(grain)) {
            throw new IllegalArgumentException("granularity must be week or month, not '" + grain + "'");
        }
        boolean week = "week".equals(grain);
        LocalDate today = asOf == null ? LocalDate.now() : asOf;
        Map<String, HubQueries.AccountTotals> totals = reads.accountTotals();

        LocalDate rawFrom = "all".equals(win)
            ? totals.values().stream().map(HubQueries.AccountTotals::first).min(LocalDate::compareTo)
                .orElse(today.minusMonths(12))
            : today.minusMonths(Long.parseLong(win.substring(0, win.length() - 1)));
        LocalDate from = week ? mondayOnOrBefore(rawFrom) : rawFrom.withDayOfMonth(1);
        LocalDate to = week
            ? mondayOnOrBefore(today).plusDays(6)
            : today.withDayOfMonth(1).plusMonths(1).minusDays(1);

        // One pass over the window: per bucket, the row count and the file(s) that appended them.
        Map<String, Long> counts = new HashMap<>();
        Map<String, List<String>> files = new HashMap<>();
        for (HubQueries.CoverageRow row : reads.coverage(from, to)) {
            String key = row.accountRef() + "|" + bucketKey(row.date(), grain);
            counts.merge(key, 1L, Long::sum);
            if (row.file() != null) {
                List<String> names = files.computeIfAbsent(key, k -> new ArrayList<>());
                if (!names.contains(row.file())) {
                    names.add(row.file());
                }
            }
        }
        Map<String, HubQueries.LastImport> imports = new LinkedHashMap<>();
        for (HubQueries.LastImport imp : reads.lastImports()) {
            imports.put(imp.accountRef(), imp);
        }

        Map<String, Opening.PerAccount> openings = new HashMap<>();
        for (Opening.PerAccount p : Opening.of(reads.currentFacts(), refresher.config().registry(),
                reads.transferRows())) {
            openings.put(p.accountRef(), p);
        }
        List<AccountsResponse.AccountCoverage> accounts = new ArrayList<>();
        for (Account account : refresher.config().registry().accounts().values()) {
            HubQueries.AccountTotals total = totals.get(account.ref());
            String firstKey = total == null ? null : bucketKey(total.first(), grain);
            String lastKey = total == null ? null : bucketKey(total.last(), grain);
            List<AccountsResponse.Bucket> buckets = new ArrayList<>();
            long txnsInWindow = 0;
            long holes = 0;
            for (LocalDate d = from; !d.isAfter(to); d = week ? d.plusWeeks(1) : d.plusMonths(1)) {
                String key = bucketKey(d, grain);
                long count = counts.getOrDefault(account.ref() + "|" + key, 0L);
                txnsInWindow += count;
                String state;
                if (total == null) {
                    state = "none";
                } else if (key.compareTo(firstKey) < 0) {
                    state = "before";
                } else if (key.compareTo(lastKey) > 0) {
                    state = "after";
                } else if (count > 0) {
                    state = "facts";
                } else {
                    state = "hole";
                    holes++;
                }
                LocalDate bucketTo = week ? d.plusDays(6) : d.withDayOfMonth(d.lengthOfMonth());
                buckets.add(new AccountsResponse.Bucket(key, d, bucketTo, count, state,
                    files.getOrDefault(account.ref() + "|" + key, List.of())));
            }
            HubQueries.LastImport imp = imports.get(account.ref());
            Opening.PerAccount op = openings.get(account.ref());
            accounts.add(new AccountsResponse.AccountCoverage(account.ref(), account.currency(),
                account.balanceSource().wire(), op == null ? null : op.backwardOpening(),
                total == null ? null : total.first(),
                total == null ? null : total.last(), total == null ? 0 : total.txns(),
                txnsInWindow, holes,
                imp == null ? null : new AccountsResponse.Import(imp.file(), imp.status(), imp.startedMs()),
                buckets));
        }
        return new AccountsResponse(win, grain, from, to, accounts);
    }

    /** The ISO week key for a date, or the calendar month ({@code 2026-09}) at month grain. */
    private static String bucketKey(LocalDate date, String granularity) {
        return "month".equals(granularity) ? YearMonth.from(date).toString() : Period.weekKey(date);
    }

    /** The Monday of the ISO week containing the date. */
    private static LocalDate mondayOnOrBefore(LocalDate date) {
        return date.minusDays(date.getDayOfWeek().getValue() - 1L);
    }

    // ---- rule files (V2-PROPOSAL.md §7.4 point 6, §9.3) -------------------------------------
    /** The current {@code categories.yaml} text, for the rule editor. */
    @Override
    public Optional<String> categoriesYaml() {
        Path file = config.configDir().resolve("categories.yaml");
        try {
            return Files.exists(file) ? Optional.of(Files.readString(file)) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * Validate a candidate and write it atomically; the watcher then reloads and re-derives. Rule
     * edits go through the hub, so a rule swap and an index write serialise behind the same lock.
     */
    @Override
    public DecisionOutcome saveCategories(String categoriesYaml) {
        if (categoriesYaml == null || categoriesYaml.isBlank()) {
            return new DecisionOutcome(422, new ErrorResponse("categories is required"));
        }
        try {
            RuleSet.File parsed = Yaml.mapper().readValue(categoriesYaml, RuleSet.File.class);
            RuleSet.compile("categories.yaml", parsed);
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            return new DecisionOutcome(422, new ErrorResponse(
                "candidate categories failed to parse: " + e.getOriginalMessage()));
        } catch (IllegalArgumentException e) {
            return new DecisionOutcome(422, new ErrorResponse(e.getMessage()));
        }
        Path file = config.configDir().resolve("categories.yaml");
        try {
            Path tmp = file.resolveSibling("categories.yaml.tmp");
            Files.writeString(tmp, categoriesYaml);
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            return new DecisionOutcome(500, new ErrorResponse("cannot write " + file + ": " + e.getMessage()));
        }
        return new DecisionOutcome(200, Map.of("saved", true));
    }

    // ---- transfer-pattern preview (V2-PROPOSAL.md §9.3, §9.9.C) -----------------------------

    /** Run {@code derive()} against a candidate {@code transfers.yaml}; writes nothing. */
    @Override
    public DecisionOutcome transfersPreview(String transfersYaml) {
        if (transfersYaml == null || transfersYaml.isBlank()) {
            return new DecisionOutcome(422, new ErrorResponse("transfers is required"));
        }
        DeriveConfig current = refresher.config();
        TransferRules candidate;
        try {
            candidate = ConfigLoader.compileTransferRules(transfersYaml, current.registry());
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            return new DecisionOutcome(422, new ErrorResponse(
                "candidate transfers failed to parse: " + e.getOriginalMessage()));
        } catch (IllegalArgumentException e) {
            return new DecisionOutcome(422, new ErrorResponse(e.getMessage()));
        }
        DeriveConfig candidateConfig = new DeriveConfig(current.registry(), current.categories(),
            candidate, current.profiles(), Hashes.sha256(transfersYaml));

        Instant now = Instant.now();
        Derivation before = indexer.deriveWith(current, now, Long.MAX_VALUE);
        Derivation after = indexer.deriveWith(candidateConfig, now, Long.MAX_VALUE);

        Map<String, String> b = legById(before);
        Map<String, String> a = legById(after);
        Set<String> ids = new TreeSet<>(b.keySet());
        ids.addAll(a.keySet());
        List<TransferPreview.MovedLeg> moved = new ArrayList<>();
        for (String id : ids) {
            String from = b.get(id);
            String to = a.get(id);
            if (!java.util.Objects.equals(from, to)) {
                moved.add(new TransferPreview.MovedLeg(id, from, to));
            }
        }
        Set<String> beforeTransfers = before.transfers().stream()
            .map(trex.v2.core.derive.TransferRow::transferId).collect(Collectors.toSet());
        Set<String> afterTransfers = after.transfers().stream()
            .map(trex.v2.core.derive.TransferRow::transferId).collect(Collectors.toSet());
        int pairsAdded = (int) afterTransfers.stream().filter(t -> !beforeTransfers.contains(t)).count();
        int pairsRemoved = (int) beforeTransfers.stream().filter(t -> !afterTransfers.contains(t)).count();
        Set<String> beforeReview = reviewKeys(before);
        Set<String> afterReview = reviewKeys(after);
        int reviewOpened = (int) afterReview.stream().filter(r -> !beforeReview.contains(r)).count();
        int reviewCleared = (int) beforeReview.stream().filter(r -> !afterReview.contains(r)).count();

        return new DecisionOutcome(200, new TransferPreview(current.configRevision(),
            candidateConfig.configRevision(), pairsAdded, pairsRemoved, reviewOpened, reviewCleared, moved));
    }

    private static Map<String, String> legById(Derivation d) {
        Map<String, String> out = new TreeMap<>();
        for (CurrentFact c : d.current()) {
            out.put(c.externalId(), c.leg().name());
        }
        return out;
    }

    /** The current {@code transfers.yaml} text, for the pattern editor. */
    @Override
    public Optional<String> transfersYaml() {
        Path file = config.configDir().resolve("transfers.yaml");
        try {
            return Files.exists(file) ? Optional.of(Files.readString(file)) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** Validate a candidate {@code transfers.yaml} and write it atomically; the watcher re-derives. */
    @Override
    public DecisionOutcome saveTransfers(String transfersYaml) {
        if (transfersYaml == null || transfersYaml.isBlank()) {
            return new DecisionOutcome(422, new ErrorResponse("transfers is required"));
        }
        try {
            ConfigLoader.compileTransferRules(transfersYaml, refresher.config().registry());
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            return new DecisionOutcome(422, new ErrorResponse(
                "candidate transfers failed to parse: " + e.getOriginalMessage()));
        } catch (IllegalArgumentException e) {
            return new DecisionOutcome(422, new ErrorResponse(e.getMessage()));
        }
        Path file = config.configDir().resolve("transfers.yaml");
        try {
            Path tmp = file.resolveSibling("transfers.yaml.tmp");
            Files.writeString(tmp, transfersYaml);
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            return new DecisionOutcome(500, new ErrorResponse("cannot write " + file + ": " + e.getMessage()));
        }
        return new DecisionOutcome(200, Map.of("saved", true));
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
                yield e != null ? e : dismissSubjects(d.item(), d.externalIds(), cfg);
            }
            case PIN -> precheckPin(d, cfg);
            case UNPIN -> checkIds(d.externalIds(), "externalIds");
            case SUPERSEDE -> twoFacts(d.fromId(), d.toId(), "fromId", "toId");
            case RETIRE -> oneFact(d.externalId(), "externalId");
            case MARK_NOOP -> {
                String e = oneFact(d.externalId(), "externalId");
                yield e != null ? e : (d.reason() == null || d.reason().isBlank()
                    ? "reason is required" : null);
            }
            case UNMARK_NOOP -> oneFact(d.externalId(), "externalId");
            case REVOKE -> {
                if (d.target() == null) {
                    yield "target is required";
                }
                yield reads.decisionKnown(d.target()) ? null
                    : "REVOKE names a decision n that does not exist: " + d.target();
            }
            case USER_ACK -> {
                if (d.externalId() == null || d.externalId().isBlank()) {
                    yield "externalId is required";
                }
                if (reads.rowStateHash(d.externalId()) == null) {
                    yield "USER_ACK names no current row '" + d.externalId() + "'";
                }
                if (d.configRevision() == null || d.deriveVersion() == null
                    || d.hashVersion() == null || d.stateHash() == null) {
                    yield "USER_ACK needs configRevision, deriveVersion, hashVersion and stateHash";
                }
                yield null;
            }
            case USER_UNACK -> oneFact(d.externalId(), "externalId");
            case NOTE -> {
                String e = oneFact(d.externalId(), "externalId");
                if (e != null) {
                    yield e;
                }
                if (d.text() == null || d.text().isBlank()) {
                    yield "text is required";
                }
                yield d.text().length() > 2000 ? "text is too long (max 2000 characters)" : null;
            }
            case ATTACH_ACCOUNT -> {
                String e = checkIds(d.externalIds(), "externalIds");
                if (e != null) {
                    yield e;
                }
                if (d.account() == null || d.account().isBlank()) {
                    yield "account is required";
                }
                var account = cfg.registry().findAccount(d.account());
                yield account.isEmpty() ? "unknown account '" + d.account() + "'"
                    : (account.get().clearing() ? null : "ATTACH_ACCOUNT must name a clearing account");
            }
            case DECLARE_COMMITMENT -> precheckDeclare(d);
            case RETIRE_COMMITMENT -> {
                String e = required(d.commitmentId(), "commitmentId");
                yield e != null ? e : (d.endedAt() == null ? "endedAt is required" : null);
            }
            case IGNORE_RECURRING -> {
                String e = required(d.candidate(), "candidate");
                yield e != null ? e : required(d.reason(), "reason");
            }
            case PIN_COMMITMENT -> {
                String e = checkIds(d.externalIds(), "externalIds");
                yield e != null ? e : checkCommitmentTarget(d.commitmentId(), true);
            }
            case UNPIN_COMMITMENT -> checkIds(d.externalIds(), "externalIds");
            case EXCLUDE_COMMITMENT, INCLUDE_COMMITMENT -> {
                String e = checkIds(d.externalIds(), "externalIds");
                yield e != null ? e : checkCommitmentTarget(d.commitmentId(), false);
            }
            case NOTE_COMMITMENT -> {
                String e = checkCommitmentTarget(d.commitmentId(), false);
                if (e != null) {
                    yield e;
                }
                if (d.text() == null || d.text().isBlank()) {
                    yield "text is required";
                }
                yield d.text().length() > 2000 ? "text is too long (max 2000 characters)" : null;
            }
            case SETTLE_OCCURRENCE -> {
                String e = checkCommitmentTarget(d.commitmentId(), false);
                yield e != null ? e : (d.dueDates() == null || d.dueDates().isEmpty()
                    ? "dueDates is required" : null);
            }
        };
    }

    /** The declaration shape (V2-COMMITMENTS-PLAN.md §2.6, §2.2): id, faces and a usable rule set. */
    private String precheckDeclare(DecisionDraft d) {
        String id = d.commitmentId();
        if (id == null || id.isBlank()) {
            return "commitmentId is required";
        }
        if (id.length() > 64 || !COMMITMENT_ID.matcher(id).matches()) {
            return "commitmentId must be a slug of a-z0-9 and '-', max 64 chars: '" + id + "'";
        }
        String e = required(d.name(), "name");
        if (e != null) {
            return e;
        }
        String direction = d.direction();
        if (direction == null || direction.isBlank()) {
            return "direction is required";
        }
        if (!direction.equals("in") && !direction.equals("out")) {
            return "direction must be 'in' or 'out', not '" + direction + "'";
        }
        e = checkWire("cadence", d.cadence(), Cadence::fromWire);
        if (e == null) {
            e = checkWire("amountKind", d.amountKind(), AmountKind::fromWire);
        }
        if (e == null) {
            e = checkWire("kind", d.kind(), CommitmentKind::fromWire);
        }
        if (e == null) {
            e = checkMatches(d.matches());
        }
        if (e == null && d.fromCandidate() != null && d.fromCandidate().isBlank()) {
            e = "fromCandidate must not be blank";
        }
        return e;
    }

    private static String checkWire(String name, String wire, java.util.function.Function<String, ?> parse) {
        if (wire == null || wire.isBlank()) {
            return name + " is required";
        }
        try {
            parse.apply(wire);
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    /** Each rule compiles case-insensitively and passes the catastrophic-backtracking lint (§2.2). */
    private static String checkMatches(List<DecisionDraft.MatchDraft> matches) {
        if (matches == null || matches.isEmpty()) {
            return "matches is required";
        }
        for (DecisionDraft.MatchDraft m : matches) {
            if (m == null || m.match() == null || m.match().isBlank()) {
                return "each match must name a regex";
            }
            try {
                Pattern.compile(m.match(), Pattern.CASE_INSENSITIVE);
            } catch (java.util.regex.PatternSyntaxException e) {
                return "match '" + m.match() + "' is not a valid regex: " + e.getDescription();
            }
            if (CatastrophicRegex.risky(m.match())) {
                return "match '" + m.match() + "' is risky for backtracking";
            }
        }
        return null;
    }

    private static String required(String value, String name) {
        return value == null || value.isBlank() ? name + " is required" : null;
    }

    /**
     * The commitment a decision names (V2-COMMITMENTS-PLAN.md §2.6): it must be declared — a
     * candidate id or an unknown slug is ineffective in the fold and refused by the writer, so the
     * hub says 422 first. A pin additionally refuses a retired commitment, because a pin to an
     * ended commitment can never win; a note, a settle and an exclusion are conclusions about the
     * past, so they may name one. A retire keeps its Stage 3 semantics: the writer decides, and an
     * undeclared id surfaces as {@code INEFFECTIVE_DECISION}.
     */
    private String checkCommitmentTarget(String commitmentId, boolean mustBeUnretired) {
        if (commitmentId == null || commitmentId.isBlank()) {
            return "commitmentId is required";
        }
        HubQueries.CommitmentRef ref = reads.commitmentRef(commitmentId);
        if (ref == null) {
            return "commitmentId names an unknown commitment '" + commitmentId + "'";
        }
        if (!ref.declared()) {
            return "commitmentId names an undeclared commitment '" + commitmentId + "'";
        }
        if (mustBeUnretired && ref.retired()) {
            return "commitment '" + commitmentId + "' is retired";
        }
        return null;
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

    /** A list of declared account refs; the subject shape of a BALANCE_BREAK (§6.9). */
    private String checkAccounts(List<String> ids, DeriveConfig cfg) {
        if (ids == null || ids.isEmpty()) {
            return "externalIds is required";
        }
        for (String id : ids) {
            if (id == null || !cfg.registry().accounts().containsKey(id)) {
                return "externalIds names an unknown account '" + id + "'";
            }
        }
        return null;
    }

    /**
     * A DISMISS's subjects per review kind (V2-PROPOSAL.md §9.9.F, as amended): a fact id for most
     * kinds, an account ref for {@code BALANCE_BREAK}, the grouping stem for
     * {@code SUSPECTED_RECURRING} and a declared commitment id for {@code DORMANT_COMMITMENT}/
     * {@code COMMITMENT_ARREARS}. A stem resolves through the detector's own
     * {@link Commitments#candidateId} to a detected row; the commitment kinds check the same
     * declared set the fold checks. A writer that bypasses the hub surfaces a bad subject as
     * {@code INEFFECTIVE_DECISION}; here it is a 422 the UI can read.
     */
    private String dismissSubjects(String item, List<String> ids, DeriveConfig cfg) {
        if (ids == null || ids.isEmpty()) {
            return "externalIds is required";
        }
        for (String id : ids) {
            if (id == null || id.isBlank()) {
                return "externalIds must not contain a blank subject";
            }
        }
        if (ReviewItem.BALANCE_BREAK.equals(item)) {
            return checkAccounts(ids, cfg);
        }
        if (ReviewItem.SUSPECTED_RECURRING.equals(item)) {
            for (String stem : ids) {
                HubQueries.CommitmentRef ref = reads.commitmentRef(Commitments.candidateId(stem));
                if (ref == null || ref.declared()) {
                    return "externalIds names an unknown candidate '" + stem + "'";
                }
            }
            return null;
        }
        if (ReviewItem.DORMANT_COMMITMENT.equals(item) || ReviewItem.COMMITMENT_ARREARS.equals(item)) {
            for (String id : ids) {
                HubQueries.CommitmentRef ref = reads.commitmentRef(id);
                if (ref == null || !ref.declared()) {
                    return "externalIds names an unknown commitment '" + id + "'";
                }
            }
            return null;
        }
        return checkIds(ids, "externalIds");
    }

    private static String checkItem(String item) {
        return item != null && Set.of("POTENTIAL_DUP", "RESTATEMENT", "AMBIGUOUS_TRANSFER",
            "AMBIGUOUS_SETTLEMENT", "UNMATCHED_LEG", "STALE_PENDING", "INEFFECTIVE_DECISION",
            "BALANCE_BREAK", ReviewItem.SUSPECTED_RECURRING, ReviewItem.DORMANT_COMMITMENT,
            ReviewItem.COMMITMENT_ARREARS).contains(item)
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
        events.close();
        reads.close();
        indexer.close();
        lock.close();
    }
}
