package trex.v2.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import trex.v2.core.Fact;
import trex.v2.ingest.Adapters;
import trex.v2.ingest.FactDraft;
import trex.v2.ingest.IngestClient;
import trex.v2.ingest.IngestRunner;
import trex.v2.ingest.Parsed;
import trex.v2.ingest.Reparse;
import trex.v2.ingest.SourceAdapter;
import trex.v2.log.EvidenceStore;
import trex.v2.log.FramedReader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    @Option(names = "--sequencer-url", description = "The running sequencer base URL.")
    String sequencerUrl;

    @Option(names = "--evidence", required = true, description = "The evidence store directory.")
    Path evidenceDir;

    @Option(names = "--journal", description = "The journal file, for --reparse.")
    Path journal;

    @Option(names = "--reparse", description = "An evidence id to replay, e.g. sha256:... .")
    String reparseEvidence;

    @Option(names = "--apply", description = "With --reparse, post the new facts and SUPERSEDE/RETIRE decisions.")
    boolean apply;

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
        if (reparseEvidence != null) {
            return reparse(adapter);
        }
        if (account == null || file == null || sequencerUrl == null) {
            System.err.println("--account, --sequencer-url and FILE are required to ingest");
            return IngestRunner.USAGE;
        }
        EvidenceStore evidence = new EvidenceStore(evidenceDir);
        return IngestRunner.run(adapter, Files.readAllBytes(file), file.getFileName().toString(),
            account, evidence, new IngestClient(sequencerUrl), System.out);
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
        List<Reparse.Proposal> proposals = Reparse.diff(readJournalFacts(journal), reparseEvidence,
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
        IngestClient client = new IngestClient(sequencerUrl);
        List<FactDraft> toPost = proposals.stream()
            .filter(p -> p.kind() == Reparse.Kind.NEW || p.kind() == Reparse.Kind.SHIFTED)
            .map(p -> p.candidate().withEvidence(reparseEvidence, adapter.parser(), java.time.Instant.now()))
            .toList();
        if (!toPost.isEmpty()) {
            try {
                client.postFacts(toPost, true);
            } catch (IngestClient.IngestException e) {
                System.err.println("transport failure: " + e.getMessage());
                return IngestRunner.TRANSPORT;
            }
        }
        List<Map<String, Object>> decisions = new ArrayList<>();
        for (Reparse.Proposal p : proposals) {
            if (p.kind() == Reparse.Kind.SHIFTED) {
                decisions.add(decision("SUPERSEDE", Map.of("fromId", p.previousId(), "toId", p.externalId(),
                    "reason", "re-parse " + adapter.parser())));
            } else if (p.kind() == Reparse.Kind.MISSING) {
                decisions.add(decision("RETIRE", Map.of("externalId", p.externalId(),
                    "reason", "re-parse " + adapter.parser())));
            }
        }
        if (!decisions.isEmpty()) {
            try {
                client.postDecisions(decisions);
            } catch (IngestClient.IngestException e) {
                System.err.println("transport failure: " + e.getMessage());
                return IngestRunner.TRANSPORT;
            }
        }
        System.out.printf("applied: %d fact(s), %d decision(s)%n", toPost.size(), decisions.size());
        return 0;
    }

    private static Map<String, Object> decision(String action, Map<String, Object> payload) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("action", action);
        out.put("actor", "system");
        out.putAll(payload);
        return out;
    }

    private static List<Fact> readJournalFacts(Path journal) {
        List<Fact> facts = new ArrayList<>();
        try (FramedReader reader = new FramedReader(journal, 0)) {
            FramedReader.Framed framed;
            while ((framed = reader.next()) != null) {
                if (framed.line() instanceof Fact fact) {
                    facts.add(fact);
                }
            }
        }
        return facts;
    }
}
