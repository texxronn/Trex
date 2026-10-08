package trex.v2.core.derive;

/**
 * One effective fact-to-commitment pin (V2-COMMITMENTS-PLAN.md §2.2, §2.5): the resolved form of a
 * {@code PIN_COMMITMENT}. Stage 3 folds the decisions and the supersession map, so the matcher
 * takes plain pairs and never reads a decision (§2.5: "pins first, then rules").
 *
 * <p>A pin names the fact and the commitment; it outranks the rules for that fact, but the
 * commitment's sign still applies — a pin can place a fact the rules miss, never one that moves
 * the wrong way.
 */
public record CommitmentPin(String externalId, String commitmentId) {}
