package trex.v2.hub.api;

import java.time.Instant;

/**
 * One user's read marker on one row (V2-PROPOSAL.md §9.4): the stored hash and whether the row's
 * content still matches it for that user. {@code stale} is computed, never stored.
 */
public record AckJson(String user, String externalId, String stateHash, boolean stale, Instant ackedAt) {}
