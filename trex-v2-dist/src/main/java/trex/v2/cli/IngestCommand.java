package trex.v2.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import trex.v2.core.Decision;
import trex.v2.core.Fact;
import trex.v2.ingest.AccountGuard;
import trex.v2.ingest.Adapters;
import trex.v2.ingest.BadRow;
import trex.v2.ingest.FactDraft;
import trex.v2.ingest.GuardReport;
import trex.v2.ingest.IngestClient;
import trex.v2.ingest.IngestRunner;
import trex.v2.ingest.Parsed;
import trex.v2.ingest.Reparse;
import trex.v2.ingest.SourceAdapter;
import trex.v2.log.EvidenceStore;
import trex.v2.log.FramedReader;
import trex.v2.log.Json;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.concurrent.Callable;

/**
 * {@code trex ingest}: parse a source into evidence and facts (V2-PROPOSAL.md §12). Whole-file
 * validation, day-atomic batching, gzip, and v1's exit codes 0/1/2/3/64. {@code --reparse} replays
 * stored evidence and diffs it against the facts it produced before.
 */
@Command(name = "ingest", mixinStandardHelpOptions = true,
    description = "Parse a source into evidence and facts; whole-file validation, day batching.")
public final class IngestCommand implements Callable<Integer> {

    @Option(names = "--types", description = "List the known source types and exit.")
    boolean types;

    @Option(names = "--source-type", description = "The adapter, e.g. ing-csv or bw-csv.")
    String sourceType;

    @Option(names = "--account", description = "The registry accountRef these rows belong to.")
    String account;

    @Option(names = "--source", defaultValue = "ING_0001",
        description = "The 8-char writing-process id stamped on the facts (V2-PROPOSAL.md §6).")
    String source;

    @Option(names = "--source-archive",
        description = "Archive the source bytes here, gzipped under a dated name (§12.6).")
    Path sourceArchive;

    @Option(names = "--source-name",
        description = "The original file name for the archive; defaults to the FILE's name.")
    String sourceName;

    @Option(names = "--sequencer-url", description = "The running sequencer base URL.")
    String sequencerUrl;

    @Option(names = "--evidence", description = "The evidence store directory (required unless --types).")
    Path evidenceDir;

    @Option(names = "--journal", description = "The journal file: for --reparse, the account guard and --identify.")
    Path journal;

    @Option(names = "--reparse", description = "An evidence id to replay, e.g. sha256:... .")
    String reparseEvidence;

    @Option(names = "--apply", description = "With --reparse, post the new facts and SUPERSEDE/RETIRE decisions.")
    boolean apply;

    @Option(names = "--account-guard", defaultValue = "on",
        description = "on | off | check: refuse a file that fits another account (default on); check "
            + "prints the verdict and writes nothing.")
    String accountGuard;

    @Option(names = "--identify",
        description = "Print which account the FILE fits, from the journal, and write nothing.")
    boolean identify;

    @Option(names = "--format", defaultValue = "text", description = "With --identify: text | json.")
    String format;

    @Parameters(index = "0", arity = "0..1", paramLabel = "FILE", description = "The statement file.")
    Path file;

