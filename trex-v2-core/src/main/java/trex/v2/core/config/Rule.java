package trex.v2.core.config;

/**
 * One category rule: a category plus a condition tree (V2-PROPOSAL.md §9.9.E). Rules are ordered;
 * the first match wins. A pin is a decision in v2, not a rule, so it is not modelled here.
 *
 * @param index    1-based position in {@code categories.yaml}, for explaining what fired
 * @param category the category to assign; never a reserved name (checked at load)
 * @param comment  why this rule exists, or null — a field so it survives a machine rewrite
 * @param when     the condition; compiled at load, so matching does no parsing
 */
public record Rule(int index, String category, String comment, Condition when) {

    public boolean matches(RuleSubject subject) {
        return when.test(subject);
    }

    public String where() {
        return "rule #" + index;
    }
}
