package trex.sequencer;

import trex.core.CanonicalEvent;
import trex.core.EventState;
import trex.core.Provenance;
import trex.core.TypeHint;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class TestEvents {

    private TestEvents() {}

    public static CanonicalEvent line(long n, String id, EventState state, long amount) {
        return new CanonicalEvent(n, id, "ing-savings", null, "AUD", LocalDate.parse("2026-06-01"), amount, 1000 + n,
            "desc " + id, "desc  " + id, amount < 0 ? TypeHint.WITHDRAWAL : TypeHint.DEPOSIT, null, null, null,
            state, null, List.of(), Provenance.BANK, "test", null, null, null, null, null, null,
            Instant.parse("2026-06-02T03:04:05Z"));
    }
}
