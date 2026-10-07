package trex.v2.hub.api;

/**
 * {@code POST /api/reflow/preview/transfers}: a candidate {@code transfers.yaml} to run without
 * saving (V2-PROPOSAL.md §9.3, §9.9.C).
 */
public record TransferRequest(String transfers) {}
