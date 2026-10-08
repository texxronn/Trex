package trex.v2.core.derive;

import java.util.Locale;

/**
 * The state of one expected occurrence (V2-COMMITMENTS-PLAN.md §2.5): {@code occurred} (a fact
 * satisfied it — green), {@code settled} (a person concluded it was paid without a fact, §2.9),
 * {@code due} (the window is open), {@code partial} (a fact covered part; the remainder is
 * arrears) or {@code missed} (the window closed unmatched — red; {@code lapsed} while it is the
 * current one).
 */
public enum OccurrenceStatus {
    OCCURRED,
    SETTLED,
    DUE,
    PARTIAL,
    MISSED;

    /** The wire and derived-table form, e.g. {@code occurred}. */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
