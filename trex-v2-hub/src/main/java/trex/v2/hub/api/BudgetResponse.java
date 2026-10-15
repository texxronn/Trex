package trex.v2.hub.api;

import java.time.LocalDate;
import java.util.List;

/**
 * The month's budget sheet (V2-SPEC.md §9.2, V2-BUDGET-VIEW-PLAN.md §4): Money In, Money Out and
 * Money Left Over over the {@code budget} accounts, each Money Out amount rolled into its primary
 * group (§11.2), plus the two things the budget accounts cannot see — the transfers between two
 * accounts that are both outside it (the loan repayments) and those accounts' own external rows
 * (the loan interest, income paid into a savings account).
 *
 * <p>A read of the derived index, over the same row-walk as {@code headroom}: the sheet's
 * {@code left} equals {@code /api/expected}'s {@code headroom.left} for the same month, so the two
 * can never drift. Every amount is a positive magnitude except {@code left} and an {@code External}'s
 * amount, which is signed; {@code month} is the absolute {@code YYYY-MM}, unlike headroom's "the
 * month of {@code asOf"}.
 */
public record BudgetResponse(String month, LocalDate through, List<String> accounts,
                             Income income, Spend spend, List<Outside> outside,
                             List<External> outsideExternal,
                             long missed, long unpaired, long left) {

    /**
     * Money In. {@code posted} is income already seen — a commitment's {@code occurred}/{@code
     * settled} income occurrence, or an uncommitted credit; {@code due} is income still expected at
     * the commitment's current price; {@code movedIn} is money that arrived from one of your own
     * accounts outside the budget, a move and not income, reported apart. {@code total} is the three
     * summed. {@code lines} is the posted income per category, amount descending then name; income is
     * never grouped (§11.2), and {@code movedIn} is never a line. {@code movedInParts} is that move
     * split by the account it came from, amount descending then name, empty when nothing moved in.
     */
    public record Income(long posted, long due, long movedIn, long total, List<Line> lines,
                         List<Part> movedInParts) {}

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
     * synthetic slices {@code Due} (committed-due, which carries no category), {@code Moved out}
     * ({@code movedOut}, whose parts name the accounts it went to) and {@code Unrecorded} (the
     * unrecorded figure), each only when non-zero. The group slices sum to {@code posted}; every
     * slice sums to {@code total}.
     */
    public record Spend(long posted, long due, long unrecorded, long movedOut, long total,
                        List<Slice> slices) {}

    /**
     * One Money Out slice: a primary group's posted spend, or the synthetic
     * Due/Moved&nbsp;out/Unrecorded. A group slice carries its {@code parts} — the trex categories
     * inside it (the distribution that made the group up), amount descending then name — so the view
     * can open a group into its parts; the {@code Moved out} slice's parts name the accounts the
     * money went to, and Due/Unrecorded have none. The parts sum to the slice's {@code amount}.
     */
    public record Slice(String group, long amount, List<Part> parts) {

        /** A synthetic slice with no part distribution. */
        public Slice(String group, long amount) {
            this(group, amount, List.of());
        }
    }

    /**
     * One part of a slice: a trex category inside a group slice, or the account a {@code Moved out}
     * or {@code movedIn} move went to or came from. {@code label} is the category or the account ref;
     * the parts of one slice sum to its amount.
     */
    public record Part(String label, long amount) {}

    /**
     * One transfer between two accounts that are <b>both</b> outside the budget
     * (V2-BUDGET-VIEW-PLAN.md D-3): the loan repayments, aggregated over the month by its pair.
     * {@code from} pays {@code to}; {@code amount} is the month's summed magnitude and {@code count}
     * the number of payments behind it. It never enters In/Out/Left, because it never touches the
     * budget.
     */
    public record Outside(String from, String to, long amount, int count) {}

    /**
     * One external row on an account outside the budget (V2-BUDGET-VIEW-PLAN.md D-3): a fact that is
     * no transfer — the loan interest, income paid into a savings account, its interest earned —
     * aggregated over the month by {@code (account, category)}. {@code amount} is <b>signed</b>
     * (negative is money out). It never enters In/Out/Left: the budget accounts cannot see it, and
     * booking it would double-count what the outside account's statement already carries.
     */
    public record External(String account, String category, long amount) {}
}
