package trex.ingest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trex.category.Categorized;
import trex.category.Categorizer;
import trex.category.CategoryRules;
import trex.category.Merchant;
import trex.category.Transfers;
import trex.core.Candidate;
import trex.core.CandidateResult;
import trex.core.CanonicalEvent;
import trex.core.TypeHint;
import trex.journal.Yaml;
import trex.sequencer.config.Config;
import trex.sequencer.ingest.CandidateInput;
import trex.sequencer.ingest.ReconcileReport;
import trex.sequencer.ingest.Sequencer;
import trex.sequencer.journal.JsonlJournal;
import trex.sequencer.state.Fold;

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

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dry run over a whole pile of statements, of any mix of source types, through a <b>real</b>
 * sequencer in a temp journal: same identity, same dedup, same transfer matching, same
 * HELD/REVIEW rules as production. Then it categorises the result and prints the worklist.
 * Nothing durable is written and no server is involved.
 * <p>
 * This is the loop for taming categories: edit {@code categories.yaml}, run again, watch
 * UNCATEGORIZED shrink. Rules are only ever evaluated at ingest (§3.2), so it is also the
 * honest way to see what the transfer allowlist will do <em>before</em> the first real ingest.
 * <pre>
 *   mvn -pl trex-ingest test -Dtest=DryRunTest -Dtrex.dry.manifest=/path/to/statements.yaml
 * </pre>
 * The manifest lists what to feed it, in order:
 * <pre>
 *   config: deploy/config          # optional: accounts.yaml, transfers.yaml, categories.yaml
 *   batchRows: 300                 # optional: day-atomic call size, as the CLI's --batch-rows
 *   files:
 *     - {sourceType: ing-csv,  account: ing-salary,      file: ~/statements/Salary.csv}
 *     - {sourceType: bw-csv,   account: bw-credit-card,  file: ~/statements/BW.csv}
 *     - {sourceType: cba-pdf,  account: cba-netsaver,    file: ~/statements/NetSaver.pdf}
 * </pre>
 * Without a manifest it runs the repo's own sample statements, so it always has something to do.
 */
class DryRunTest {

    private static final int WORKLIST = 25;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-01T00:00:00Z"), ZoneOffset.UTC);

    record Manifest(String config, Integer batchRows, List<Source> files) {}

    record Source(String sourceType, String account, String file) {}

    @TempDir
    Path journalDir;

    @Test
    void ingestEverythingAndReportWhatWouldHappen() throws Exception {
        Manifest manifest = manifest();
        Path configDir = path(manifest.config() == null ? "../deploy/config" : manifest.config());
        int batchRows = manifest.batchRows() == null ? Integer.MAX_VALUE : manifest.batchRows();
        Config config = Config.load(configDir);
        Categorizer categorizer = trex.journal.RuleFiles.load(
            configDir.resolve("categories.yaml"), configDir.resolve("pins.yaml"));

        StringBuilder out = new StringBuilder();
        line(out, "=== dry run: %d file(s), config %s ===", manifest.files().size(), configDir.toAbsolutePath());

        Path journalPath = journalDir.resolve("journal.jsonl");
        List<String> invalid = new ArrayList<>();
        try (JsonlJournal journal = new JsonlJournal(journalPath)) {
            Sequencer sequencer = new Sequencer(journal, Fold.fold(journal), config.registry(), config.rules(), CLOCK);

            for (Source source : manifest.files()) {
                Path file = path(source.file());
                if (!Files.exists(file)) {
                    line(out, "%-46s MISSING", file.getFileName());
                    invalid.add(source.file());
                    continue;
                }
                Parsed parsed = IngestRunner.parser(source.sourceType()).apply(file, source.account());
                if (!parsed.valid()) {
                    line(out, "%-46s INVALID: %d bad row(s), nothing sent", file.getFileName(), parsed.badRows().size());
                    parsed.badRows().stream().limit(5).forEach(b -> line(out, "      %s", b));
                    invalid.add(source.file());
                    continue;
                }
                report(out, file, source, parsed, submit(sequencer, parsed.candidates(), batchRows));
            }

            summarise(out, sequencer, categorizer);
        }
        System.out.print(out);
        assertTrue(invalid.isEmpty(), "files that could not be ingested: " + invalid);
    }

    /** Day-atomic calls, exactly as the CLI batches them (§4), through the real submit path. */
    private static List<CandidateResult> submit(Sequencer sequencer, List<Candidate> candidates, int batchRows) {
        List<CandidateResult> results = new ArrayList<>();
        for (List<Candidate> call : DayBatcher.split(candidates, batchRows)) {
            results.addAll(sequencer.submitCandidates(false, call.stream().map(CandidateInput::bound).toList()).results());
        }
        return results;
    }

