package trex.v2.core.derive;

/** The effective pin for one id (V2-PROPOSAL.md §7.2 {@code pin_current}, §9.9.E): the PIN event that won. */
public record PinRow(String externalId, String category, long decisionN, String userId, String comment) {}
