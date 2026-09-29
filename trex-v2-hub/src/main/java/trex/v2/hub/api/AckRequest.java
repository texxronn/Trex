package trex.v2.hub.api;

/** {@code POST /api/acks}: close an eyeball period for one user (V2-PROPOSAL.md §9.4). */
public record AckRequest(String user, String period, String comment) {}
