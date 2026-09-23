package trex.category;

import trex.core.CanonicalEvent;

/**
 * One rule: a category plus a condition tree. SPEC §5.6.
 * A pin is an ordinary rule whose condition is an {@code externalId} leaf — one mechanism, not two.
 *
 * @param index    1-based position within its block, for explaining what fired
 * @param pin      true when it came from the {@code pins} block
 * @param category the category to assign; never a reserved name (checked at load)
 * @param when     the condition; compiled at load, so matching does no parsing
 */
public record Rule(int index, boolean pin, String category, Condition when) {

    public boolean matches(CanonicalEvent line) {
        return when.test(line);
    }
}
