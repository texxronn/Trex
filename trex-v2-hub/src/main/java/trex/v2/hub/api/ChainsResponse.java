package trex.v2.hub.api;

import java.util.List;

/**
 * {@code GET /api/chains}: the §6.9 balance check as an operator view — per account, the reconcile
 * status, the forks the chain does not close on, and a preview of marking each side {@code noop}.
 * Computed from the derivation on every read; nothing here is stored.
 */
public record ChainsResponse(List<AccountJson> accounts) {

    public record AccountJson(String accountRef, String status, boolean reconciled, long opening, long closing,
                              long sum, long gap, List<String> exclusions, List<ForkJson> forks,
                              List<PreviewJson> previews) {}

    /** A value two rows claim on the same side of the chain ({@code OPENING} or {@code CLOSING}). */
    public record ForkJson(long value, String side, List<String> externalIds) {}

    /** What the account would reconcile to if {@code externalId} were marked noop. */
    public record PreviewJson(String externalId, String status, boolean reconciled, long opening, long closing,
                              List<ForkJson> remainingForks) {}
}
