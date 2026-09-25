package trex.core.state;

import trex.core.CanonicalEvent;
import trex.core.TypeHint;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Order-independent per-account reconciliation over leg lines (first line per externalId,
 * TRANSFER lines excluded). SPEC §7 test 6. Never guesses: a broken chain is unreconcilable.
 */
public final class Reconciliation {

    /** What reconciling an account concluded. SPEC §7 test 6. */
    public enum Status {
        /** One opening, one closing, chain intact. */
        RECONCILED,
        /** The chain does not close on an account whose statements should make it: a FAULT. */
        BROKEN,
        /** A declared account (§6). Its chain has deliberate gaps; the gap is the finding. */
        DECLARED
    }

    /**
     * @param gap for a DECLARED account, the value that moved between attestations and was never
     *            recorded. It is the number the whole arrangement exists to produce, not an error.
     */
    public record Result(String accountRef, Status status, long opening, long closing, long sum, long gap) {

        public boolean reconcilable() {
            return status == Status.RECONCILED;
        }

        /** True when nothing is wrong. A DECLARED account is never wrong, only incomplete. */
        public boolean balances() {
            return status == Status.DECLARED || status == Status.RECONCILED && sum == closing - opening;
        }
    }

    private Reconciliation() {}

    public static Map<String, Result> reconcile(List<CanonicalEvent> journalLines) {
        return reconcile(journalLines, java.util.Set.of());
    }

    /**
     * @param declaredAccounts refs whose balances arrive from a person, not a statement (§6). A
     *                         {@code Set} rather than a registry lookup, so this stays pure: it is
     *                         data, not I/O, and the fold must remain deterministic.
     */
    public static Map<String, Result> reconcile(List<CanonicalEvent> journalLines,
                                                java.util.Set<String> declaredAccounts) {
        Map<String, CanonicalEvent> firstLines = new LinkedHashMap<>();
        for (CanonicalEvent l : journalLines) {
            if (l.typeHint() != TypeHint.TRANSFER) {
                firstLines.putIfAbsent(l.externalId(), l);
            }
        }
        Map<String, List<CanonicalEvent>> byAccount = new TreeMap<>();
        firstLines.values().forEach(l -> byAccount.computeIfAbsent(l.accountRef(), k -> new ArrayList<>()).add(l));

        Map<String, Result> results = new LinkedHashMap<>();
        byAccount.forEach((account, legs) -> results.put(account,
            declaredAccounts.contains(account) ? reconcileDeclared(account, legs)
                : reconcileAccount(account, legs)));
        return results;
    }

    private static Result reconcileAccount(String account, List<CanonicalEvent> legs) {
        Map<Long, Integer> diff = new HashMap<>();   // +1 per prev, -1 per balance
        long sum = 0;
        for (CanonicalEvent leg : legs) {
            long prev = leg.balance() - leg.amount();
            diff.merge(prev, 1, Integer::sum);
            diff.merge(leg.balance(), -1, Integer::sum);
            sum += leg.amount();
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
            return new Result(account, Status.BROKEN, 0, 0, sum, 0);
        }
        return new Result(account, Status.RECONCILED, openings.getFirst(), closings.getFirst(), sum, 0);
    }

    /**
     * A declared account reconciles <b>between attestations</b>, not across the whole history.
     * <p>
     * Only an {@code ATTESTATION} carries a balance here, so the chain is a series of islands with
     * deliberate gaps between them. Each gap is the value that moved and was never recorded —
     * cash spent without being itemised — and reporting it is the point. Calling that "broken"
     * would make {@code /reconcile} permanently red and destroy the tripwire for every account
     * that <em>can</em> be checked.
     */
    private static Result reconcileDeclared(String account, List<CanonicalEvent> legs) {
        List<CanonicalEvent> byDate = legs.stream()
            .sorted(java.util.Comparator.comparing(CanonicalEvent::date)
                .thenComparingLong(CanonicalEvent::n))
            .toList();
        long sum = byDate.stream().mapToLong(CanonicalEvent::amount).sum();

        Long previous = null;
        long moved = 0;
        long gap = 0;
        long opening = 0;
        long closing = 0;
        for (CanonicalEvent l : byDate) {
            if (l.typeHint() != TypeHint.ATTESTATION) {
                moved += l.amount();
                continue;
            }
            if (previous != null) {
                // What the attestations say moved, less what was actually recorded.
                gap += l.balance() - previous - moved;
            } else {
                opening = l.balance() - moved;
            }
            previous = l.balance();
            closing = l.balance();
            moved = 0;
        }
        return new Result(account, Status.DECLARED, opening, closing, sum, gap);
    }
}