    @Override
    public Integer call() throws Exception {
        if (types) {
            System.out.println(String.join("\n", Adapters.types()));
            return 0;
        }
        if (sourceType == null) {
            System.err.println("--source-type is required (known: " + Adapters.types() + ")");
            return IngestRunner.USAGE;
        }
        SourceAdapter adapter = Adapters.byType(sourceType);
        if (identify) {
            return identify(adapter);
        }
        if ("check".equals(accountGuard)) {
            return check(adapter);
        }
        if (!"on".equals(accountGuard) && !"off".equals(accountGuard)) {
            System.err.println("--account-guard must be on, off or check");
            return IngestRunner.USAGE;
        }
        if (evidenceDir == null) {
            System.err.println("--evidence is required to ingest");
            return IngestRunner.USAGE;
        }
        if (reparseEvidence != null) {
            return reparse(adapter);
        }
        if (account == null || file == null || sequencerUrl == null) {
            System.err.println("--account, --sequencer-url and FILE are required to ingest");
            return IngestRunner.USAGE;
        }
        IngestRunner.Guard guard = null;
        if ("off".equals(accountGuard)) {
            guard = IngestRunner.Guard.OFF;
        } else if (journal != null) {
            SortedMap<String, List<Fact>> current = currentFacts();
            if (current == null) {
                return IngestRunner.TRANSPORT;
            }
            guard = new IngestRunner.Guard(current);
        } else {
            System.err.println("account guard skipped: no --journal");
        }
        EvidenceStore evidence = new EvidenceStore(evidenceDir);
        return IngestRunner.run(adapter, Files.readAllBytes(file), file.getFileName().toString(),
            account, sourceArchive, sourceName == null ? file.getFileName().toString() : sourceName,
            evidence, new IngestClient(sequencerUrl, source), guard, System.out);
    }

    /** {@code --account-guard=check}: the verdict for one file against the journal; writes nothing. */
    private Integer check(SourceAdapter adapter) throws Exception {
        if (account == null || journal == null || file == null) {
            System.err.println("--account, --journal and FILE are required with --account-guard=check");
            return IngestRunner.USAGE;
        }
        Parsed parsed = parseOrReport(adapter, account);
        if (parsed == null) {
            return IngestRunner.BAD_ROWS;
        }
        SortedMap<String, List<Fact>> current = currentFacts();
        if (current == null) {
            return IngestRunner.TRANSPORT;
        }
        AccountGuard.Verdict verdict = AccountGuard.check(parsed.candidates(), account, current);
        GuardReport.verdict(file.getFileName().toString(), verdict, parsed.candidates(), current)
            .forEach(System.out::println);
        return verdict.refuses() ? IngestRunner.GUARD_REFUSED : 0;
    }

    /** {@code --identify}: which account's history the FILE continues; no account needed, nothing written. */
    private Integer identify(SourceAdapter adapter) throws Exception {
        if (journal == null || file == null) {
            System.err.println("--journal and FILE are required with --identify");
            return IngestRunner.USAGE;
        }
        // The guard compares content, never accountRef, so the placeholder never reaches a score.
        Parsed parsed = parseOrReport(adapter, "?");
        if (parsed == null) {
            return IngestRunner.BAD_ROWS;
        }
        SortedMap<String, List<Fact>> current = currentFacts();
        if (current == null) {
            return IngestRunner.TRANSPORT;
        }
        List<AccountGuard.Score> scores = AccountGuard.score(parsed.candidates(), current);
        String name = file.getFileName().toString();
        if ("json".equals(format)) {
            System.out.println(identifyJson(name, scores));
        } else {
            GuardReport.identify(name, scores, current, parsed.candidates()).forEach(System.out::println);
        }
        return 0;
    }

    private String identifyJson(String name, List<AccountGuard.Score> scores) throws Exception {
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (AccountGuard.Score s : scores) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("account", s.account());
            c.put("verdict", s.fits() ? "fits" : s.contradicts() ? "contradicts" : "none");
            c.put("overlapRows", s.overlapRows());
            c.put("overlapFrom", s.overlapFrom() == null ? null : s.overlapFrom().toString());
            c.put("overlapTo", s.overlapTo() == null ? null : s.overlapTo().toString());
            c.put("connects", s.connects());
            candidates.add(c);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("file", name);
        out.put("sourceType", sourceType);
        out.put("candidates", candidates);
        out.put("suggestion", AccountGuard.suggestion(scores).orElse(null));
        return Json.mapper().writeValueAsString(out);
    }

