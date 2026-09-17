package trex.core.state;

import org.junit.jupiter.api.Test;
import trex.core.CanonicalEvent;
import trex.core.EventState;
import trex.core.Flag;
import trex.core.Provenance;
import trex.core.TypeHint;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LedgerTest {

    private static CanonicalEvent line(long n, String id, EventState state) {
        return new CanonicalEvent(n, id, "acc", null, "AUD", LocalDate.parse("2026-06-01"), -100, 0, "d", "d",
            TypeHint.WITHDRAWAL, null, null, null, state, null, List.of(), Provenance.BANK, "t",
            null, null, null, null, null, null, Instant.parse("2026-06-02T00:00:00Z"));
    }

    @Test
    void latestLineWinsAndViewsFollowIt() {
        Ledger ledger = new Ledger();
        CanonicalEvent a = line(1, "a", EventState.HELD);
        CanonicalEvent b = line(2, "b", EventState.EXTERNAL);
        CanonicalEvent c = line(3, "c", EventState.REVIEW);
        ledger.apply(a);
        ledger.apply(b);
        ledger.apply(c);
        ledger.apply(b.reappend(4, EventState.EXTERNAL, List.of(Flag.POTENTIAL_DUP), null));
        ledger.apply(a.reappend(5, EventState.EXTERNAL, List.of(), "x"));

        LedgerView v = ledger.snapshot();
        assertEquals(Set.of(), v.heldIds());
        assertEquals(List.of("c", "b"), v.review().stream().map(CanonicalEvent::externalId).toList());
        assertEquals(a, ledger.firstLine("a"));
        assertEquals(EventState.EXTERNAL, ledger.latest("a").state());
        assertEquals(5, v.highWaterN());
    }

    @Test
    void nonIncreasingNIsRejected() {
        Ledger ledger = new Ledger();
        ledger.apply(line(2, "a", EventState.EXTERNAL));
        assertThrows(IllegalStateException.class, () -> ledger.apply(line(2, "b", EventState.EXTERNAL)));
    }
}
