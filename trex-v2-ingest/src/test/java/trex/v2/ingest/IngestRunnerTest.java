package trex.v2.ingest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trex.v2.log.EvidenceStore;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    private static java.util.SortedMap<String, java.util.List<trex.v2.core.Fact>> currentWith(String account) {
        trex.v2.core.Fact row = new trex.v2.core.Fact(1, "id-1", account, java.time.LocalDate.of(2026, 7, 1),
            -450, 174550, "COFFEE CART, SYDNEY", null, 0, trex.v2.core.Observation.POSTED, "ing-csv",
            trex.v2.core.Provenance.BANK, null, "ing-csv/1", java.time.Instant.parse("2026-09-30T00:00:00Z"));
        return trex.v2.ingest.AccountGuard.current(java.util.List.of(row), java.util.List.of());
    }

    private static java.util.Map<String, Object> complete(FakeSequencer sequencer) {
        return sequencer.ingestEvents.stream().filter(e -> "complete".equals(e.get("phase"))).findFirst()
            .orElseThrow();
    }

    private static int runWith(FakeSequencer sequencer, Path dir, String account, IngestRunner.Guard guard) {
        return IngestRunner.run(Adapters.byType("ing-csv"), SAMPLE.getBytes(StandardCharsets.UTF_8), "f.csv",
            account, null, "f.csv", new EvidenceStore(dir.resolve("e")), new IngestClient(sequencer.url()),
            guard, quiet());
    }

    @Test
    void aRefusingGuardPostsOnlyTheMarkers(@TempDir Path dir) throws Exception {
        try (FakeSequencer sequencer = new FakeSequencer("Appended")) {
            int exit = runWith(sequencer, dir, "ing-salary",
                new IngestRunner.Guard(currentWith("ing-orange")));
            assertEquals(IngestRunner.GUARD_REFUSED, exit);
            assertEquals(0, sequencer.calls);
            assertEquals(2, sequencer.ingests, "start + complete, no facts");
            assertEquals("guard_refused", complete(sequencer).get("status"));
            assertEquals("elsewhere:ing-orange", complete(sequencer).get("guard"));
            assertEquals(0, ((Number) complete(sequencer).get("appended")).intValue());
        }
    }

    @Test
    void aPostingGuardIsNamedOnTheCompleteEvent(@TempDir Path dir) throws Exception {
        try (FakeSequencer sequencer = new FakeSequencer("Appended")) {
            int exit = runWith(sequencer, dir, "ing-savings",
                new IngestRunner.Guard(currentWith("ing-savings")));
            assertEquals(IngestRunner.OK, exit);
            assertEquals(2, sequencer.factsSeen);
            assertEquals("ok", complete(sequencer).get("status"));
            assertEquals("fits", complete(sequencer).get("guard"));
        }
    }

    @Test
    void aNewAccountPostsAndSaysSo(@TempDir Path dir) throws Exception {
        try (FakeSequencer sequencer = new FakeSequencer("Appended")) {
            java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
            int exit = IngestRunner.run(Adapters.byType("ing-csv"), SAMPLE.getBytes(StandardCharsets.UTF_8),
                "f.csv", "ing-new", null, "f.csv", new EvidenceStore(dir.resolve("e")),
                new IngestClient(sequencer.url()),
                new IngestRunner.Guard(new java.util.TreeMap<>()), new java.io.PrintStream(captured));
            assertEquals(IngestRunner.OK, exit);
            assertEquals("new", complete(sequencer).get("guard"));
            assertTrue(captured.toString().contains("account guard: new account"), captured.toString());
        }
    }

    @Test
    void guardOffIsRecordedAndNothingIsChecked(@TempDir Path dir) throws Exception {
        try (FakeSequencer sequencer = new FakeSequencer("Appended")) {
            assertEquals(IngestRunner.OK, runWith(sequencer, dir, "ing-salary", IngestRunner.Guard.OFF));
            assertEquals(2, sequencer.factsSeen);
            assertEquals("off", complete(sequencer).get("guard"));
        }
    }

    @Test
    void noGuardLeavesTheCompleteEventUnchanged(@TempDir Path dir) throws Exception {
        try (FakeSequencer sequencer = new FakeSequencer("Appended")) {
            assertEquals(IngestRunner.OK, runWith(sequencer, dir, "ing-salary", null));
            assertFalse(complete(sequencer).containsKey("guard"));
        }
    }
}
