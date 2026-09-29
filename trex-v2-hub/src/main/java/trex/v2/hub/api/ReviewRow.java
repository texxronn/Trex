package trex.v2.hub.api;

import java.time.Instant;

/** A derived review item (V2-PROPOSAL.md §10.1 Review mode). {@code subject} is an id, or a decision n. */
public record ReviewRow(String subject, String kind, String detail, Long amountStake,
                        Instant openedAt, String stateHash) {}
