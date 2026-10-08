package trex.v2.core.derive;

import java.util.Locale;

/**
 * Where a commitment came from (V2-COMMITMENTS-PLAN.md §2.1): {@code detected} (a predictable
 * cadence found in the facts) or {@code declared} (a person said so). Only {@code detected} rows
 * exist until the declaring decisions land.
 */
public enum CommitmentOrigin {
    DETECTED,
    DECLARED;

    /** The wire and derived-table form, e.g. {@code detected}. */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