    private static void report(StringBuilder out, Path file, Source source, Parsed parsed, List<CandidateResult> results) {
        Map<String, Integer> kinds = new TreeMap<>();
        for (CandidateResult r : results) {
            kinds.merge(switch (r) {
                case CandidateResult.Resolved _ -> "resolved";
                case CandidateResult.DroppedDuplicate _ -> "duplicate";
                case CandidateResult.Held _ -> "held";
                case CandidateResult.Flagged _ -> "flagged";
                case CandidateResult.Rejected _ -> "REJECTED";
            }, 1, Integer::sum);
        }
        String skipped = parsed.skipped().isEmpty() ? "" : "  skipped " + parsed.skipped().size() + " pending";
        line(out, "%-46s %-9s %-24s %4d rows  %s%s", file.getFileName(), source.sourceType(), source.account(),
            parsed.candidates().size(), kinds, skipped);
    }

    private static void summarise(StringBuilder out, Sequencer sequencer, Categorizer categorizer) {
        var view = sequencer.view();
        List<CanonicalEvent> latest = view.latestLines();

        line(out, "");
        line(out, "journal lines   %d", view.highWaterN());
        line(out, "transactions    %d", latest.size());
        line(out, "transfers       %d matched", latest.stream().filter(e -> e.typeHint() == TypeHint.TRANSFER).count());
        line(out, "held / review   %d / %d  (the resolver's worklist)", view.held().size(), view.review().size());

        ReconcileReport reconcile = sequencer.reconcile();
        line(out, "reconcile       ok=%s", reconcile.ok());
        reconcile.accounts().forEach(a -> line(out, "  %-26s reconcilable=%-5s balances=%-5s  closing %s",
            a.accountRef(), a.reconcilable(), a.balances(), money(a.closing())));

        // Categories over the same snapshot a journal consumer would read (§5.6).
        Set<String> transfers = Transfers.ids(latest);
        Map<String, int[]> counts = new TreeMap<>();
        Map<String, long[]> totals = new TreeMap<>();
        Map<String, Integer> worklist = new LinkedHashMap<>();
        Map<String, Long> worklistSpend = new LinkedHashMap<>();
        for (CanonicalEvent e : latest) {
            Categorized c = categorizer.categorize(e, transfers);
            counts.computeIfAbsent(c.category(), _ -> new int[1])[0]++;
            totals.computeIfAbsent(c.category(), _ -> new long[1])[0] += e.amount();
            if (c.origin() == Categorized.Origin.NONE) {
                String merchant = Merchant.stem(e.rawDescription());
                worklist.merge(merchant, 1, Integer::sum);
                worklistSpend.merge(merchant, e.amount(), Long::sum);
            }
        }
        line(out, "");
        line(out, "%-20s %6s %14s", "category", "rows", "total");
        counts.forEach((category, n) -> line(out, "%-20s %6d %14s", category, n[0], money(totals.get(category)[0])));

        if (!worklist.isEmpty()) {
            line(out, "");
            line(out, "uncategorised worklist — write rules for these first:");
            line(out, "%6s %14s  %s", "rows", "total", "merchant");
            worklist.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(WORKLIST)
                .forEach(e -> line(out, "%6d %14s  %s", e.getValue(), money(worklistSpend.get(e.getKey())), e.getKey()));
            if (worklist.size() > WORKLIST) {
                line(out, "%6s %14s  ... %d more distinct merchants", "", "", worklist.size() - WORKLIST);
            }
        }
    }

    private static Manifest manifest() {
        String path = System.getProperty("trex.dry.manifest", System.getenv("TREX_DRY_MANIFEST"));
        if (path == null || path.isBlank()) {
            // The repo's own samples, so the test always has something to run.
            return new Manifest("../deploy/config", null, List.of(
                new Source("ing-csv", "ing-savings", "../deploy/dev/samples/ing-savings.csv"),
                new Source("ing-csv", "ing-orange", "../deploy/dev/samples/ing-orange.csv")));
        }
        return Yaml.read(path(path), Manifest.class);
    }

    /**
     * Manifest paths may start with {@code ~}, be absolute, or be relative to anywhere between
     * the working directory and the repo root — Maven runs tests from the module directory,
     * while a manifest is naturally written from the root.
     */
    private static Path path(String value) {
        if (value.startsWith("~/")) {
            return Path.of(System.getProperty("user.home") + value.substring(1));
        }
        Path given = Path.of(value);
        if (given.isAbsolute() || Files.exists(given)) {
            return given;
        }
        for (Path base = Path.of("").toAbsolutePath(); base != null; base = base.getParent()) {
            Path candidate = base.resolve(value);
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        return given;   // reported as missing by the caller, with the path the manifest gave
    }

    private static String money(long cents) {
        return "%,d.%02d".formatted(cents / 100, Math.abs(cents % 100));
    }

    private static void line(StringBuilder out, String format, Object... args) {
        out.append(format.formatted(args)).append('\n');
    }
}
