package trex.v2.core.derive;

import java.time.LocalDate;

/**
 * One derived occurrence of a tracked commitment (V2-COMMITMENTS-PLAN.md §2.5, §2.7
 * {@code commitment_occurrence}): the due date, its matching window, how it was satisfied
 * ({@code matchedBy} is {@code rule} or {@code pin}; {@code settled} by decision carries
 * {@code settleN}) and the allocated amount. An off-window pinned fact becomes an occurrence at
 * its own date with {@code offSchedule} set — "charged twice this month" is never a silent match.
 *
 * <p>Stage 1 does not produce occurrence rows; this is the shape the Stage 2 matcher fills.
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
    String stateHash) {}
