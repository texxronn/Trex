package trex.v2.core.derive;

/**
 * One effective match rule of a declared commitment (V2-COMMITMENTS-PLAN.md §2.2, §2.7
 * {@code commitment_rule}): a regex over {@code clean(rawDescription)} with an optional account
 * scope, and the declaration decision that wrote it. Rules see core fact fields only —
 * description, account, sign and date — never a derived field, so a reflow can not move a
 * commitment. A re-declare replaces its own rule set (latest effective wins).
 */
public record CommitmentRule(String commitmentId, String match, String accountRef, Long decisionN) {}
