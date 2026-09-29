package trex.v2;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trex.v2.core.Fact;
import trex.v2.core.config.Account;
import trex.v2.core.config.BalanceSource;
import trex.v2.core.derive.CategoryRow;
import trex.v2.core.derive.CurrentFact;
import trex.v2.core.derive.Derivation;
import trex.v2.core.derive.Reconciliation;
import trex.v2.core.derive.ReviewItem;
import trex.v2.ingest.Adapters;
import trex.v2.ingest.IngestClient;
import trex.v2.ingest.IngestRunner;
import trex.v2.index.IndexLock;
import trex.v2.index.Indexer;
import trex.v2.log.ConfigLoader;
import trex.v2.log.EvidenceStore;
import trex.v2.log.FramedReader;
import trex.v2.log.Yaml;
import trex.v2.core.workbook.Workbook;
import trex.v2.sequencer.SequencerService;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End to end over a whole pile of real statements (V2-IMPLEMENTATION-PLAN.md §3 P4 acceptance): the
 * production ingest path (evidence + {@code POST /facts} through a real sequencer) into a temp
 * journal, then the index and {@code derive()} over what actually landed. It is the v2 counterpart
 * of v1's {@code DryRunTest}, and the honest way to see what identity, dedup, transfer matching and
 * the tuned categories do to real history before anything durable exists.
 *
 * <p>It is tagged {@code fixture} and skipped with a reason when no statements are present, so
 * {@code mvn verify} stays green on a fresh clone. Sources resolve in this order:
 * <ol>
 *   <li>{@code -Dtrex.e2e.manifest=<file>} or {@code $TREX_E2E_MANIFEST}: a manifest in the shape of
 *       {@code deploy/dev/statements.local.yaml};</li>
 *   <li>{@code -Dtrex.statements.dir=<dir>} or {@code $TREX_STATEMENTS_DIR}, else
 *       {@code ~/Downloads/Statements/Statements_CSV}: a directory scanned by file name;</li>
 *   <li>{@code deploy/dev/statements.local.yaml} if present.</li>
 * </ol>
 * The statements themselves are private and never committed. Config is {@code deploy/config}.
 *
 * <pre>
 *   mvn -pl trex-v2-dist -am test -Dtest=StatementsE2ETest -Dgroups=fixture \
 *       -Dtrex.statements.dir=/path/to/Statements_CSV
 * </pre>
 */
@Tag("fixture")
class StatementsE2ETest {

