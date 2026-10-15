package trex.v2.hub.api;

import java.time.LocalDate;
import java.util.List;

/**
 * The month's budget sheet (V2-SPEC.md §9.2, V2-BUDGET-VIEW-PLAN.md §4): Money In, Money Out and
 * Money Left Over as the month's <b>real flows</b> — every account's external rows and every
 * commitment's occurrences, whatever the account's {@code budget} flag — with a move between two of
 * your own accounts reported apart, never as income or spend.
 *
 * <p>A read of the derived index, over the same row-walk as {@code headroom}: the sheet's
 * {@code left} equals {@code /api/expected}'s {@code headroom.left} for the same month, so the two
 * can never drift. Every amount is a positive magnitude except {@code left} and a {@link Moved}'s
 * {@code net}, which are signed; {@code month} is the absolute {@code YYYY-MM}, unlike headroom's
 * "the month of {@code asOf"}.
 */
public record BudgetResponse(String month, LocalDate through, List<String> accounts,
                             Income income, Spend spend, Moved moved,
                             long missed, long unpaired, long left) {

    /**
     * Money In as a real flow. {@code posted} is income already seen — a commitment's {@code
     * occurred}/{@code settled} income occurrence, or an uncommitted credit (whatever the account,
     * including one flagged {@code budget: false}); {@code due} is income still expected at the
     * commitment's current price. {@code total} is the two summed; it holds no move. {@code lines}
     * is the posted income per category, amount descending then name; income is never grouped
     * (§11.2).
     */
    public record Income(long posted, long due, long total, List<Line> lines) {}

    /** One income category's posted amount (positive). */
    public record Line(String category, long amount) {}

    /**
     * Money Out as a real flow. {@code posted} is spend already seen — a commitment's paid
     * occurrence plus every uncommitted out row (whatever the account); {@code due} is a commitment
     * still owed at its current price; {@code unrecorded} is the month's unrecorded cash (§9.4),
     * signed, so a negative month reduces spend. {@code total} is the three summed; it holds no
     * move.
     *
     * <p>{@code slices} is the posted spend rolled into its primary group (§11.2; an unmapped or
     * blank category falls back to {@code Expense}), amount descending then name, followed by the
     * synthetic slices {@code Due} (committed-due, which carries no category) and {@code Unrecorded}
     * (the unrecorded figure), each only when non-zero. The group slices sum to {@code posted};
     * every slice sums to {@code total}. A move is never a slice — it lives in {@link Moved}.
     */
    public record Spend(long posted, long due, long unrecorded, long total, List<Slice> slices) {}

    /**
     * One Money Out slice: a primary group's posted spend, or the synthetic Due/Unrecorded. A group
     * slice carries its {@code parts} — the trex categories inside it (the distribution that made
     * the group up), amount descending then name — so the view can open a group into its parts; Due
     * and Unrecorded have none. The parts sum to the slice's {@code amount}.
     */
    public record Slice(String group, long amount, List<Part> parts) {

        /** A synthetic slice with no part distribution. */
        public Slice(String group, long amount) {
            this(group, amount, List.of());
        }
    }

    /**
     * One part of a slice: a trex category inside a group slice, or the account an {@code out} or
     * {@code in} move went to or came from. {@code label} is the category or the account ref; the
     * parts of one slice sum to its amount.
     */
    public record Part(String label, long amount) {}

    /**
     * Money moved between your own accounts, never income or spend (V2-SPEC.md §9.2). {@code out}
     * is what left a budget account for one of your own accounts outside it, {@code in} what
     * arrived in a budget account from one outside; {@code net} is {@code in − out} and moves
     * {@code left} exactly as far as the boundary was crossed. {@code outParts}/{@code inParts} name
     * the other account of each move (the destination/source), amount descending then name.
     * {@code outside} is the month's moves between two accounts that are <b>both</b> outside the
     * budget: they change {@code left} not at all and are reported per pair.
     */
    public record Moved(long out, long in, long net, List<Part> outParts, List<Part> inParts,
                        List<Outside> outside) {}

    /**
     * One move between two accounts that are <b>both</b> outside the budget
     * (V2-BUDGET-VIEW-PLAN.md D-3): the loan repayments, aggregated over the month by its pair.
     * {@code from} pays {@code to}; {@code amount} is the month's summed magnitude and {@code count}
     * the number of payments behind it. It never enters In/Out/Left, because it never crosses the
     * budget boundary.
     */
    public record Outside(String from, String to, long amount, int count) {}
}
