package trex.category;

import trex.core.CanonicalEvent;

import java.util.List;
import java.util.Set;

/**
 * The resolution chain of SPEC §5.6: structural TRANSFER, then a pin, then the first matching
 * rule in file order, then UNCATEGORIZED.
 * <p>
 * Pure and deterministic: no I/O, no clock, no state. Patterns are already compiled
 * ({@link CategoryRules#load}), so categorizing is only matching.
 */
public record RuleCategorizer(List<String> declared, List<Rule> pins, List<Rule> rules) implements Categorizer {

    @Override
    public Categorized categorize(CanonicalEvent line, Set<String> transferIds) {
        if (transferIds.contains(line.externalId())) {
            return Categorized.structural();
        }
        for (Rule pin : pins) {
            if (pin.matches(line)) {
                return new Categorized(pin.category(), Categorized.Origin.PIN, pin);
            }
        }
        for (Rule rule : rules) {
            if (rule.matches(line)) {
                return new Categorized(rule.category(), Categorized.Origin.RULE, rule);
            }
        }
        return Categorized.none();
    }
}
