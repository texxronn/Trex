package trex.sequencer.ingest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trex.core.BatchStatus;
import trex.core.Candidate;
import trex.core.CandidateResult.DroppedDuplicate;
import trex.core.CandidateResult.Rejected;
import trex.core.CandidateResult.Resolved;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static trex.sequencer.ingest.Harness.c;

/** SPEC §7 test 12 (pipeline level; the HTTP variant runs in stage 4). */
class AllOrNoneTest {

    @TempDir
    Path dir;

    private static final Candidate GOOD1 = c("row-2", "ing-savings", "2026-06-01", -100, "Coffee", 900, null);
    private static final Candidate GOOD2 = c("row-3", "ing-savings", "2026-06-01", -200, "Lunch", 700, null);
    private static final Candidate BAD = c("row-4", "no-such-account", "2026-06-01", -300, "Dinner", 400, null);
    private static final Candidate FIXED = c("row-4", "ing-savings", "2026-06-01", -300, "Dinner", 400, null);

    @Test
    void allOrNoneTrueAppendsNothing() {
        try (Harness h = new Harness(dir.resolve("j.jsonl"))) {
            BatchResponse r = h.submit(true, GOOD1, BAD, GOOD2);
            assertEquals(BatchStatus.REJECTED, r.batchStatus());
            assertEquals(1, r.results().size());
            assertInstanceOf(Rejected.class, r.results().getFirst());
            assertEquals(0, h.lines().size());
        }
    }

    @Test
    void allOrNoneFalseCommitsGoodRowsAndFixedResubmitReDedups() {
        try (Harness h = new Harness(dir.resolve("j.jsonl"))) {
            BatchResponse r = h.submit(false, GOOD1, BAD, GOOD2);
            assertEquals(BatchStatus.PARTIAL, r.batchStatus());
            assertEquals(List.of(Resolved.class, Rejected.class, Resolved.class),
                r.results().stream().map(Object::getClass).toList());
            assertEquals(2, h.lines().size());

            BatchResponse fixed = h.submit(false, GOOD1, FIXED, GOOD2);
            assertEquals(BatchStatus.COMMITTED, fixed.batchStatus());
            assertEquals(List.of(DroppedDuplicate.class, Resolved.class, DroppedDuplicate.class),
                fixed.results().stream().map(Object::getClass).toList());
            assertEquals(3, h.lines().size());
        }
    }

    @Test
    void unbindableElementIsRejectedWithDefaultRef() {
        try (Harness h = new Harness(dir.resolve("j.jsonl"))) {
            BatchResponse r = h.sequencer().submitCandidates(false, Stream.of(
                CandidateInput.bound(GOOD1), CandidateInput.unbindable(null, "amount is not an integer")).toList());
            assertEquals(new Rejected("idx-1", "amount is not an integer"), r.results().get(1));
        }
    }
}
