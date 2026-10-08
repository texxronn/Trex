package trex.v2.core.derive;

import java.util.Locale;

/**
 * The state of one expected occurrence (V2-MANUAL-ARREARS-PLAN.md §3.2): {@code occurred} (a fact
 * landed in the window — green), {@code settled} (a person concluded it was paid without a fact),
 * {@code due} (the window is open) or {@code missed} (the window closed unmatched — red;
 * {@code lapsed} while it is the current one). {@code partial} is retired from automatic output
 * (a fact's amount is what moved, never a shortfall); the value stays so an old index or a future
 * explicit allocation can still be rendered.
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
