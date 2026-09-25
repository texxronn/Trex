package trex.category;

import trex.core.CanonicalEvent;

/**
 * One rule: a category plus a condition tree. SPEC §5.6.
 * A pin is an ordinary rule whose condition is an {@code externalId} leaf — one mechanism, not two.
 *
 * @param index    1-based position within its block, for explaining what fired
 * @param pin      true when it came from {@code pins.yaml}
 * @param category the category to assign; never a reserved name (checked at load)
 * @param comment  why this rule exists, or null. A field rather than a YAML {@code #} comment so
 *                 it survives a machine rewrite of the file and can be shown beside the category
 *                 it explains (§5.6) — rationale only a maintainer reading the file can see is
 *                 rationale the person asking "why is this GROCERIES?" never gets.
 * @param when     the condition; compiled at load, so matching does no parsing
 */
public record Rule(int index, boolean pin, String category, String comment, Condition when) {

    public boolean matches(CanonicalEvent line) {
        return when.test(line);
    }

    /** The same rule at a different position, for a dry run that has not chosen one yet. */
    public Rule withIndex(int index) {
        return new Rule(index, pin, category, comment, when);
    }

    /** "pin #2" / "rule #7", the entry's address in its own file. */
    public String where() {
        return (pin ? "pin #" : "rule #") + index;
    }
}
