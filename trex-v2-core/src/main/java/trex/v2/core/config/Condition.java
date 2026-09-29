package trex.v2.core.config;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A compiled condition over one {@link RuleSubject} (V2-PROPOSAL.md §9.9.E; SPEC.md §5.6). Data,
 * not an expression language: everything a rule can say is one of these cases, so a bad rule fails
 * at load rather than at row 12 000, and nothing from a config file is ever evaluated as code.
 * Sealed, so adding a leaf surfaces every site that must handle it.
 */
public sealed interface Condition {

    boolean test(RuleSubject subject);

    /** Every child must match. */
    record All(List<Condition> of) implements Condition {
        @Override
        public boolean test(RuleSubject subject) {
            return of.stream().allMatch(c -> c.test(subject));
        }
    }

    /** At least one child must match. */
    record Any(List<Condition> of) implements Condition {
        @Override
        public boolean test(RuleSubject subject) {
            return of.stream().anyMatch(c -> c.test(subject));
        }
    }

    record Not(Condition of) implements Condition {
        @Override
        public boolean test(RuleSubject subject) {
            return !of.test(subject);
        }
    }

    /** The legacy pin form: exact external ids. Pins are decisions in v2; kept for the importer. */
    record ExternalId(Set<String> ids) implements Condition {
        @Override
        public boolean test(RuleSubject subject) {
            return ids.contains(subject.externalId());
        }
    }

    /** Case-insensitive regex, {@code find} semantics, on the raw or the cleaned description. */
    record Match(Pattern pattern, Target on) implements Condition {
        public enum Target { RAW, CLEANED }

        @Override
        public boolean test(RuleSubject subject) {
            String text = on == Target.RAW ? subject.rawDescription() : subject.cleanedDescription();
            return text != null && pattern.matcher(text).find();
        }
    }

    /** Money in or out, from the sign of the amount. */
    record Direction(boolean in) implements Condition {
        @Override
        public boolean test(RuleSubject subject) {
            return in ? subject.amount() > 0 : subject.amount() < 0;
        }
    }

    /** The account, by registry ref. */
    record Accounts(Set<String> refs) implements Condition {
        @Override
        public boolean test(RuleSubject subject) {
            return refs.contains(subject.accountRef());
        }
    }

    /** Inclusive bounds in cents, tested against the absolute amount. */
    record AmountRange(Long min, Long max) implements Condition {
        @Override
        public boolean test(RuleSubject subject) {
            long amount = Math.abs(subject.amount());
            return (min == null || amount >= min) && (max == null || amount <= max);
        }
    }
}
