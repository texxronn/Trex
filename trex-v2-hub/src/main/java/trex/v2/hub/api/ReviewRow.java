package trex.v2.hub.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * A derived review item (V2-PROPOSAL.md §10.1 Review mode). {@code subject} is an id, or a decision
 * n; {@code subjectDescription} is the subject fact's verbatim description when it has one, so the
 * queue can show a person what the item is about instead of a bare id. {@code date} is the subject
 * fact's own transaction date, distinct from {@code openedAt} (when the item was derived); it is
 * null when the subject is a decision rather than a fact. {@code members} carries the rows of a
 * POTENTIAL_DUP/RESTATEMENT cluster (empty for every other kind). {@code accountRef} is the subject
 * fact's account (null for a decision subject), so the queue can show which account it concerns.
 * {@code enrichment} carries the candidate facts a {@code SUSPECTED_RECURRING} row renders without
 * a second fetch; it is null for every other kind (V2-COMMITMENTS-PLAN.md §2.8).
 */
public record ReviewRow(String subject, String kind, String detail, Long amountStake,
                        Instant openedAt, String stateHash, String subjectDescription,
                        LocalDate date, List<ReviewMember> members, String accountRef,
                        Long amount, Enrichment enrichment) {

    /**
     * The candidate behind a {@code SUSPECTED_RECURRING} subject — the grouping stem — joined to
     * its derived {@code commitment} row ({@code cand|<hex>}): the cadence, the span, the observed
     * occurrence count and current price, and the price step's size. A clean series carries a null
     * {@code changePct} and a candidate whose row is gone carries a null enrichment.
     */
    public record Enrichment(String cadence, LocalDate firstDate, LocalDate lastDate,
                             int occurrenceCount, Long currentAmount, Double regularity,
                             Double changePct) {}
}
