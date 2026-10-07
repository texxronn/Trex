package trex.v2.core.derive;

import trex.v2.core.Fact;

/**
 * A pending observation's derived settlement state (V2-PROPOSAL.md §7.2, §9.9.D). The pending
 * fact itself lives in the log; this row is the derived answer, never counted in totals.
 */
public record PendingRow(Fact fact, String settledBy, PendingState state) {

    public String externalId() {
        return fact.externalId();
    }
}
