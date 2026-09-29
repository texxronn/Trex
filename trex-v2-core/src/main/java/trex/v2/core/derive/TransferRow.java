package trex.v2.core.derive;

import java.time.Instant;

/**
 * A derived transfer (V2-PROPOSAL.md §7.2, §9.9.C.4): one row per pair, over resolved (current)
 * leg ids. {@code origin} is {@code derived} or {@code decision}; a decision pair carries its
 * {@code decisionN}. The id is minted with v1's rule over the legs' chain roots, so supersession
 * never moves it (§11).
 */
public record TransferRow(String transferId, String fromLeg, String toLeg, Confidence confidence,
                          String origin, Long decisionN, Instant matchedAt) {}
