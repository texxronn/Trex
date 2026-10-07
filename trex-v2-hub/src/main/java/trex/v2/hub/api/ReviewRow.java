package trex.v2.hub.api;

import java.time.Instant;

/**
 * A derived review item (V2-PROPOSAL.md §10.1 Review mode). {@code subject} is an id, or a decision
 * n; {@code subjectDescription} is the subject fact's verbatim description when it has one, so the
 * queue can show a person what the item is about instead of a bare id.
 */
public record ReviewRow(String subject, String kind, String detail, Long amountStake,
                        Instant openedAt, String stateHash, String subjectDescription) {}
