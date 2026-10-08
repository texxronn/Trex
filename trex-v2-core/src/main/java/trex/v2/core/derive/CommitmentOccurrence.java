package trex.v2.core.derive;

import trex.v2.core.Hashes;

import java.time.LocalDate;

/**
 * One derived occurrence of a tracked commitment (V2-COMMITMENTS-PLAN.md §2.5, §2.7
 * {@code commitment_occurrence}): the due date, its matching window, how it was satisfied
 * ({@code matchedBy} is {@code rule} or {@code pin}; {@code settled} by decision carries
 * {@code settleN}) and the allocated amount. An off-window pinned fact becomes an occurrence at
 * its own date with {@code offSchedule} set — "charged twice this month" is never a silent match.
 *
 * <p>Stage 1 does not produce occurrence rows; this is the shape the Stage 2 matcher fills.
 *
 * <p>{@code stateHash} is filled by {@link #hashed()}, not by a caller, so it cannot drift from
 * the content it hashes.
 */
public record CommitmentOccurrence(
    String commitmentId,
    LocalDate dueDate,
    OccurrenceStatus status,
    LocalDate windowStart,
    LocalDate windowEnd,
    String matchedExternalId,
    LocalDate matchedDate,
    String matchedBy,
    boolean offSchedule,
    Long settleN,
    Long amount,
    String stateHash) {

    /**
     * The same row with its state hash computed from its canonical content (§2.7): the identity
     * and outcome fields — commitment id, due date, status, the matched fact id and date, the
     * allocated amount, the settling decision and the off-schedule marker. The window is not
     * hashed (it follows from the schedule and cadence) and neither is {@code matchedBy} (how a
     * fact was placed does not change what the row says). A rebuild re-derives the identical
     * value.
     */
    public CommitmentOccurrence hashed() {
        String canonical = commitmentId + '|' + dueDate + '|' + status.wire() + '|'
            + matchedExternalId + '|' + matchedDate + '|' + amount + '|' + settleN + '|'
            + offSchedule;
        return new CommitmentOccurrence(commitmentId, dueDate, status, windowStart, windowEnd,
            matchedExternalId, matchedDate, matchedBy, offSchedule, settleN, amount,
            Hashes.sha256(canonical));
    }
}
