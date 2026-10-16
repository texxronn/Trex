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
                               Headroom headroom, List<ToPay> toPay) {

    public ExpectedResponse(String window, LocalDate from, LocalDate to,
                            List<Occurrence> occurrences, List<Arrear> arrears, Totals totals,
                            Headroom headroom) {
        this(window, from, to, occurrences, arrears, totals, headroom, List.of());
    }

    /**
     * "Left this month" (V2-SPEC.md §9.2): the <b>Cost</b> view of the month's cash-flow sheet
     * (V2-CASHFLOW-PLAN.md C-4) over the liquid accounts, from the same row-walk, so the status strip
     * and the sheet cannot drift. {@code left} is {@code incomeIn + incomeDue − committedPaid −
     * committedDue − uncommittedSpend − unrecordedCash}. Income and spend are the liquid accounts'
     * external rows and the declared loans' charges, with commitments counted through their
     * occurrences (a paid one in its payment month, C-5); a move between your own accounts is never
     * income or spend, and the principal and set-aside are not Out in the Cost view. A read over the
     * derived tables, not a budget: nothing is planned or stored. Every field is a positive magnitude
     * except {@code left} and {@code unrecordedCash}. {@code committedDue} holds the declared loan
     * interest commitments' due occurrences and never the repayments' (a repayment is a move).
     * {@code missed} (out-commitments whose window closed with nothing seen) and {@code unpaired}
     * (transfer legs still waiting for their other side) are shown, never folded into {@code left}.
     * {@code through} is the oldest statement frontier among the liquid accounts: the figure knows
     * nothing after it. {@code unrecordedCash} is the month's unrecorded cash (V2-CASH-PLAN.md §4.2)
     * over the liquid wallets — the {@code cash_month} figure, signed: a positive month is spend
     * nobody recorded, a negative one cash that appeared unrecorded.
     */
    public record Headroom(LocalDate month, long left, long incomeIn, long incomeDue, long committedPaid,
                           long committedDue, long uncommittedSpend, long unrecordedCash, long missed,
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
                             boolean offSchedule, Long settleN, String payment) {}

    /** A window-independent occurrence needing manual payment, in the status strip's bucket order. */
    public record ToPay(String commitmentId, String commitmentName, String direction,
                        String cadence, LocalDate dueDate, LocalDate windowStart,
                        LocalDate windowEnd, String status, Long amount,
                        String matchedExternalId, LocalDate matchedDate, String matchedBy,
                        boolean offSchedule, Long settleN, String payment, String bucket,
                        int remindDays) {}

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
