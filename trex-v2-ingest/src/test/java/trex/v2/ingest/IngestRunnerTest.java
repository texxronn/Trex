package trex.v2.ingest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trex.v2.log.EvidenceStore;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The ingest run (V2-PROPOSAL.md §6.5): evidence stored, whole-file validation, exit codes. */
class IngestRunnerTest {

    private static final String SAMPLE = """
        Date,Description,Credit,Debit,Balance
        01/07/2026,"COFFEE CART, SYDNEY",,-4.50,1745.50
        02/07/2026,Salary Deposit - Receipt No 998877,2500.00,,4061.00
        """;

    private static java.io.PrintStream quiet() {
        return new java.io.PrintStream(OutputStream.nullOutputStream());
    }

    @Test
    void storesEvidenceAndPosts(@TempDir Path dir) throws Exception {
        try (FakeSequencer sequencer = new FakeSequencer("Appended")) {
            EvidenceStore evidence = new EvidenceStore(dir.resolve("evidence"));
            int exit = IngestRunner.run(Adapters.byType("ing-csv"), SAMPLE.getBytes(StandardCharsets.UTF_8),
                "ing-savings.csv", "ing-savings", evidence, new IngestClient(sequencer.url()), quiet());
            assertEquals(IngestRunner.OK, exit);
            assertTrue(sequencer.calls > 0);
            assertEquals(2, sequencer.factsSeen);
            assertEquals(1, evidence.list().size());
        }
    }

    /**
     * The complete event carries the writer's Flagged count: it is the one place an ingest says a
     * re-observation happened (V2-REVIEW-FIXES-PLAN.md §4 — it once read "flagged 0" over 58 merges).
     */
    @Test
    void flaggedRowsAreCountedOnTheCompleteEvent(@TempDir Path dir) throws Exception {
        try (FakeSequencer sequencer = new FakeSequencer("Flagged")) {
            int exit = IngestRunner.run(Adapters.byType("ing-csv"), SAMPLE.getBytes(StandardCharsets.UTF_8),
                "f.csv", "ing-savings", new EvidenceStore(dir.resolve("evidence")),
                new IngestClient(sequencer.url()), quiet());
            assertEquals(IngestRunner.OK, exit);
            assertTrue(sequencer.factsSeen > 0, "the sample must post rows");
            java.util.Map<String, Object> complete = sequencer.ingestEvents.stream()
                .filter(e -> "complete".equals(e.get("phase"))).findFirst().orElseThrow();
            assertEquals(sequencer.factsSeen, ((Number) complete.get("flagged")).intValue(), complete.toString());
            assertEquals(0, ((Number) complete.get("appended")).intValue());
        }
    }

    @Test
    void aBadRowSendsNothing(@TempDir Path dir) throws Exception {
        String bad = "Date,Description,Credit,Debit,Balance\n31/02/2026,Bad,,-2.00,0.00\n";
        try (FakeSequencer sequencer = new FakeSequencer("Appended")) {
            int exit = IngestRunner.run(Adapters.byType("ing-csv"), bad.getBytes(StandardCharsets.UTF_8),
                "bad.csv", "ing-savings", new EvidenceStore(dir.resolve("e")),
                new IngestClient(sequencer.url()), quiet());
            assertEquals(IngestRunner.BAD_ROWS, exit);
            assertEquals(0, sequencer.calls);
            assertEquals(2, sequencer.ingests, "start + complete, no facts");
        }
    }

    @Test
    void aRejectedBatchIsExitThree(@TempDir Path dir) throws Exception {
        try (FakeSequencer sequencer = new FakeSequencer("Rejected")) {
            int exit = IngestRunner.run(Adapters.byType("ing-csv"), SAMPLE.getBytes(StandardCharsets.UTF_8),
                "f.csv", "ing-savings", new EvidenceStore(dir.resolve("e")),
                new IngestClient(sequencer.url()), quiet());
            assertEquals(IngestRunner.REJECTED, exit);
        }
    }

    @Test
    void anUnreachableSequencerIsExitTwo(@TempDir Path dir) {
        int exit = IngestRunner.run(Adapters.byType("ing-csv"), SAMPLE.getBytes(StandardCharsets.UTF_8),
            "f.csv", "ing-savings", new EvidenceStore(dir.resolve("e")),
            new IngestClient("http://127.0.0.1:1"), quiet());
        assertEquals(IngestRunner.TRANSPORT, exit);
    }
}
