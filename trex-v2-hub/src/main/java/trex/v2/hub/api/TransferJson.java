package trex.v2.hub.api;

import java.time.Instant;

/** A derived transfer (V2-PROPOSAL.md §10.2): one row per pair, with confidence, origin and method. */
public record TransferJson(String transferId, String fromLeg, String toLeg, String confidence,
                           String origin, Long decisionN, String method, Instant matchedAt) {}
