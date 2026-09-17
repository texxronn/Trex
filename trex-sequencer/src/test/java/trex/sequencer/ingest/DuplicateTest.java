package trex.sequencer.ingest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trex.core.CandidateResult.DroppedDuplicate;
import trex.core.CandidateResult.Flagged;
import trex.core.CanonicalEvent;
import trex.core.EventState;
import trex.core.Flag;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static trex.sequencer.ingest.Harness.c;
import static trex.sequencer.ingest.Harness.id;

/** POTENTIAL_DUP, flag only (SPEC §3.3 step 5). */
class DuplicateTest {

    @TempDir
    Path dir;

    @Test
    void differentBalanceFlagsOnceWithStateUnchanged() {
        try (Harness h = new Harness(dir.resolve("j.jsonl"))) {
            String id = id(h.submit(c("r1", "ing-savings", "2026-06-01", -500, "Fast Transfer", 1000, "5")).results().getFirst());

            var r = h.submit(c("r1", "ing-savings", "2026-06-01", -500, "Fast Transfer", 999, "5"));
            assertEquals(new Flagged("r1", id, List.of(Flag.POTENTIAL_DUP)), r.results().getFirst());
            List<CanonicalEvent> lines = h.lines();
            assertEquals(2, lines.size());
            assertEquals(EventState.HELD, lines.get(1).state());
            assertEquals(List.of(Flag.POTENTIAL_DUP), lines.get(1).flags());
            assertEquals(1000, lines.get(1).balance());
            assertEquals(1, h.sequencer().view().review().size());

            var again = h.submit(c("r1", "ing-savings", "2026-06-01", -500, "Fast Transfer", 999, "5"));
            assertInstanceOf(Flagged.class, again.results().getFirst());
            assertEquals(2, h.lines().size());
        }
    }

    @Test
    void sameIdTwiceInOneBatch() {
        try (Harness h = new Harness(dir.resolve("j.jsonl"))) {
            var r = h.submit(
                c("r1", "ing-savings", "2026-06-01", -500, "Coffee", 1000, "7"),
                c("r2", "ing-savings", "2026-06-01", -500, "Coffee", 1000, "7"),
                c("r3", "ing-savings", "2026-06-01", -500, "Coffee", 1234, "7"));
            assertInstanceOf(DroppedDuplicate.class, r.results().get(1));
            assertInstanceOf(Flagged.class, r.results().get(2));
            List<CanonicalEvent> lines = h.lines();
            assertEquals(2, lines.size());
            assertEquals(List.of(), lines.get(0).flags());
            assertEquals(List.of(Flag.POTENTIAL_DUP), lines.get(1).flags());
            assertEquals(EventState.EXTERNAL, lines.get(1).state());
        }
    }
}
