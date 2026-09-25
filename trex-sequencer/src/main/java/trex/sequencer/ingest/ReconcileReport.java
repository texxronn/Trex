package trex.sequencer.ingest;


import trex.core.state.Reconciliation;

import java.util.List;
import java.util.Map;

/**
 * Response of {@code GET /reconcile} (SPEC §3.5): the §7 test 6 algorithm run over the
 * published snapshot. {@code n} and {@code offset} say which journal point was reconciled,
 * so a result can be tied to a journal state rather than to a wall-clock moment.
 */
public record ReconcileReport(long n, long offset, boolean ok, List<Account> accounts) {

    /** One account's outcome. {@code balances} is derived, so clients need not recompute it. */
    public record Account(String accountRef, boolean reconcilable, boolean balances,
                          long opening, long closing, long sum) {}

    /** Build from the reconciliation results of a snapshot at {@code n}/{@code offset}. */
    public static ReconcileReport of(long n, long offset, Map<String, Reconciliation.Result> results) {
        List<Account> accounts = results.values().stream()
            .map(r -> new Account(r.accountRef(), r.reconcilable(), r.balances(),
                r.opening(), r.closing(), r.sum()))
            .sorted(java.util.Comparator.comparing(Account::accountRef))
            .toList();
        // Vacuously true for an empty journal: nothing fails to balance.
        boolean ok = accounts.stream().allMatch(Account::balances);
        return new ReconcileReport(n, offset, ok, accounts);
    }
}
