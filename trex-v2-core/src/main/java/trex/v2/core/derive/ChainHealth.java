package trex.v2.core.derive;

import trex.v2.core.Fact;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The balance check as a view over derived state (V2-PROPOSAL.md §6.9, §10.5): per account, whether
 * the chain closes and, when it does not, the <b>forks</b> — the balance values two rows contest —
 * and a per-row preview of marking one side {@code noop}. Nothing here is stored: it is recomputed
 * from the facts, so a fork appears the moment the head moves and clears when the chain closes.
 *
 * <p>The chain runs over {@code transaction} rows only; excluded {@code noop} rows are named by
 * {@link Reconciliation}. A fork is a value claimed by two rows as their previous balance
 * ({@link Side#OPENING}) or by two rows as their balance ({@link Side#CLOSING}). A disjoint chain
 * with no such double-claim is still {@code BROKEN}, just with no fork to name.
 */
public final class ChainHealth {

    /** Which edge of the chain a contested value sits on. */
    public enum Side {
        OPENING,
        CLOSING
    }

    /** A value two rows claim on the same side, with those rows' ids, sorted. */
    public record Fork(long value, Side side, List<String> externalIds) {
        public Fork {
            externalIds = List.copyOf(externalIds);
        }
    }

    /** One account's chain: its reconcile result plus the forks the exclusion does not explain. */
    public record Account(String accountRef, Reconciliation.Status status, long opening, long closing,
                          long sum, long gap, List<String> exclusions, List<Fork> forks) {
        public Account {
            exclusions = List.copyOf(exclusions);
            forks = List.copyOf(forks);
        }

        public boolean reconciled() {
            return status == Reconciliation.Status.RECONCILED;
        }
    }

    /** What the account would reconcile to if {@code externalId} were marked noop (writes nothing). */
    public record Preview(String externalId, Reconciliation.Status status, long opening, long closing,
                          List<Fork> remainingForks) {
        public Preview {
            remainingForks = List.copyOf(remainingForks);
        }

        public boolean reconciled() {
            return status == Reconciliation.Status.RECONCILED;
        }
    }

    private ChainHealth() {}

    /** Every account's chain, transaction accounts only, with excluded noop rows listed (§6.9). */
    public static Map<String, Account> of(List<Fact> transactions, List<Fact> noops, Set<String> declared) {
        Map<String, Reconciliation.AccountResult> base = Reconciliation.reconcile(transactions, noops, declared);
        Map<String, List<Fact>> byAccount = group(transactions);
        Map<String, Account> out = new LinkedHashMap<>();
        base.forEach((account, r) -> out.put(account, new Account(account, r.status(), r.opening(),
            r.closing(), r.sum(), r.gap(), r.exclusions(),
            declared.contains(account) ? List.of() : forks(byAccount.getOrDefault(account, List.of())))));
        return out;
    }

    /**
     * The account as it would reconcile with {@code externalId} removed from the chain (as a noop
     * would be), or null when the id is not a current transaction.
     */
    public static Preview preview(List<Fact> transactions, List<Fact> noops, Set<String> declared,
                                  String externalId) {
        List<Fact> remaining = new ArrayList<>();
        String account = null;
        for (Fact f : transactions) {
            if (f.externalId().equals(externalId)) {
                account = f.accountRef();
                continue;
            }
            remaining.add(f);
        }
        if (account == null) {
            return null;
        }
        String ref = account;
        Reconciliation.AccountResult r = Reconciliation.reconcile(remaining, noops, declared).get(ref);
        if (r == null) {
            r = new Reconciliation.AccountResult(ref, Reconciliation.Status.RECONCILED, 0, 0, 0, 0, List.of());
        }
        List<Fork> remainingForks = declared.contains(ref) ? List.of()
            : forks(remaining.stream().filter(f -> f.accountRef().equals(ref)).toList());
        return new Preview(externalId, r.status(), r.opening(), r.closing(), remainingForks);
    }

    private static List<Fork> forks(List<Fact> facts) {
        Map<Long, List<String>> previous = new TreeMap<>();
        Map<Long, List<String>> balance = new TreeMap<>();
        for (Fact f : facts) {
            previous.computeIfAbsent(f.balance() - f.amount(), k -> new ArrayList<>()).add(f.externalId());
            balance.computeIfAbsent(f.balance(), k -> new ArrayList<>()).add(f.externalId());
        }
        List<Fork> out = new ArrayList<>();
        previous.forEach((value, ids) -> {
            if (ids.size() >= 2) {
                Collections.sort(ids);
                out.add(new Fork(value, Side.OPENING, ids));
            }
        });
        balance.forEach((value, ids) -> {
            if (ids.size() >= 2) {
                Collections.sort(ids);
                out.add(new Fork(value, Side.CLOSING, ids));
            }
        });
        out.sort(Comparator.comparingLong(Fork::value).thenComparing(f -> f.side().name()));
        return out;
    }

    private static Map<String, List<Fact>> group(List<Fact> facts) {
        Map<String, List<Fact>> out = new TreeMap<>();
        for (Fact f : facts) {
            out.computeIfAbsent(f.accountRef(), k -> new ArrayList<>()).add(f);
        }
        return out;
    }
}
