package trex.v2.hub.api;

import java.time.LocalDate;
import java.util.List;

/**
 * The Expected view (V2-COMMITMENTS-PLAN.md §2.8): the materialised occurrences whose due date
 * falls inside a calendar window, each joined to its commitment; the arrears backlog across every
 * commitment, oldest first, with a running total; and the window's committed totals by direction.
 */
public record ExpectedResponse(String window, LocalDate from, LocalDate to,
                               List<Occurrence> occurrences, List<Arrear> arrears, Totals totals,
                               Headroom headroom) {

    /**
     * "Left this month" (V2-REVIEW-FIXES-PLAN.md §10) over the accounts in the budget: what came in
     * and is still expected in, minus what the commitments took and still will, minus everything else
     * spent. A read over the derived tables, not a budget: nothing is planned or stored. Every field
     * is a positive magnitude except {@code left}. {@code movedOut}/{@code movedIn} are transfers
     * between a budget account and one of your own accounts outside it (the offset, savings, a loan):
     * they move {@code left} like any outflow or inflow, but they are not spending or income. {@code missed} (out-commitments whose window
     * closed with nothing seen) and {@code unpaired} (transfer legs still waiting for their other
     * side) are shown, never folded into {@code left}. {@code through} is the oldest statement
     * frontier among the budget accounts: the figure knows nothing after it.
     */
    public record Headroom(LocalDate month, long left, long incomeIn, long incomeDue, long committedPaid,
                           long committedDue, long uncommittedSpend, long movedOut, long movedIn, long missed,
                           long unpaired, LocalDate through, List<String> accounts) {}


    /**
     * One occurrence due in the window. {@code amount} is what the occurrence carries — the
     * movement attached to its window (facts summed) for an {@code occurred} row, the expected
     * amount on a {@code settled} one — and is null for {@code due}/{@code missed};
     * {@code matchedBy} is {@code rule} or {@code pin}; {@code windowStart}/{@code windowEnd} are
     * the occurrence's own matching window (null for an irregular or off-schedule row).
     */
    public record Occurrence(String commitmentId, String commitmentName, String direction,
                             String cadence, LocalDate dueDate, LocalDate windowStart,
                             LocalDate windowEnd, String status, Long amount,
                             String matchedExternalId, LocalDate matchedDate, String matchedBy,
                             boolean offSchedule, Long settleN) {}

    /**
     * One hole in arrears: a {@code missed} occurrence, oldest first. {@code expected} is the
     * commitment's current price (the derived tables do not keep the step timeline, so a price
     * step inside the materialised span makes this an approximation of the hole's own
     * expectation); {@code shortfall} is the expected amount still missing and {@code runningTotal}
     * the backlog accumulated through it. Both are positive magnitudes.
     */
    public record Arrear(String commitmentId, String commitmentName, String direction, String cadence,
                         LocalDate dueDate, String status, Long amount, long expected, long shortfall,
                         long runningTotal) {}

    /** The window's committed totals by direction; both are positive magnitudes. */
    public record Totals(long out, long in) {}
}