    private static final Instant ASOF = Instant.parse("2026-10-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(ASOF, ZoneOffset.UTC);

    /** The known exports this repository has adapters for, by file name. Directories are scanned with this. */
    private static final Map<String, Source> KNOWN = knownSources();

    private static Map<String, Source> knownSources() {
        Map<String, Source> m = new LinkedHashMap<>();
        m.put("salary_account.csv", new Source("ing-csv", "ing-salary"));
        m.put("loan_offset.csv", new Source("ing-csv", "ing-loan-offset"));
        m.put("mortgage_simplifier.csv", new Source("ing-csv", "ing-mortgage-simplifier"));
        m.put("variable_rate.csv", new Source("ing-csv", "ing-variable-rate"));
        m.put("orange_everyday.csv", new Source("ing-csv", "ing-orange"));
        m.put("ing_credit_card.csv", new Source("ing-csv", "ing-credit-card"));
        m.put("bankwest_transactions_full.csv", new Source("bw-csv", "bw-credit-card"));
        m.put("cba_smartaccess.csv", new Source("cba-csv", "cba-smartaccess"));
        m.put("cba_netsaver_transactionsummary.pdf", new Source("cba-pdf", "cba-netsaver"));
        return m;
    }

    record Source(String sourceType, String account) {}

    record Entry(String sourceType, String account, String file) {}

    record Manifest(String config, Integer batchRows, List<Entry> files) {}

    record Input(Path file, String sourceType, String account) {}

    @TempDir
    Path dir;

    @Test
    void everyStatementLandsAndTheIndexRebuildsIdentically() throws Exception {
        List<Input> inputs = resolve();
        Assumptions.assumeTrue(!inputs.isEmpty(),
            "no statements at " + statementsDirOrNull() + " and no manifest; skipping");

        Path configDir = configDir();
        ConfigLoader.Loaded loaded = ConfigLoader.load(configDir);
        Path journal = dir.resolve("trex.jsonl");
        EvidenceStore evidence = new EvidenceStore(dir.resolve("evidence"));

        StringBuilder out = new StringBuilder();
        line(out, "=== trex v2 end to end: %d file(s), config %s ===", inputs.size(), configDir.toAbsolutePath());
        List<String> invalid = new ArrayList<>();
        Map<String, long[]> perFile = new TreeMap<>();

        try (SequencerService sequencer = SequencerService.start(journal, configDir, "127.0.0.1", 0, CLOCK)) {
            IngestClient client = new IngestClient("http://127.0.0.1:" + sequencer.port());
            for (Input input : inputs) {
                if (!Files.exists(input.file())) {
                    line(out, "  %-46s MISSING", input.file());
                    invalid.add(input.file().toString());
                    continue;
                }
                byte[] bytes = Files.readAllBytes(input.file());
                long before = countFacts(journal);
                int exit = IngestRunner.run(Adapters.byType(input.sourceType()), bytes,
                    input.file().getFileName().toString(), input.account(), evidence, client, quiet());
                long appended = countFacts(journal) - before;
                perFile.put(input.file().getFileName().toString(), new long[] { exit, appended });
                line(out, "  %-46s %-8s %s  +%d fact(s)", input.file().getFileName(),
                    input.sourceType(), exit == IngestRunner.OK ? "ok" : "EXIT " + exit, appended);
                if (exit != IngestRunner.OK) {
                    invalid.add(input.file().getFileName().toString() + " (exit " + exit + ")");
                }
            }

            // §6.5: re-delivering a file appends nothing.
            Input first = inputs.get(0);
            if (Files.exists(first.file())) {
                long before = countFacts(journal);
                IngestRunner.run(Adapters.byType(first.sourceType()), Files.readAllBytes(first.file()),
                    first.file().getFileName().toString(), first.account(), evidence, client, quiet());
                assertEquals(before, countFacts(journal),
                    "re-delivering " + first.file().getFileName() + " must append nothing");
                line(out, "  re-delivered %s: no new facts", first.file().getFileName());
            }
        }

        Path db = dir.resolve("trex.sqlite");
        try {
            try (IndexLock ignored = IndexLock.acquire(db);
                 Indexer indexer = Indexer.open(db, loaded.config())) {
                indexer.apply(journal, ASOF);
                Derivation d = indexer.deriveWith(loaded.config(), ASOF, Long.MAX_VALUE);
                report(out, d, loaded);
                String incremental = indexer.derivedFingerprint();
                indexer.rebuild(journal, ASOF);
                assertEquals(incremental, indexer.derivedFingerprint(),
                    "a full rebuild must equal the incremental pass on real history");
                line(out, "  rebuild fingerprint == incremental: %s", incremental);
            }
        } finally {
            System.out.print(out);
        }

        assertTrue(invalid.isEmpty(), "files that did not ingest cleanly: " + invalid);
        assertTrue(perFile.values().stream().mapToLong(v -> v[1]).sum() > 0, "no facts landed");
    }

    // ---- the report ---------------------------------------------------------------------------

    private static void report(StringBuilder out, Derivation d, ConfigLoader.Loaded loaded) {
        line(out, "-- derived state " + "-".repeat(60));
        line(out, "  facts(current): %d   transfers: %d   pending: %d   review: %d   units: %d",
            d.current().size(), d.transfers().size(), d.pending().size(), d.review().size(), d.units().size());

        Map<String, Long> byKind = d.review().stream()
            .collect(Collectors.groupingBy(ReviewItem::kind, TreeMap::new, Collectors.counting()));
        line(out, "  review by kind: %s", byKind);

        Map<String, Long> byCategory = d.categories().stream()
            .collect(Collectors.groupingBy(CategoryRow::category, TreeMap::new, Collectors.counting()));
        line(out, "  categories: %s", byCategory);
        line(out, "  category origins: %s", d.categories().stream()
            .collect(Collectors.groupingBy(CategoryRow::origin, TreeMap::new, Collectors.counting())));

        if (!d.transfers().isEmpty()) {
            line(out, "  transfers (first 10):");
            d.transfers().stream().limit(10)
                .forEach(t -> line(out, "    %s  %s -> %s  %s/%s", t.transferId(), t.fromLeg(), t.toLeg(),
                    t.confidence(), t.origin()));
        }

        Set<String> declared = loaded.registry().accounts().values().stream()
            .filter(a -> a.balanceSource() == BalanceSource.DECLARED)
            .map(Account::ref)
            .collect(Collectors.toCollection(TreeSet::new));
        Map<String, Reconciliation.AccountResult> rec =
            Reconciliation.reconcile(currentFacts(d), declared);
        line(out, "  reconciliation:");
        rec.values().forEach(r -> line(out, "    %-24s %-12s balances=%s opening=%s closing=%s gap=%s",
            r.accountRef(), r.status().name().toLowerCase(java.util.Locale.ROOT), r.balances(),
            r.opening(), r.closing(), r.gap()));

        long uncategorised = d.categories().stream()
            .filter(c -> "UNCATEGORIZED".equals(c.category())).count();
        line(out, "  uncategorised: %d of %d", uncategorised, d.categories().size());

        // The P2 loop (V2-PROPOSAL.md §10.4): what the rules do on real history, and where they stop.
        Workbook.Report wb = Workbook.of(loaded.config(), d);
        line(out, "-- coverage --");
        line(out, "  total=%d rule=%d pin=%d structural=%d uncategorised=%d",
            wb.coverage().total(), wb.coverage().categorized(), wb.coverage().pinned(),
            wb.coverage().structural(), wb.coverage().uncategorized());
        if (!wb.findings().isEmpty()) {
            line(out, "  rule lint:");
            wb.findings().forEach(f -> line(out, "    %-20s %-14s %s", f.kind(), f.subject(), f.detail()));
        }

        Map<String, String> raw = new java.util.HashMap<>();
        for (CurrentFact c : d.current()) {
            raw.put(c.externalId(), c.fact().rawDescription());
        }
        List<Workbook.Suggestion> uncat = wb.suggestions().stream()
            .filter(s -> s.source() == Workbook.SuggestionSource.UNCATEGORISED)
            .limit(Integer.getInteger("trex.tuning.limit", 50)).toList();
        long clusters = wb.suggestions().stream()
            .filter(s -> s.source() == Workbook.SuggestionSource.UNCATEGORISED).count();
        line(out, "  uncategorised clusters (top %d of %d by rows):", uncat.size(), clusters);
        for (Workbook.Suggestion s : uncat) {
            line(out, "    %-38s %4d rows  %12s  %s..%s  %s",
                s.stem(), s.occurrences(), money(s.total()), s.firstSeen(), s.lastSeen(), s.accounts());
            s.sampleIds().stream().limit(3).forEach(id -> line(out, "        %s", raw.getOrDefault(id, id)));
        }
        List<Workbook.Suggestion> promotions = wb.suggestions().stream()
            .filter(s -> s.source() == Workbook.SuggestionSource.PIN).toList();
        if (!promotions.isEmpty()) {
            line(out, "  pin promotions (>= %d pins -> a rule):", Workbook.PROMOTION_THRESHOLD);
            promotions.forEach(s -> line(out, "    %-38s %4d pins  -> %s",
                s.stem(), s.occurrences(), s.category()));
        }
    }

    private static String money(long cents) {
        return String.format(java.util.Locale.ROOT, "%s%d.%02d", cents < 0 ? "-" : "",
            Math.abs(cents) / 100, Math.abs(cents) % 100);
    }

    private static List<Fact> currentFacts(Derivation d) {
        List<Fact> facts = new ArrayList<>();
        for (CurrentFact c : d.current()) {
            facts.add(c.fact());
        }
        return facts;
    }

    // ---- source resolution --------------------------------------------------------------------

    private static List<Input> resolve() throws Exception {
        String manifest = setting("trex.e2e.manifest", "TREX_E2E_MANIFEST");
        if (manifest != null) {
            return fromManifest(Path.of(expand(manifest)));
        }
        Path dir = statementsDirOrNull();
        if (dir != null) {
            return scan(dir);
        }
        Path local = Path.of("..", "deploy", "dev", "statements.local.yaml");
        if (Files.exists(local)) {
            return fromManifest(local);
        }
        return List.of();
    }

    private static List<Input> fromManifest(Path manifest) throws Exception {
        Manifest m = Yaml.read(manifest, Manifest.class);
        List<Input> out = new ArrayList<>();
        if (m.files() != null) {
            for (Entry e : m.files()) {
                out.add(new Input(Path.of(expand(e.file())), e.sourceType(), e.account()));
            }
        }
        return out;
    }

    private static List<Input> scan(Path dir) throws Exception {
        List<Input> out = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            for (Path file : stream.sorted().toList()) {
                Source source = KNOWN.get(file.getFileName().toString().toLowerCase(java.util.Locale.ROOT));
                if (source != null) {
                    out.add(new Input(file, source.sourceType(), source.account()));
                }
            }
        }
        return out;
    }

