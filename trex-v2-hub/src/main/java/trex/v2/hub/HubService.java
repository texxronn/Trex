package trex.v2.hub;

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
import trex.v2.core.config.User;
import trex.v2.core.derive.Derivation;
import trex.v2.core.derive.CategoryRow;
import trex.v2.core.derive.Opening;
import trex.v2.core.derive.Period;
import trex.v2.core.derive.Reconciliation;
import trex.v2.core.derive.ReviewItem;
import trex.v2.core.workbook.Workbook;
import trex.v2.core.Hashes;
import trex.v2.hub.api.AckJson;
import trex.v2.hub.api.AckRequest;
import trex.v2.hub.api.AccountsResponse;
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
        return new OpeningResponse(Opening.of(reads.currentFacts(), refresher.config().registry()));
    }

    @Override
    public ReconcileResponse reconcile() {
        DeriveConfig c = refresher.config();
        Set<String> declared = c.registry().accounts().values().stream()
            .filter(a -> a.balanceSource() == BalanceSource.DECLARED)
            .map(Account::ref)
            .collect(Collectors.toCollection(TreeSet::new));
        List<Fact> noops = reads.currentNoops();
        Map<String, Fact> noopById = new HashMap<>();
        for (Fact f : noops) {
            noopById.put(f.externalId(), f);
        }
        Map<String, Reconciliation.AccountResult> results =
            Reconciliation.reconcile(reads.currentFacts(), noops, declared);
        List<ReconcileResponse.AccountJson> accounts = results.values().stream()
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
        LedgerPage page = reads.ledger(new BlotterQuery(null, null, null, null, range.from(), range.to(),
            null, null, null, false, "date", "asc", MAX_WALK_ROWS, 0));
        return Eyeball.walk(period, user, at, bucket, facts, page.rows(), reads.review(null), reads.pending(),
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
            c.configRevision(), DeriveConfig.DERIVE_VERSION, DeriveConfig.HASH_VERSION, stateHash, null);
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

    /** The ingest history (§12.6). */
    @Override
    public trex.v2.hub.api.IngestsResponse ingests() {
        return new trex.v2.hub.api.IngestsResponse(reads.ingests(50));
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
            accounts.add(new AccountsResponse.AccountCoverage(account.ref(), account.currency(),
                account.balanceSource().wire(), total == null ? null : total.first(),
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
                // BALANCE_BREAK is subject on an account ref, not a fact id (§6.9).
                yield e != null ? e : (ReviewItem.BALANCE_BREAK.equals(d.item())
                    ? checkAccounts(d.externalIds(), cfg)
                    : checkIds(d.externalIds(), "externalIds"));
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

    private static String checkItem(String item) {
        return item != null && Set.of("POTENTIAL_DUP", "RESTATEMENT", "AMBIGUOUS_TRANSFER",
            "AMBIGUOUS_SETTLEMENT", "UNMATCHED_LEG", "STALE_PENDING", "INEFFECTIVE_DECISION",
            "BALANCE_BREAK").contains(item)
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
