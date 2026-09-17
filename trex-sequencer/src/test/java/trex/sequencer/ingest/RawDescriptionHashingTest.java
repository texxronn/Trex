package trex.sequencer.ingest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trex.core.Candidate;
import trex.core.CanonicalEvent;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static trex.sequencer.ingest.Harness.c;

/** SPEC §7 test 15. */
class RawDescriptionHashingTest {

    @TempDir
    Path dir;

    @Test
    void changingCleaningLeavesExternalIdsUnchanged() {
        Candidate[] batch = {
            c("r1", "cba-everyday", "2026-06-01", -450, "  COFFEE   CART ", 100, null),
            c("r2", "cba-everyday", "2026-06-01", -450, "  COFFEE   CART ", 50, null),
            c("r3", "ing-savings", "2026-06-02", -1000, "Woolworths  Receipt 9", 10, "9")
        };
        List<CanonicalEvent> standard;
        List<CanonicalEvent> shouting;
        try (Harness h = new Harness(dir.resolve("a.jsonl"))) {
            h.submit(batch);
            standard = h.lines();
        }
        try (Harness h = new Harness(dir.resolve("b.jsonl"), raw -> raw.toUpperCase().replace(" ", "_"))) {
            h.submit(batch);
            shouting = h.lines();
        }
        assertEquals(standard.stream().map(CanonicalEvent::externalId).toList(),
            shouting.stream().map(CanonicalEvent::externalId).toList());
        assertNotEquals(standard.getFirst().description(), shouting.getFirst().description());
        assertEquals("COFFEE CART", standard.getFirst().description());
    }
}
