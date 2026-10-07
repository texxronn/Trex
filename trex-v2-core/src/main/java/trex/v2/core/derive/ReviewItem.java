package trex.v2.core.derive;

import java.time.Instant;

/**
 * A derived review item (V2-PROPOSAL.md §7.2, §9.9.F). {@code subject} is an external id, the
 * decision {@code n} for {@code INEFFECTIVE_DECISION}, or the account ref for
 * {@code BALANCE_BREAK}. {@code stateHash} hashes the detail payload so the item survives a
 * rebuild identically.
 */
public record ReviewItem(String subject, String kind, String detail, Long amountStake,
                         Instant openedAt, String stateHash) {

    public static final String POTENTIAL_DUP = "POTENTIAL_DUP";
    public static final String RESTATEMENT = "RESTATEMENT";
    public static final String AMBIGUOUS_TRANSFER = "AMBIGUOUS_TRANSFER";
    public static final String AMBIGUOUS_SETTLEMENT = "AMBIGUOUS_SETTLEMENT";
    public static final String UNMATCHED_LEG = "UNMATCHED_LEG";
    public static final String STALE_PENDING = "STALE_PENDING";
    public static final String INEFFECTIVE_DECISION = "INEFFECTIVE_DECISION";
    public static final String BALANCE_BREAK = "BALANCE_BREAK";
}
