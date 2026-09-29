package trex.v2.hub.api;

import java.util.List;

/** {@code GET /api/reconcile}: the §15.10 reconciliation run over derived state. */
public record ReconcileResponse(boolean ok, List<AccountJson> accounts) {

    public record AccountJson(String accountRef, String status, boolean reconcilable, boolean balances,
                              long opening, long closing, long sum, long gap) {}
}
