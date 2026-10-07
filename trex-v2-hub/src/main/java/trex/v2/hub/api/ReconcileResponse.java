package trex.v2.hub.api;

import java.util.List;

/** {@code GET /api/reconcile}: the §15.10 reconciliation run over derived state. */
public record ReconcileResponse(boolean ok, List<AccountJson> accounts) {

    /**
     * One account's check. {@code exclusions} names every {@code noop} row the chain skipped and
     * what classified it (§6.9) — the result is never silently clean.
     */
    public record AccountJson(String accountRef, String status, boolean reconcilable, boolean balances,
                              long opening, long closing, long sum, long gap, List<ExclusionJson> exclusions) {}

    /** An excluded noop row: its id, the classifier ({@code decision 42} or {@code profile}) and why. */
    public record ExclusionJson(String externalId, String classifiedBy, String reason) {}
}
