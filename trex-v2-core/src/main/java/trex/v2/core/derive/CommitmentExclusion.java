package trex.v2.core.derive;

/**
 * One effective exclusion (V2-COMMITMENT-EXCLUSIONS-PLAN.md §4): the person concluded the fact is
 * not part of the commitment, so the matcher never claims the pair — a pin falls through to the
 * rules and a rule scan skips it. The fact itself is untouched; latest effective decision wins per
 * pair.
 */
public record CommitmentExclusion(String commitmentId, String externalId, long decisionN) {}
