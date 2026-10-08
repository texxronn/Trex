package trex.v2.core.derive;

import java.util.Locale;

/**
 * How a commitment's amount behaves (V2-COMMITMENTS-PLAN.md §2.1): a fixed price, a usage-driven
 * variable amount, or a declared floor/ceiling range. Detection produces {@code fixed} or
 * {@code variable}; {@code range} is declared only.
 */
public enum AmountKind {
    FIXED,
    VARIABLE,
    RANGE;

    /** The wire and derived-table form, e.g. {@code variable}. */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
