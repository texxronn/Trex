package trex.v2.hub.api;

import java.time.LocalDate;
import java.util.List;

/**
 * The Expected view (V2-COMMITMENTS-PLAN.md §2.8): the materialised occurrences whose due date
 * falls inside a calendar window, each joined to its commitment; the arrears backlog across every
 * commitment, oldest first, with a running total; and the window's committed totals by direction.
 */
public record ExpectedResponse(String window, LocalDate from, LocalDate to,
                               List<Occurrence> occurrences, List<Arrear> arrears, Totals totals) {

    /**
     * One occurrence due in the window. {@code amount} is what the occurrence carries (the
     * allocated share for an {@code occurred}/{@code partial} row, the expected amount on a
     * {@code settled} one) and is null for {@code due}/{@code missed}; {@code matchedBy} is
     * {@code rule} or {@code pin}; {@code windowStart}/{@code windowEnd} are the occurrence's own
     * matching window (null for an irregular or off-schedule row).
     */
    public record Occurrence(String commitmentId, String commitmentName, String direction,
                             String cadence, LocalDate dueDate, LocalDate windowStart,
                             LocalDate windowEnd, String status, Long amount,
                             String matchedExternalId, LocalDate matchedDate, String matchedBy,
                             boolean offSchedule, Long settleN) {}

    /**
     * One occurrence in arrears: a {@code missed} or {@code partial} occurrence, oldest first.
     * {@code amount} is the allocated share (null on {@code missed}); {@code expected} is the
     * commitment's current price (the derived tables do not keep the step timeline, so a price step
     * inside the materialised span makes this an approximation of the occurrence's own
     * expectation); {@code shortfall} is what is still missing on the row and {@code runningTotal}
     * the backlog accumulated through it. All three are positive magnitudes.
     */
    public record Arrear(String commitmentId, String commitmentName, String direction, String cadence,
                         LocalDate dueDate, String status, Long amount, long expected, long shortfall,
                         long runningTotal) {}

    /** The window's committed totals by direction; both are positive magnitudes. */
    public record Totals(long out, long in) {}
}
