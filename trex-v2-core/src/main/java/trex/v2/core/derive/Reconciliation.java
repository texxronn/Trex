package trex.v2.core.derive;

import trex.v2.core.Fact;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Order-independent per-account reconciliation over derived current facts (V2-PROPOSAL.md §15.10;
 * SPEC §7 test 6). The chain runs over {@code transaction} rows only (§6.9): a {@code noop} row's
 * edges and amount are not part of it, and the excluded rows are named in the result rather than
 * silently dropped. Transfer legs count like any other transaction; the v1 TRANSFER aggregate does
 * not exist in v2. Never guesses: a chain that does not close is unreconcilable.
 */
public final class Reconciliation {

    public enum Status {
        /** One opening, one closing, chain intact. */
        RECONCILED,
        /** The chain does not close on an account whose statements should make it: a FAULT. */
        BROKEN,
        /** A declared account: its chain has deliberate gaps; the gap is the finding (§6.1). */
        DECLARED,
        /** A clearing account: opening computed, closing declared, no chain (§6.10). */
        CLEARING
    }

    /**
     * @param gap        for a DECLARED account, value that moved between attestations and was never
     *                   recorded — the number the arrangement exists to produce, not an error.
     * @param exclusions the {@code noop} row ids on this account that the chain deliberately skips
     *                   (§6.9), sorted; named so the result is never silently clean.
     */
    public record AccountResult(String accountRef, Status status, long opening, long closing,
                                long sum, long gap, List<String> exclusions) {

        public AccountResult {
            exclusions = List.copyOf(exclusions);
        }

        public boolean reconcilable() {
            return status == Status.RECONCILED;
        }

        /** True when nothing is wrong. A DECLARED or CLEARING account is never wrong, only declared. */
        public boolean balances() {
            return status == Status.DECLARED || status == Status.CLEARING
                || (status == Status.RECONCILED && sum == closing - opening);
        }
    }

    private Reconciliation() {}

    /**
     * A declared account's synthetic wallet leg as a movement between counts (§6.7): an ATM
     * withdrawal or cash deposit is a transfer, not a fact, so it is not in the account's fact
     * list but must count against its gap. {@code amount} is the movement on the declared account
     * (the real leg's amount negated), at the real leg's {@code (date, n)}.
     */
    public record Movement(LocalDate date, long n, long amount) {}

    /**
     * A clearing account's result (§6.10): its opening is computed backwards from the movements
     * that matched it, its closing is declared, and it has no chain to break — never {@code BROKEN}.
     */
    public static AccountResult clearing(String accountRef, long opening, long closing) {
        return new AccountResult(accountRef, Status.CLEARING, opening, closing, 0, 0, List.of());
    }

    /**
     * @param transactions     the current posted facts (one per chain); noop and pending excluded
     * @param declaredAccounts refs whose balances arrive from a person, not a statement
     */
    public static Map<String, AccountResult> reconcile(List<Fact> transactions, Set<String> declaredAccounts) {
        return reconcile(transactions, List.of(), declaredAccounts);
    }

    /**
     * @param noopExclusions   the current {@code noop} facts, whose rows the chain skips by role
     *                         (§6.9); each is listed under its account in the result
     */
    public static Map<String, AccountResult> reconcile(List<Fact> transactions, List<Fact> noopExclusions,
                                                       Set<String> declaredAccounts) {
        return reconcile(transactions, noopExclusions, declaredAccounts, Map.of());
    }

