package trex.v2.core.derive;

import java.time.Instant;

/**
 * The latest effective {@code USER_ACK} for one (user, period) (V2-PROPOSAL.md §7.2, §9.4). It is
 * a queryable copy of a decision line, never truth: delete the index and it comes back. Staleness
 * is computed against the current period hash, so it is not stored here.
 */
public record UserAckRow(String userId, String period, long throughN, String stateHash,
                         String configRevision, String deriveVersion, String hashVersion,
                         Instant ackedAt, long decisionN) {}
