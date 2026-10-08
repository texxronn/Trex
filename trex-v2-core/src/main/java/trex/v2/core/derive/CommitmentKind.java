package trex.v2.core.derive;

import java.util.Locale;

/**
 * What a commitment is for (V2-COMMITMENTS-PLAN.md §2.1). Detection cannot infer the label, so a
 * detected candidate is {@code other} until a person declares what it is.
 */
public enum CommitmentKind {
    SUBSCRIPTION,
    BILL,
    INSURANCE,
    FEE,
    TAX,
    INCOME,
    OTHER;

    /** The wire and derived-table form, e.g. {@code subscription}. */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
