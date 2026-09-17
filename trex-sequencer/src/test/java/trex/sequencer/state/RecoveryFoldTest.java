package trex.sequencer.state;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trex.core.CanonicalEvent;
import trex.core.EventState;
import trex.core.Flag;
import trex.sequencer.TestEvents;
import trex.sequencer.journal.JsonlJournal;
import trex.sequencer.journal.Recovery;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** SPEC §7 test 7: append N, restart (fold), state matches pre-restart. */
class RecoveryFoldTest {

    @TempDir
    Path dir;

    @Test
    void foldAfterRestartMatchesLiveState() {
        Path p = dir.resolve("j.jsonl");
        CanonicalEvent a = TestEvents.line(1, "a", EventState.HELD, -500);
        CanonicalEvent b = TestEvents.line(2, "b", EventState.EXTERNAL, 700);
        CanonicalEvent c = TestEvents.line(3, "c", EventState.REVIEW, -500);
        CanonicalEvent d = TestEvents.line(4, "d", EventState.HELD, 500);
        CanonicalEvent bDup = b.reappend(5, EventState.EXTERNAL, List.of(Flag.POTENTIAL_DUP), null);
        CanonicalEvent dExternal = d.reappend(6, EventState.EXTERNAL, List.of(), "not mine");

        Ledger live = new Ledger();
        LedgerView before;
        try (JsonlJournal j = new JsonlJournal(p)) {
            for (List<CanonicalEvent> batch : List.of(List.of(a, b), List.of(c, d), List.of(bDup, dExternal))) {
                long head = j.appendBatch(batch);
                batch.forEach(live::apply);
                live.setHeadOffset(head);
            }
            before = live.snapshot();
        }

        Recovery.recover(p, p);
        LedgerView after;
        try (JsonlJournal j = new JsonlJournal(p)) {
            after = Fold.fold(j).snapshot();
        }

        assertEquals(before, after);
        assertEquals(6, after.highWaterN());
        assertEquals(java.util.Set.of("a"), after.heldIds());
        assertEquals(java.util.Set.of("b", "c"), after.reviewIds());
        assertEquals(b, after.firstLine().get("b"));
        assertEquals(dExternal, after.latestLines().stream().filter(e -> e.externalId().equals("d")).findFirst().orElseThrow());
    }

    @Test
    void nonIncreasingNIsRejected() {
        Ledger ledger = new Ledger();
        ledger.apply(TestEvents.line(2, "a", EventState.EXTERNAL, 1));
        assertThrows(IllegalStateException.class, () -> ledger.apply(TestEvents.line(2, "b", EventState.EXTERNAL, 1)));
    }
}
