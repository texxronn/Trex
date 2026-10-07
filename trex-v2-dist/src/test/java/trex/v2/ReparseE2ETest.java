package trex.v2;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trex.v2.core.Fact;
import trex.v2.ingest.Adapters;
import trex.v2.ingest.FactDraft;
import trex.v2.ingest.IngestClient;
import trex.v2.ingest.IngestRunner;
import trex.v2.ingest.Parsed;
import trex.v2.ingest.Reparse;
import trex.v2.index.Indexer;
import trex.v2.log.ConfigLoader;
import trex.v2.log.EvidenceStore;
import trex.v2.log.FramedReader;
import trex.v2.sequencer.SequencerService;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §15.9 end to end: ingest evidence to facts, then a parser fix re-parses the stored evidence and the
 * apply path writes the new fact plus the SUPERSEDE/RETIRE decisions through the real sequencer, and
 * the index resolves the chain.
 */
class ReparseE2ETest {

    private static final String ORIGINAL = """
        Date,Description,Credit,Debit,Balance
        01/07/2026,"COLES 1234",,-10.00,990.00
        02/07/2026,"OTHER SHOP",,-20.00,970.00
        """;

    @Test
    void aParserFixFlowsThroughAsAReviewedSupersede(@TempDir Path dir) throws Exception {
        Path configDir = dir.resolve("config");
        Files.createDirectories(configDir);
        config(configDir);
        Path journal = dir.resolve("trex.jsonl");
        EvidenceStore evidence = new EvidenceStore(dir.resolve("evidence"));

        try (SequencerService sequencer = SequencerService.start(journal, configDir, "127.0.0.1", 0, Clock.systemUTC())) {
            IngestClient client = new IngestClient("http://127.0.0.1:" + sequencer.port());
            String evidenceId = evidence.put(ORIGINAL.getBytes(StandardCharsets.UTF_8));
            int exit = IngestRunner.run(Adapters.byType("ing-csv"), ORIGINAL.getBytes(StandardCharsets.UTF_8),
                "f.csv", "ing-savings", evidence, client, quiet());
            assertEquals(IngestRunner.OK, exit);

            List<Fact> current = readFacts(journal);
            assertEquals(2, current.size());
            String oldId = current.get(0).externalId();
            String droppedId = current.get(1).externalId();

            // The parser fix: one row's text is extracted better, and one row is no longer read.
            String fixed = """
                Date,Description,Credit,Debit,Balance
                01/07/2026,"COLES 1234 SYDNEY",,-10.00,990.00
                """;
            Parsed reparsed = Adapters.byType("ing-csv").parse(fixed.getBytes(StandardCharsets.UTF_8),
                "f.csv", "ing-savings");
            List<Reparse.Proposal> proposals = Reparse.diff(current, evidenceId, reparsed.candidates());
            Reparse.Proposal shifted = proposals.stream().filter(p -> p.kind() == Reparse.Kind.SHIFTED)
                .findFirst().orElseThrow();
            assertEquals(1, proposals.stream().filter(p -> p.kind() == Reparse.Kind.MISSING).count());
            String newId = shifted.externalId();

            Reparse.ApplyResult applied = Reparse.apply(client, "ing-csv/2", evidenceId, proposals);
            assertEquals(1, applied.facts());
            assertEquals(2, applied.decisions());

            // The chain now resolves: the index sees the new row, and neither the superseded nor the
            // retired row is current.
            Path index = dir.resolve("trex.sqlite");
            try (Indexer indexer = Indexer.open(index, ConfigLoader.load(configDir).config())) {
                indexer.apply(journal, Instant.parse("2026-10-01T00:00:00Z"));
                List<String> currentIds = indexer.currentFacts().stream().map(Fact::externalId).toList();
                assertTrue(currentIds.contains(newId), currentIds.toString());
                assertFalse(currentIds.contains(oldId), "the superseded row is not current");
                assertFalse(currentIds.contains(droppedId), "the retired row is not current");
            }
        }
    }

    private static java.io.PrintStream quiet() {
        return new java.io.PrintStream(OutputStream.nullOutputStream());
    }

    private static List<Fact> readFacts(Path journal) {
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

    private static void config(Path configDir) throws Exception {
        Files.writeString(configDir.resolve("accounts.yaml"), """
            accounts:
              - ref: "ing-savings"
                currency: "AUD"
                balanceSource: statement
            """);
        Files.writeString(configDir.resolve("users.yaml"), """
            users:
              - id: "ron"
                name: "Ron"
                active: true
            """);
        Files.writeString(configDir.resolve("categories.yaml"), """
            categories: [GROCERIES]
            rules:
              - category: GROCERIES
                when:
                  match: "COLES"
            """);
        Files.writeString(configDir.resolve("transfers.yaml"), """
            windowDays: 4
            allowlist:
              - 'Transfer'
            """);
    }
}
