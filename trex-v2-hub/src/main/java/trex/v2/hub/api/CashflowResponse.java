package trex.v2.hub.api;

import java.time.LocalDate;
import java.util.List;

/**
 * The month's cash-flow sheet (V2-SPEC.md §9.2, V2-CASHFLOW-PLAN.md C-2…C-6): Money In, Money Out
 * and Left of the household's <b>liquid</b> accounts, in one of two views of the mortgage.
 * {@code view} is {@code cost} (the loan charges sit in their category groups; the principal and the
 * set-aside are reported in {@link #loans} and {@link #setAside}, never in Out) or {@code cash} (the
 * charges leave their groups and, with the principal, form one {@code Mortgage} slice equal to the
 * repayments; {@code Set aside} is a slice), so a past month's Cash {@code left} is the change in the
 * liquid accounts.
 *
 * <p>A read of the derived index over the same row-walk as {@code headroom}: the Cost view's
 * {@code left} equals {@code /api/expected}'s {@code headroom.left} for the same month. Amounts are
 * positive magnitudes except {@code left}, the reconcile figures, a loan's principal and a
 * set-aside, which are signed. {@code through} is the oldest statement frontier among the liquid
 * accounts and {@code accounts} the liquid refs.
 */
public record CashflowResponse(String month, String view, LocalDate through, List<String> accounts,
                               Income income, Spend spend, Loans loans, SetAside setAside,
                               long missed, long unpaired, long left, Reconcile reconcile) {

    /**
     * Money In. {@code posted} is income already seen — a paid income occurrence or an uncommitted
     * credit on a liquid account, and in the Cost view a credit on a loan account; {@code due} is
     * income still expected at the commitment's current price; {@code total} is the two summed.
     * {@code lines} is the posted income per category, amount descending then name; income is never
     * grouped (§11.2).
     */
    public record Income(long posted, long due, long total, List<Line> lines) {}

    /** One income category's posted amount. */
    public record Line(String category, long amount) {}

    /**
     * Money Out. {@code posted} is every posted slice — the category groups and, in the Cash view,
     * {@code Mortgage} and {@code Set aside}; {@code due} is a commitment still owed at its current
     * price (Cost: the interest commitments, never the repayments; Cash: the reverse);
     * {@code unrecorded} is the month's unrecorded cash (§9.4), signed. {@code total} is the three
     * summed and equals the sum of {@code slices}: the groups (amount descending, then name), then in
     * the Cash view {@code Mortgage} and {@code Set aside}, then {@code Due}, then
     * {@code Unrecorded}; a zero slice is omitted.
     */
    public record Spend(long posted, long due, long unrecorded, long total, List<Slice> slices) {}

    /**
     * One Money Out slice with its {@code parts} summing to its amount: a group's trex categories,
     * the {@code Mortgage} slice's {@code Interest & fees} and {@code Principal}, the {@code Set
     * aside} slice's accounts; {@code Due} and {@code Unrecorded} have none.
     */
    public record Slice(String group, long amount, List<Part> parts) {

        /** A synthetic slice with no part distribution. */
        public Slice(String group, long amount) {
            this(group, amount, List.of());
        }
    }

    /** One part of a slice: a category, an account ref, or a mortgage component. */
    public record Part(String label, long amount) {}

    /**
     * The month's declared loans (C-3), in both views. {@code repayments} is what the liquid
     * accounts paid into the loans net of redraws; {@code charges} the loans' own external debits
     * (interest, fees) net of their credits; {@code principal = repayments − charges} — the "→ home
     * equity" figure, negative in an odd month and reported as is. {@code parts} is one entry per
     * loan account with any movement, by account.
     */
    public record Loans(long repayments, long charges, long principal, List<Loan> parts) {}

    /** One loan account's month: the same three figures as {@link Loans}. */
    public record Loan(String account, long repayments, long charges, long principal) {}

    /**
     * Money moved from a liquid account to another of your own non-liquid ones that is not a loan
     * (the kids' accounts, a clearing account, investments), net of what came back; {@code parts}
     * names the destination accounts, amount descending then name. In both views; a slice only in
     * Cash.
     */
    public record SetAside(long total, List<Part> parts) {}

    /**
     * The month against the liquid accounts' own rows (C-6), whatever the view: {@code liquidNet} is
     * the signed sum of every non-noop row on the liquid accounts dated in the month, synthetic legs
     * included (a wallet's ATM leg); {@code postedLeft} the Cash view's {@code left} without any due
     * projection or unrecorded cash; {@code settledWithoutFact} the signed sum of the month's paid
     * occurrences that carry no fact (a "Mark paid" with no statement row: spend negative, income
     * positive), which {@code postedLeft} counts but no row does; and
     * {@code gap = postedLeft − liquidNet − settledWithoutFact}, zero for a fully reconciled month
     * bar an occurrence-vs-fact cent.
     */
    public record Reconcile(long liquidNet, long postedLeft, long settledWithoutFact, long gap) {}
}
