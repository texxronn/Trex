package trex.v2.core.derive;

import java.util.Locale;

/**
 * A commitment's lifecycle at {@code asOf} (V2-COMMITMENTS-PLAN.md §2.1):
 *
 * <ul>
 *   <li>{@code candidate} — detected and not yet declared or ignored; active candidates ship to
 *       Review.</li>
 *   <li>{@code active} — expecting occurrences: the last one is within one cadence + tolerance of
 *       the account's posted frontier.</li>
 *   <li>{@code dormant} — tracked and silent for more than one cadence + tolerance while the
 *       frontier keeps advancing (silence, not missing data).</li>
 *   <li>{@code ended} — retired by a person, or (for a candidate) silent beyond the frontier by
 *       more than two periods.</li>
 * </ul>
 *
 * <p>{@code lapsed} is an overlay on the current occurrence, not a lifecycle value.
 */
public enum CommitmentStatus {
    CANDIDATE,
    ACTIVE,
    DORMANT,
    ENDED;

    /** The wire and derived-table form, e.g. {@code dormant}. */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