    private static Path statementsDirOrNull() {
        String override = setting("trex.statements.dir", "TREX_STATEMENTS_DIR");
        Path p = override != null ? Path.of(expand(override))
            : Path.of(System.getProperty("user.home"), "Downloads", "Statements", "Statements_CSV");
        return Files.isDirectory(p) ? p : null;
    }

    private static String setting(String property, String env) {
        String v = System.getProperty(property);
        if (v == null) {
            v = System.getenv(env);
        }
        return v == null || v.isBlank() ? null : v;
    }

    /** A leading {@code ~} is the operator's home; a manifest is portable otherwise. */
    private static String expand(String path) {
        if (path.startsWith("~/") || path.equals("~")) {
            return System.getProperty("user.home") + path.substring(1);
        }
        return path;
    }

    // ---- helpers ------------------------------------------------------------------------------

    private static long countFacts(Path journal) {
        long n = 0;
        if (!Files.exists(journal)) {
            return 0;
        }
        try (FramedReader reader = new FramedReader(journal, 0)) {
            FramedReader.Framed framed;
            while ((framed = reader.next()) != null) {
                if (framed.line() instanceof Fact) {
                    n++;
                }
            }
        }
        return n;
    }

    private static Path configDir() {
        Path fromModule = Path.of("..", "deploy", "config");
        return Files.isDirectory(fromModule) ? fromModule : Path.of("deploy", "config");
    }

    private static PrintStream quiet() {
        return new PrintStream(OutputStream.nullOutputStream());
    }

    private static void line(StringBuilder out, String format, Object... args) {
        out.append(String.format(java.util.Locale.ROOT, format, args)).append('\n');
    }
}
