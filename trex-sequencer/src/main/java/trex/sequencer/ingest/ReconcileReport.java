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

    /**
     * One account's outcome. {@code balances} is derived, so clients need not recompute it.
     *
     * @param status {@code reconciled}, {@code broken}, or {@code declared} (§6). The third is not
     *               a failure: a declared account's chain has deliberate gaps, and {@code gap} is
     *               their total — value that moved and was never recorded.
     */
    public record Account(String accountRef, String status, boolean reconcilable, boolean balances,
                          long opening, long closing, long sum, long gap) {}

    /** Build from the reconciliation results of a snapshot at {@code n}/{@code offset}. */
    public static ReconcileReport of(long n, long offset, Map<String, Reconciliation.Result> results) {
        List<Account> accounts = results.values().stream()
            .map(r -> new Account(r.accountRef(),
                r.status().name().toLowerCase(java.util.Locale.ROOT),
                r.reconcilable(), r.balances(), r.opening(), r.closing(), r.sum(), r.gap()))
            .sorted(java.util.Comparator.comparing(Account::accountRef))
            .toList();
        // Vacuously true for an empty journal: nothing fails to balance.
        // "No account is BROKEN" — a declared account neither passes nor fails, so it cannot
        // leave the tripwire permanently red (§7 test 6).
        boolean ok = accounts.stream().allMatch(Account::balances);
        return new ReconcileReport(n, offset, ok, accounts);
    }
}
