package trex.v2.core.workbook;

import trex.v2.core.config.Rule;
import trex.v2.core.config.RuleSet;
import trex.v2.core.config.RuleSubject;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The categorisation regression fixtures (V2-PROPOSAL.md §10.4): {@code categories.tests.yaml} is a
 * golden set, {@code description + amount -> expected category}, run on load and in CI. It tests the
 * rules, not decisions: pins are events and are not fixtures.
 */
public final class RuleFixtures {

    /** One golden case. {@code accountRef} is optional and unused unless a rule names an account. */
    public record Case(String description, long amount, String category, String accountRef) {}

    public record Failure(String description, long amount, String expected, String actual) {}

    private RuleFixtures() {}

    /** Empty when every case matches; one failure per mismatch, naming what happened. */
    public static List<Failure> check(RuleSet rules, List<Case> cases) {
        List<Failure> failures = new ArrayList<>();
        for (Case c : cases) {
            RuleSubject subject = RuleSubject.of("fixture", c.accountRef() == null ? "" : c.accountRef(),
                c.description(), c.amount());
            Optional<Rule> rule = rules.firstMatch(subject);
            String actual = rule.map(Rule::category).orElse(RuleSet.UNCATEGORIZED);
            if (!actual.equals(c.category())) {
                failures.add(new Failure(c.description(), c.amount(), c.category(), actual));
            }
        }
        return failures;
    }
}
