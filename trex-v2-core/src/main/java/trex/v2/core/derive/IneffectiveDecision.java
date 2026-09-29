package trex.v2.core.derive;

/**
 * A decision that derivation could not apply (V2-PROPOSAL.md §9.8, §6.8): it named an
 * unresolvable id, or a supersession cycle. Recorded, visible, revocable — never dropped.
 */
public record IneffectiveDecision(long decisionN, String action, String reason) {}
