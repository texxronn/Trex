package trex.v2.core.derive;

import java.time.LocalDate;

/**
 * A statement account closed by a person's {@code CLOSE_ACCOUNT} decision (V2-CLOSE-ACCOUNT-PLAN.md
 * §3.2): the account keeps its facts, and derivation lands its closing on {@code closingBalance} at
 * {@code closedAt}. {@code adjustment} is the movement the closure introduces
 * ({@code closingBalance − the last fact's balance}); the settling legs named by the decision must
 * sum to it, and it is the amount of the derived closure leg.
 *
 * <p>Never a fact: no evidence, no receipt, not a decision target. It is carried on the derivation
 * so the index, the chain check and the egress anchor all read the same value.
 */
public record ClosedAccount(String accountRef, LocalDate closedAt, long closingBalance, long adjustment,
                            Long decisionN) {}
