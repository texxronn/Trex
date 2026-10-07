package trex.v2.hub.api;

import java.time.Instant;

/**
 * A derived transfer (V2-PROPOSAL.md §10.2): one row per pair, with confidence, origin and method.
 * {@code clearingAccount} is non-null when the pair is a real leg against a clearing account
 * (§6.10); one of {@code fromLeg}/{@code toLeg} is then that account ref.
 */
public record TransferJson(String transferId, String fromLeg, String toLeg, String confidence,
                           String origin, Long decisionN, String method, String clearingAccount,
                           Instant matchedAt) {}