    /**
     * @param synthetic        per declared account, its synthetic wallet legs as movements between
     *                         counts (§6.7); cleared accounts are unaffected, and an account absent
     *                         here reconciles over its facts alone
     */
    public static Map<String, AccountResult> reconcile(List<Fact> transactions, List<Fact> noopExclusions,
                                                       Set<String> declaredAccounts,
                                                       Map<String, List<Movement>> synthetic) {
        Map<String, List<Fact>> byAccount = new TreeMap<>();
        for (Fact f : transactions) {
            byAccount.computeIfAbsent(f.accountRef(), k -> new ArrayList<>()).add(f);
        }
        Map<String, List<String>> excluded = new TreeMap<>();
        for (Fact f : noopExclusions) {
            excluded.computeIfAbsent(f.accountRef(), k -> new ArrayList<>()).add(f.externalId());
        }
        excluded.values().forEach(Collections::sort);

        Map<String, AccountResult> results = new LinkedHashMap<>();
        byAccount.forEach((account, facts) -> {
            List<String> ex = excluded.getOrDefault(account, List.of());
            results.put(account, declaredAccounts.contains(account)
                ? reconcileDeclared(account, facts, ex, synthetic.getOrDefault(account, List.of()))
                : reconcileStatement(account, facts, ex));
        });
        // An account whose rows are all noop has no chain to break: report it reconciled, with its
        // exclusions listed, rather than absent from the run.
        excluded.forEach((account, ids) -> {
            if (!results.containsKey(account)) {
                results.put(account, new AccountResult(account, Status.RECONCILED, 0, 0, 0, 0, ids));
            }
        });
        return results;
    }

    private static AccountResult reconcileStatement(String account, List<Fact> facts, List<String> excluded) {
        Map<Long, Integer> diff = new HashMap<>();   // +1 per prev, -1 per balance
        long sum = 0;
        for (Fact f : facts) {
            long prev = f.balance() - f.amount();
            diff.merge(prev, 1, Integer::sum);
            diff.merge(f.balance(), -1, Integer::sum);
            sum += f.amount();
        }
        List<Long> openings = new ArrayList<>();
        List<Long> closings = new ArrayList<>();
        diff.forEach((value, count) -> {
            for (int i = 0; i < count; i++) {
                openings.add(value);
            }
            for (int i = 0; i < -count; i++) {
                closings.add(value);
            }
        });
        if (openings.size() != 1 || closings.size() != 1) {
            return new AccountResult(account, Status.BROKEN, 0, 0, sum, 0, excluded);
        }
        return new AccountResult(account, Status.RECONCILED, openings.getFirst(), closings.getFirst(),
            sum, 0, excluded);
    }

    /**
     * A declared account reconciles between attestations, not across the whole history. In v2 an
     * attestation is derived: a declared account's fact with {@code amount == 0} (§6.1). Each gap
     * is cash that moved and was never itemised; reporting it is the point. Its synthetic wallet
     * legs (§6.7) are movements too: they count in the interval like a non-zero fact, but they
     * never anchor it — only a count does.
     */
    private static AccountResult reconcileDeclared(String account, List<Fact> facts, List<String> excluded,
                                                   List<Movement> movements) {
        // Facts and movements share the (date, n) order; facts sort first on a tie (they cannot tie:
        // n is globally unique and a movement borrows a real leg's n, never a declared fact's).
        List<Point> points = new ArrayList<>(facts.size() + movements.size());
        for (Fact f : facts) {
            points.add(new Point(f.date(), f.n(), 0, f.amount(), f.amount() == 0 ? f.balance() : null));
        }
        for (Movement m : movements) {
            points.add(new Point(m.date(), m.n(), 1, m.amount(), null));
        }
        points.sort(Comparator.comparing(Point::date).thenComparingLong(Point::n)
            .thenComparingInt(Point::order));
        long sum = facts.stream().mapToLong(Fact::amount).sum();

        Long previous = null;
        long moved = 0;
        long gap = 0;
        long opening = 0;
        long closing = 0;
        for (Point p : points) {
            if (p.snap() == null) {
                moved += p.delta();
                continue;
            }
            if (previous != null) {
                gap += p.snap() - previous - moved;
            } else {
                opening = p.snap() - moved;
            }
            previous = p.snap();
            closing = p.snap();
            moved = 0;
        }
        return new AccountResult(account, Status.DECLARED, opening, closing, sum, gap, excluded);
    }

    /** One event in a declared account's chain: a fact or a movement; {@code snap} anchors a count. */
    private record Point(LocalDate date, long n, int order, long delta, Long snap) {}
}
