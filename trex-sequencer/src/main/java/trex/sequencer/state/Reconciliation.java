package trex.sequencer.state;

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

    public record Result(String accountRef, boolean reconcilable, long opening, long closing, long sum) {

        public boolean balances() {
            return reconcilable && sum == closing - opening;
        }
    }

    private Reconciliation() {}

    public static Map<String, Result> reconcile(List<CanonicalEvent> journalLines) {
        Map<String, CanonicalEvent> firstLines = new LinkedHashMap<>();
        for (CanonicalEvent l : journalLines) {
            if (l.typeHint() != TypeHint.TRANSFER) {
                firstLines.putIfAbsent(l.externalId(), l);
            }
        }
        Map<String, List<CanonicalEvent>> byAccount = new TreeMap<>();
        firstLines.values().forEach(l -> byAccount.computeIfAbsent(l.accountRef(), k -> new ArrayList<>()).add(l));

        Map<String, Result> results = new LinkedHashMap<>();
        byAccount.forEach((account, legs) -> results.put(account, reconcileAccount(account, legs)));
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
            return new Result(account, false, 0, 0, sum);
        }
        return new Result(account, true, openings.getFirst(), closings.getFirst(), sum);
    }
}
