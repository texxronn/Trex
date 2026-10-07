package trex.v2.core.derive;

/**
 * A current fact's derived category (V2-PROPOSAL.md §7.2 {@code category_current}, §9.9.E).
 * Never stored on the transaction; {@code STRUCTURAL} is the transfer structure, {@code PIN} a
 * decision, {@code RULE} the first matching rule, {@code NONE} {@code UNCATEGORIZED}.
 */
public record CategoryRow(String externalId, String category, CategoryOrigin origin, String ruleId) {}
