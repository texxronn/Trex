package trex.sequencer.ingest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trex.core.state.LedgerView;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static trex.sequencer.ingest.Harness.c;
import static trex.sequencer.ingest.Harness.id;

/** SPEC §7 test 14: restart-fold reproduces latest state and held/review sets from journal lines alone. */
class StateRefoldTest {

    @TempDir
    Path dir;

    @Test
    void refoldReproducesState() {
        try (Harness h = new Harness(dir.resolve("j.jsonl"))) {
            h.submit(c("r1", "ing-savings", "2026-06-28", -50000, "Fast Transfer to CBA", 1000, null),
                c("r2", "ing-savings", "2026-06-29", -700, "Osko to Dave", 300, null),
                c("r3", "ing-savings", "2026-06-29", -900, "Osko to Sam", 100, null),
                c("r4", "ing-savings", "2026-06-30", 100, "Interest", 200, null));
            h.submit(c("r5", "cba-everyday", "2026-06-29", 50000, "Transfer from ING", 60000, null));
            String dave = id(h.submit(c("r2", "ing-savings", "2026-06-29", -700, "Osko to Dave", 300, null)).results().getFirst());
            h.decide(DecisionInput.markExternal("d1", dave, "Dave"));
            h.submit(c("r4", "ing-savings", "2026-06-30", 100, "Interest", 999, null));
            h.submit(c("r6", "cba-everyday", "2026-06-30", 900, "Transfer from ING", 1, null),
                c("r7", "ing-orange", "2026-06-30", 900, "Transfer from ING", 1, null),
                c("r8", "bw-usd", "2026-06-30", -900, "Fast Transfer", 1, null));

            LedgerView before = h.sequencer().view();
            assertFalse(before.heldIds().isEmpty());
            assertFalse(before.reviewIds().isEmpty());

            h.restart();
            assertEquals(before, h.sequencer().view());
        }
    }
}
