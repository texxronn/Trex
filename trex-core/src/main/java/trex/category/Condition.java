package trex.category;

import trex.core.CanonicalEvent;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A compiled condition over one journal line. Data, not an expression language (SPEC §5.6):
 * everything a rule can say is one of these cases, so a bad rule fails at load rather than at
 * row 12 000, and nothing from a config file is ever evaluated as code.
 * <p>
 * Sealed, so adding a leaf surfaces every site that must handle it.
 */
public sealed interface Condition {

    boolean test(CanonicalEvent line);

    /** Every child must match. */
    record All(List<Condition> of) implements Condition {
        @Override
        public boolean test(CanonicalEvent line) {
            return of.stream().allMatch(c -> c.test(line));
        }
    }

    /** At least one child must match. */
    record Any(List<Condition> of) implements Condition {
        @Override
        public boolean test(CanonicalEvent line) {
            return of.stream().anyMatch(c -> c.test(line));
        }
    }

    record Not(Condition of) implements Condition {
        @Override
        public boolean test(CanonicalEvent line) {
            return !of.test(line);
        }
    }

    /** The pin form: exact external ids, never a regex. */
    record ExternalId(Set<String> ids) implements Condition {
        @Override
        public boolean test(CanonicalEvent line) {
            return ids.contains(line.externalId());
        }
    }

    /**
     * Case-insensitive regex, {@code find} semantics like the transfer allowlist (§3.4).
     * {@code raw} is the verbatim field identity hashes; {@code cleaned} may evolve, so it is opt-in.
     */
    record Match(Pattern pattern, Target on) implements Condition {
        public enum Target { RAW, CLEANED }

        @Override
        public boolean test(CanonicalEvent line) {
            String text = on == Target.RAW ? line.rawDescription() : line.description();
            return text != null && pattern.matcher(text).find();
        }
    }

    /** Money in or out, from the sign of the amount. */
    record Direction(boolean in) implements Condition {
        @Override
        public boolean test(CanonicalEvent line) {
            return in ? line.amount() > 0 : line.amount() < 0;
        }
    }

    /** Either side of a transfer counts, so a rule can name one account and catch both directions. */
    record Accounts(Set<String> refs) implements Condition {
        @Override
        public boolean test(CanonicalEvent line) {
            return refs.contains(line.accountRef()) || refs.contains(line.toAccountRef());
        }
    }

    /** Inclusive bounds in cents, tested against the absolute amount. */
    record AmountRange(Long min, Long max) implements Condition {
        @Override
        public boolean test(CanonicalEvent line) {
            long amount = Math.abs(line.amount());
            return (min == null || amount >= min) && (max == null || amount <= max);
        }
    }
}
