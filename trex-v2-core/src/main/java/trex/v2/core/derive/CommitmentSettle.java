package trex.v2.core.derive;

import java.time.LocalDate;

/**
 * One effective off-journal settlement (V2-COMMITMENTS-PLAN.md §2.6, §2.9): the resolved form of a
 * {@code SETTLE_OCCURRENCE}. A person concluded the occurrence was met without a fact to show, so
 * the matcher marks it {@code settled} and no later fact allocates to it — a decision wins over
 * matching.
 *
 * @param decisionN the settling decision's sequence number, carried onto the occurrence so the
 *                  conclusion stays attributed and revocable
 */
public record CommitmentSettle(String commitmentId, LocalDate dueDate, long decisionN) {}
