package trex.v2.core.derive;

import trex.v2.core.Fact;

/**
 * A current transaction (V2-PROPOSAL.md §7.2 {@code txn_current}, §9.9 P6): the latest posted
 * observation of a fact id that is not superseded or retired, with its derived pairing state and
 * category. The derived fields are answers, not properties of the transaction.
 */
public record CurrentFact(Fact fact, LegState leg, String transferId,
                          String category, CategoryOrigin categoryOrigin, String ruleId) {

    public String externalId() {
        return fact.externalId();
    }

    public boolean matched() {
        return leg == LegState.MATCHED;
    }
}
