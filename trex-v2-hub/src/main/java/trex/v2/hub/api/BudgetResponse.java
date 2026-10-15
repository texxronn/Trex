package trex.v2.hub.api;

import java.time.LocalDate;
import java.util.List;

/**
 * The month's budget sheet (V2-SPEC.md §9.2, V2-BUDGET-VIEW-PLAN.md §4): Money In, Money Out and
 * Money Left Over over the {@code budget} accounts, each Money Out amount rolled into its primary
 * group (§11.2), plus the transfers between two accounts that are both outside the budget.
 *
 * <p>A read of the derived index, over the same row-walk as {@code headroom}: the sheet's
 * {@code left} equals {@code /api/expected}'s {@code headroom.left} for the same month, so the two
 * can never drift. Every amount is a positive magnitude except {@code left}; {@code month} is the
 * absolute {@code YYYY-MM}, unlike headroom's "the month of {@code asOf}".
 */
public record BudgetResponse(String month, LocalDate through, List<String> accounts,
                             Income income, Spend spend, List<Outside> outside,
                             long missed, long unpaired, long left) {

    /**
     * Money In. {@code posted} is income already seen — a commitment's {@code occurred}/{@code
     * settled} income occurrence, or an uncommitted credit; {@code due} is income still expected at
     * the commitment's current price; {@code movedIn} is money that arrived from one of your own
     * accounts outside the budget, a move and not income, reported apart. {@code total} is the three
     * summed. {@code lines} is the posted income per category, amount descending then name; income is
     * never grouped (§11.2), and {@code movedIn} is never a line.
     */
    public record Income(long posted, long due, long movedIn, long total, List<Line> lines) {}

    /** One income category's posted amount (positive). */
    public record Line(String category, long amount) {}

    /**
     * Money Out. {@code posted} is spend already seen — a commitment's paid occurrence plus every
     * uncommitted out row; {@code due} is a commitment still owed at its current price;
     * {@code unrecorded} is the month's unrecorded cash (§9.4), signed, so a negative month reduces
     * spend; {@code movedOut} is money that left a budget account for one of your own outside it, a
     * move and not spending; {@code total} is the four summed.
     *
     * <p>{@code slices} is the posted spend rolled into its primary group (§11.2; an unmapped or
     * blank category falls back to {@code Expense}), amount descending then name, followed by the
     * synthetic slices {@code Due} (committed-due, which carries no category), {@code Saved}
     * ({@code movedOut}) and {@code Unrecorded} (the unrecorded figure), each only when non-zero.
     * The group slices sum to {@code posted}; every slice sums to {@code total}.
     */
    public record Spend(long posted, long due, long unrecorded, long movedOut, long total,
                        List<Slice> slices) {}

    /** One Money Out slice: a primary group's posted spend, or the synthetic Due/Saved/Unrecorded. */
    public record Slice(String group, long amount) {}

    /**
     * One transfer between two accounts that are <b>both</b> outside the budget
     * (V2-BUDGET-VIEW-PLAN.md D-3): the loan repayments. Counted once per transfer, by its paying
     * leg, {@code from} paying {@code to}. It never enters In/Out/Left, because it never touches
     * the budget.
     */
    public record Outside(String from, String to, long amount) {}
}
