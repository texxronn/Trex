package trex.v2.hub.api;

import java.time.Instant;

/** A derived transfer (V2-PROPOSAL.md §10.2): one row per pair, with confidence and origin. */
public record TransferJson(String transferId, String fromLeg, String toLeg, String confidence,
                           String origin, Long decisionN, Instant matchedAt) {}
