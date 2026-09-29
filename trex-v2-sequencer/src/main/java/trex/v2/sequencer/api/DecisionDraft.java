package trex.v2.sequencer.api;

import java.time.Instant;
import java.util.List;

/**
 * A decision as submitted (V2-PROPOSAL.md §6.2). One flat shape with every action's fields; the
 * sequencer selects the action's required fields and rejects anything malformed. {@code n} is
 * assigned by the writer; {@code at} defaults to now when a caller does not supply it.
 */
public record DecisionDraft(
    String action,
    String actor,
    String user,
    Instant at,
    String comment,
    // PAIR / UNPAIR
    String legA,
    String legB,
    // MARK_EXTERNAL / RETIRE / NOTE
    String externalId,
    // SETTLE
    String pendingId,
    String postedId,
    // DISMISS
    String item,
    List<String> externalIds,
    // PIN / UNPIN
    String category,
    // SUPERSEDE
    String fromId,
    String toId,
    String reason,
    // REVOKE
    Long target,
    // USER_ACK
    String period,
    Long throughN,
    String configRevision,
    String deriveVersion,
    String hashVersion,
    String stateHash,
    // NOTE
    String text
) {}
