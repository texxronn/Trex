package trex.v2.sequencer.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * A decision as submitted (V2-PROPOSAL.md §6.2). One flat shape with every action's fields; the
 * sequencer selects the action's required fields and rejects anything malformed. {@code n} is
 * assigned by the writer; {@code at} defaults to now when a caller does not supply it.
 *
 * <p>The commitment actions (V2-COMMITMENTS-PLAN.md §2.6) reuse the flat shape: a declaration
 * carries {@code commitmentId}, {@code name}, the faces, {@code matches} and its optional fields;
 * {@code PIN_COMMITMENT}/{@code UNPIN_COMMITMENT} and
 * {@code EXCLUDE_COMMITMENT}/{@code INCLUDE_COMMITMENT} reuse {@code externalIds},
 * {@code NOTE_COMMITMENT} reuses {@code text}, and {@code SETTLE_OCCURRENCE} reuses
 * {@code dueDates}.
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
    // PIN / UNPIN / PIN_COMMITMENT / UNPIN_COMMITMENT
    String category,
    // SUPERSEDE
    String fromId,
    String toId,
    String reason,
    // REVOKE
    Long target,
    // USER_ACK
    String configRevision,
    String deriveVersion,
    String hashVersion,
    String stateHash,
    // NOTE / NOTE_COMMITMENT
    String text,
    // ATTACH_ACCOUNT
    String account,
    // DECLARE_COMMITMENT / RETIRE_COMMITMENT / PIN_COMMITMENT / NOTE_COMMITMENT /
    // SETTLE_OCCURRENCE
    String commitmentId,
    // DECLARE_COMMITMENT
    String name,
    String direction,
    String cadence,
    String amountKind,
    String kind,
    List<MatchDraft> matches,
    Long amount,
    LocalDate anchor,
    String fromCandidate,
    // RETIRE_COMMITMENT
    LocalDate endedAt,
    // IGNORE_RECURRING
    String candidate,
    // SETTLE_OCCURRENCE
    List<LocalDate> dueDates
) {

    /** One {@code DECLARE_COMMITMENT} match rule; {@code account} is optional. */
    public record MatchDraft(String match, String account) {}

    /**
     * The pre-commitment arity, retained for the v1 importer (which predates commitments and never
     * sets one of their fields). New code uses the canonical constructor.
     */
    public DecisionDraft(String action, String actor, String user, Instant at, String comment,
                         String legA, String legB, String externalId, String pendingId, String postedId,
                         String item, List<String> externalIds, String category, String fromId, String toId,
                         String reason, Long target, String configRevision, String deriveVersion,
                         String hashVersion, String stateHash, String text, String account) {
        this(action, actor, user, at, comment, legA, legB, externalId, pendingId, postedId, item,
            externalIds, category, fromId, toId, reason, target, configRevision, deriveVersion,
            hashVersion, stateHash, text, account, null, null, null, null, null, null, null, null,
            null, null, null, null, null);
    }
}
