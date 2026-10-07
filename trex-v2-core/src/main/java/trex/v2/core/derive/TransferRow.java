package trex.v2.core.derive;

import trex.v2.core.Rail;

import java.time.Instant;

/**
 * A derived transfer (V2-PROPOSAL.md §7.2, §9.9.C.4): one row per pair, over resolved (current)
 * leg ids. {@code origin} is {@code derived} or {@code decision}; a decision pair carries its
 * {@code decisionN}. The id is minted with v1's rule over the legs' chain roots, so supersession
 * never moves it (§11).
 *
 * <p>{@code method} is the payer leg's rail method, falling back to the payee's and then
 * {@code BANK_TRANSFER}. Direction is structural — from the negative leg to the positive one — so
 * nothing extra is stored.
 */
public record TransferRow(String transferId, String fromLeg, String toLeg, Confidence confidence,
                          String origin, Long decisionN, Rail method, Instant matchedAt) {}
