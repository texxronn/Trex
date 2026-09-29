package trex.v2.hub.api;

import java.time.Instant;

/**
 * One user's eyeball marker (V2-PROPOSAL.md §9.4): the stored hash and whether the period's content
 * still matches it for that user. {@code stale} is computed, never stored.
 */
public record AckJson(String user, String period, long throughN, String stateHash,
                      boolean stale, Instant ackedAt) {}
