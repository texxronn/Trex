package trex.v2.core.derive;

import trex.v2.core.Clean;

import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The commitment rule convention (§2.2), shared by the matcher and the hub's activity read: a
 * case-insensitive find over {@link Clean#clean} with an optional account scope. One compiler for
 * both, so a rule can never mean two things in two places.
 */
public final class CommitmentRules {

    private CommitmentRules() {}

    /** Compile one rule with the categories.yaml convention; a bad regex is a caller error. */
    public static CompiledRule compile(CommitmentRule rule) {
        try {
            return new CompiledRule(rule, Pattern.compile(rule.match(),
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("commitment '" + rule.commitmentId()
                + "': bad regex '" + rule.match() + "': " + e.getDescription(), e);
        }
    }

    /** True when any compiled rule matches the fact fields (description, account; sign is the caller's). */
    public static boolean anyMatch(List<CompiledRule> rules, String rawDescription,
                                   String accountRef, long amount) {
        for (CompiledRule rule : rules) {
            if (rule.matches(rawDescription, accountRef, amount)) {
                return true;
            }
        }
        return false;
    }

    /** One rule with its compiled pattern. */
    public record CompiledRule(CommitmentRule rule, Pattern pattern) {

        public boolean matches(String rawDescription, String accountRef, long amount) {
            if (rule.accountRef() != null && !rule.accountRef().isBlank()
                && !rule.accountRef().equals(accountRef)) {
                return false;
            }
            return pattern.matcher(Clean.clean(rawDescription)).find();
        }

        boolean matches(CurrentFact fact) {
            return matches(fact.fact().rawDescription(), fact.fact().accountRef(),
                fact.fact().amount());
        }
    }
}
