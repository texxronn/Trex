package trex.core;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CanonicalEventTest {

    @Test
    void reappendChangesOnlyNStateFlagsAndComment() {
        CanonicalEvent leg = new CanonicalEvent(7, "ext1", "ing", null, "AUD", LocalDate.parse("2026-06-28"),
            -50000, 1000, "Fast Transfer", "Fast  Transfer", TypeHint.WITHDRAWAL, null, null, null,
            EventState.HELD, null, List.of(), Provenance.BANK, "ing-csv", null, null, null, null, null,
            null, Instant.parse("2026-06-29T00:00:00Z"));
        CanonicalEvent next = leg.reappend(9, EventState.EXTERNAL, List.of(Flag.POTENTIAL_DUP), "note");
        assertEquals(new CanonicalEvent(9, "ext1", "ing", null, "AUD", LocalDate.parse("2026-06-28"),
            -50000, 1000, "Fast Transfer", "Fast  Transfer", TypeHint.WITHDRAWAL, null, null, null,
            EventState.EXTERNAL, null, List.of(Flag.POTENTIAL_DUP), Provenance.BANK, "ing-csv", null, null, null,
            null, null, "note", Instant.parse("2026-06-29T00:00:00Z")), next);
    }
}
