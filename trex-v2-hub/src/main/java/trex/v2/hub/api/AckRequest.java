package trex.v2.hub.api;

/** {@code POST /api/acks}: read ({@code ACK}) or release ({@code UNACK}) one row for one user. */
public record AckRequest(String user, String externalId, String action, String comment) {}
