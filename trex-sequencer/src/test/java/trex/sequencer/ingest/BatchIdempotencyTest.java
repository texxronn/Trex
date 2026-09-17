package trex.sequencer.ingest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trex.core.BatchStatus;
import trex.core.Candidate;
import trex.core.CandidateResult.DroppedDuplicate;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static trex.sequencer.ingest.Harness.c;

/** SPEC §7 test 4. */
class BatchIdempotencyTest {

    @TempDir
    Path dir;

    @Test
    void resubmittingABatchDropsEverythingAndAppendsNothing() throws Exception {
        Candidate[] batchA = {
            c("row-2", "ing-savings", "2026-06-01", -1250, "Woolworths", 98750, null),
            c("row-3", "ing-savings", "2026-06-01", -1250, "Woolworths", 97500, null),
            c("row-4", "ing-savings", "2026-06-02", -50000, "Fast Transfer to CBA", 47500, null),
            c("row-5", "ing-savings", "2026-06-03", 250000, "Salary Receipt 42", 297500, "42")
        };
        Path journal = dir.resolve("j.jsonl");
        try (Harness h = new Harness(journal)) {
            assertEquals(BatchStatus.COMMITTED, h.submit(batchA).batchStatus());
            long size = Files.size(journal);
            int lines = h.lines().size();

            BatchResponse again = h.submit(batchA);
            assertEquals(BatchStatus.COMMITTED, again.batchStatus());
            again.results().forEach(r -> assertInstanceOf(DroppedDuplicate.class, r));
            assertEquals(size, Files.size(journal));
            assertEquals(lines, h.lines().size());
        }
    }
}