    /** Parse FILE; on bad rows print them as an ingest does and return null. */
    private Parsed parseOrReport(SourceAdapter adapter, String accountRef) throws Exception {
        String name = file.getFileName().toString();
        Parsed parsed = adapter.parse(Files.readAllBytes(file), name, accountRef);
        if (!parsed.clean()) {
            System.out.println("rejected: " + parsed.bad().size() + " bad row(s); nothing sent");
            for (BadRow bad : parsed.bad()) {
                System.out.printf("  %s:%d  %s '%s': %s%n", bad.file(), bad.line(), bad.field(), bad.value(),
                    bad.reason());
            }
            return null;
        }
        return parsed;
    }

    /**
     * The journal's current facts, or null (after one stderr line) when it cannot be read. An
     * unreadable journal is the environment, not the file: the caller exits {@code TRANSPORT}, which
     * the inbox sweep leaves in place for the next pass instead of filing the file as bad rows.
     */
    private SortedMap<String, List<Fact>> currentFacts() {
        List<Fact> facts = new ArrayList<>();
        List<Decision> decisions = new ArrayList<>();
        try {
            readJournal(journal, facts, decisions);
        } catch (RuntimeException e) {
            System.err.println("account guard: cannot read the journal: " + e.getMessage());
            return null;
        }
        return AccountGuard.current(facts, decisions);
    }

    private Integer reparse(SourceAdapter adapter) throws Exception {
        if (account == null || journal == null) {
            System.err.println("--account and --journal are required with --reparse");
            return IngestRunner.USAGE;
        }
        EvidenceStore evidence = new EvidenceStore(evidenceDir);
        if (!evidence.contains(reparseEvidence)) {
            System.err.println("no such evidence: " + reparseEvidence);
            return IngestRunner.BAD_ROWS;
        }
        Parsed parsed = adapter.parse(evidence.get(reparseEvidence), "evidence:" + reparseEvidence, account);
        if (!parsed.clean()) {
            System.err.println("re-parse produced " + parsed.bad().size() + " bad row(s); nothing applied");
            parsed.bad().forEach(b -> System.err.printf("  %s:%d %s%n", b.file(), b.line(), b.reason()));
            return IngestRunner.BAD_ROWS;
        }
        List<Fact> facts = new ArrayList<>();
        List<Decision> decisions = new ArrayList<>();
        readJournal(journal, facts, decisions);
        List<Reparse.Proposal> proposals = Reparse.diff(facts, Reparse.closedIds(decisions), reparseEvidence,
            parsed.candidates());
        long changed = proposals.stream().filter(p -> p.kind() != Reparse.Kind.MATCHED).count();
        System.out.printf("re-parse with %s: %d matched, %d changed%n", adapter.parser(),
            proposals.size() - changed, changed);
        proposals.stream().filter(p -> p.kind() != Reparse.Kind.MATCHED)
            .forEach(p -> System.out.printf("  %-8s %s  %s%n", p.kind(), p.externalId(), p.detail()));
        if (!apply || changed == 0) {
            return 0;
        }
        return applyReparse(adapter, proposals);
    }

    private int applyReparse(SourceAdapter adapter, List<Reparse.Proposal> proposals) {
        try {
            Reparse.ApplyResult result = Reparse.apply(new IngestClient(sequencerUrl), adapter.parser(),
                reparseEvidence, proposals);
            System.out.printf("applied: %d fact(s), %d decision(s)%n", result.facts(), result.decisions());
            return 0;
        } catch (IngestClient.IngestException e) {
            System.err.println("transport failure: " + e.getMessage());
            return IngestRunner.TRANSPORT;
        }
    }

    private static void readJournal(Path journal, List<Fact> facts, List<Decision> decisions) {
        try (FramedReader reader = new FramedReader(journal, 0)) {
            FramedReader.Framed framed;
            while ((framed = reader.next()) != null) {
                if (framed.line() instanceof Fact fact) {
                    facts.add(fact);
                } else if (framed.line() instanceof Decision decision) {
                    decisions.add(decision);
                }
            }
        }
    }
}
