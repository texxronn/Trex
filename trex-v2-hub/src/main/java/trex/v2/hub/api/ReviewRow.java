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
 * POTENTIAL_DUP/RESTATEMENT cluster (empty for every other kind).
 */
public record ReviewRow(String subject, String kind, String detail, Long amountStake,
                        Instant openedAt, String stateHash, String subjectDescription,
                        LocalDate date, List<ReviewMember> members) {}
