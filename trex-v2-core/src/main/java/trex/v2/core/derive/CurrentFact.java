package trex.v2.core.derive;

import trex.v2.core.Fact;
import trex.v2.core.Rail;

/**
 * A current transaction (V2-PROPOSAL.md §7.2 {@code txn_current}, §9.9 P6): the latest posted
 * observation of a fact id that is not superseded or retired, with its derived pairing state,
 * category and content hash. The derived fields are answers, not properties of the transaction.
 *
 * <p>{@code rail} is the transfer-pattern rail <b>method</b> for this row (§9.9.C.4), or null when
 * no pattern matches. The direction ({@code IN}/{@code OUT}) is not stored — it is the sign.
 *
 * <p>{@code stateHash} is the row's content hash ({@link StateHash#forRow}) — what a read marker
 * records so a later reflow can tell whether the row a person read has moved (§9.4).
 */
public record CurrentFact(Fact fact, Role role, Rail rail, LegState leg, String transferId,
                          String category, CategoryOrigin categoryOrigin, String ruleId,
                          String stateHash) {

    public String externalId() {
        return fact.externalId();
    }

    public boolean matched() {
        return leg == LegState.MATCHED;
    }

    /** A noop row is recorded but is not a posting of its account (V2-PROPOSAL.md §6.9). */
    public boolean isNoop() {
        return role == Role.NOOP;
    }

    public CurrentFact withRole(Role newRole) {
        return new CurrentFact(fact, newRole, rail, leg, transferId, category, categoryOrigin, ruleId, stateHash);
    }

    /** The same row with its content hash filled in; derive computes it once the row is final. */
    public CurrentFact withStateHash(String hash) {
        return new CurrentFact(fact, role, rail, leg, transferId, category, categoryOrigin, ruleId, hash);
    }
}
