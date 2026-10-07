package trex.v2.core.derive;

import java.util.Locale;

/**
 * What a current fact is (V2-PROPOSAL.md §6.9): a {@code TRANSACTION} posts — it can pair, it is a
 * unit, and it contributes to its account's sums and chain. A {@code NOOP} is recorded and visible
 * but is not a posting of its account: no chain edge, no transfer leg, no unit, no sum.
 *
 * <p>The role is derived from account profiles and {@code MARK_NOOP}/{@code UNMARK_NOOP} decisions
 * (decisions winning), never stored on the line — so changing a rule is a reflow, not a re-parse.
 */
public enum Role {
    TRANSACTION,
    NOOP;

    /** The stored and JSON form, e.g. {@code noop}. */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Role fromWire(String wire) {
        for (Role r : values()) {
            if (r.wire().equals(wire)) {
                return r;
            }
        }
        throw new IllegalArgumentException("unknown role '" + wire + "'");
    }
}
