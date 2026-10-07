package trex.v2.core.derive;

import trex.v2.core.Fact;

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
                ? reconcileDeclared(account, facts, ex)
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
     * is cash that moved and was never itemised; reporting it is the point.
     */
    private static AccountResult reconcileDeclared(String account, List<Fact> facts, List<String> excluded) {
        List<Fact> byDate = facts.stream()
            .sorted(Comparator.comparing(Fact::date).thenComparingLong(Fact::n))
            .toList();
        long sum = byDate.stream().mapToLong(Fact::amount).sum();

        Long previous = null;
        long moved = 0;
        long gap = 0;
        long opening = 0;
        long closing = 0;
        for (Fact f : byDate) {
            if (f.amount() != 0) {
                moved += f.amount();
                continue;
            }
            if (previous != null) {
                gap += f.balance() - previous - moved;
            } else {
                opening = f.balance() - moved;
            }
            previous = f.balance();
            closing = f.balance();
            moved = 0;
        }
        return new AccountResult(account, Status.DECLARED, opening, closing, sum, gap, excluded);
    }
}
