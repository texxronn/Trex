package trex.v2.core.config;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The compiled category ruleset (V2-PROPOSAL.md §9.9.E). Pure: a load failure names the entry;
 * nothing here reads a file.
 *
 * <p>Reserved names ({@code TRANSFER}, {@code UNCATEGORIZED}) are never declared and never
 * assigned by a rule — they come from derivation, not from config.
 */
public record RuleSet(List<String> declared, List<Rule> rules) {

    public static final String TRANSFER = "TRANSFER";
    public static final String UNCATEGORIZED = "UNCATEGORIZED";
    private static final Set<String> RESERVED = Set.of(TRANSFER, UNCATEGORIZED);

    public RuleSet {
        declared = List.copyOf(declared);
        rules = List.copyOf(rules);
    }

    public static boolean isReserved(String category) {
        return category != null && RESERVED.contains(category);
    }

    public boolean isDeclared(String category) {
        return declared.contains(category);
    }

    public Optional<Rule> firstMatch(RuleSubject subject) {
        for (Rule rule : rules) {
            if (rule.matches(subject)) {
                return Optional.of(rule);
            }
        }
        return Optional.empty();
    }

    /** {@code categories.yaml} as written. Unknown/duplicated keys fail at bind time. */
    public record File(List<String> categories, List<RuleEntry> rules) {}
    public record RuleEntry(String category, String comment, WhenEntry when) {}

    public record WhenEntry(List<WhenEntry> all, List<WhenEntry> any, WhenEntry not,
                            List<String> externalId, String match, String matchOn,
                            String direction, List<String> accounts, Long amountMin, Long amountMax) {}

    /**
     * Validate and compile already-parsed rules. Pure. Everything that can be wrong is wrong here,
     * with the entry named: an undeclared or reserved category, an uncompilable regex, an empty
     * condition, an inverted amount range.
     */
    public static RuleSet compile(String where, File parsed) {
        if (parsed.categories() == null || parsed.categories().isEmpty()) {
            throw new IllegalArgumentException(where + ": 'categories' must declare at least one category");
        }
        Set<String> declared = new LinkedHashSet<>();
        for (String c : parsed.categories()) {
            if (c == null || c.isBlank()) {
                throw new IllegalArgumentException(where + ": a declared category is blank");
            }
            if (isReserved(c)) {
                throw new IllegalArgumentException(where + ": '" + c + "' is reserved and must not be declared");
            }
            if (!declared.add(c)) {
                throw new IllegalArgumentException(where + ": category '" + c + "' is declared twice");
            }
        }
        List<Rule> rules = compile(where, parsed.rules(), declared);
        return new RuleSet(List.copyOf(declared), rules);
    }

    private static List<Rule> compile(String where, List<RuleEntry> entries, Set<String> declared) {
        if (entries == null) {
            return List.of();
        }
        List<Rule> out = new ArrayList<>();
        int index = 0;
        for (RuleEntry e : entries) {
            index++;
            String at = where + " rule #" + index;
            if (e.category() == null || e.category().isBlank()) {
                throw new IllegalArgumentException(at + ": missing 'category'");
            }
            if (isReserved(e.category())) {
                throw new IllegalArgumentException(
                    at + ": '" + e.category() + "' is reserved — it comes from derivation, not from a rule");
            }
            if (!declared.contains(e.category())) {
                throw new IllegalArgumentException(
                    at + ": category '" + e.category() + "' is not declared in " + where);
            }
            if (e.when() == null) {
                throw new IllegalArgumentException(at + ": missing 'when'");
            }
            String comment = e.comment() == null || e.comment().isBlank() ? null : e.comment().strip();
            out.add(new Rule(index, e.category(), comment, condition(at, e.when())));
        }
        return List.copyOf(out);
    }

    /** One entry is exactly one condition: composing several in a single map would hide precedence. */
    private static Condition condition(String at, WhenEntry w) {
        List<Condition> built = new ArrayList<>();
        if (w.all() != null) {
            built.add(new Condition.All(children(at, w.all())));
        }
        if (w.any() != null) {
            built.add(new Condition.Any(children(at, w.any())));
        }
        if (w.not() != null) {
            built.add(new Condition.Not(condition(at, w.not())));
        }
        if (w.externalId() != null) {
            if (w.externalId().isEmpty()) {
                throw new IllegalArgumentException(at + ": 'externalId' is empty");
            }
            built.add(new Condition.ExternalId(Set.copyOf(w.externalId())));
        }
        if (w.match() != null) {
            built.add(match(at, w));
        } else if (w.matchOn() != null) {
            throw new IllegalArgumentException(at + ": 'matchOn' needs a 'match'");
        }
        if (w.direction() != null) {
            built.add(direction(at, w.direction()));
        }
        if (w.accounts() != null) {
            if (w.accounts().isEmpty()) {
                throw new IllegalArgumentException(at + ": 'accounts' is empty");
            }
            built.add(new Condition.Accounts(Set.copyOf(w.accounts())));
        }
        if (w.amountMin() != null || w.amountMax() != null) {
            if (w.amountMin() != null && w.amountMax() != null && w.amountMin() > w.amountMax()) {
                throw new IllegalArgumentException(
                    at + ": amountMin " + w.amountMin() + " is greater than amountMax " + w.amountMax());
            }
            built.add(new Condition.AmountRange(w.amountMin(), w.amountMax()));
        }
        if (built.isEmpty()) {
            throw new IllegalArgumentException(at + ": empty 'when' — a rule that matches everything is never what is meant");
        }
        return built.size() == 1 ? built.getFirst() : new Condition.All(List.copyOf(built));
    }

    private static List<Condition> children(String at, List<WhenEntry> entries) {
        if (entries.isEmpty()) {
            throw new IllegalArgumentException(at + ": 'all'/'any' needs at least one condition");
        }
        return entries.stream().map(e -> condition(at, e)).toList();
    }

    private static Condition match(String at, WhenEntry w) {
        Condition.Match.Target target = switch (w.matchOn() == null ? "raw" : w.matchOn()) {
            case "raw" -> Condition.Match.Target.RAW;
            case "description" -> Condition.Match.Target.CLEANED;
            default -> throw new IllegalArgumentException(
                at + ": matchOn must be 'raw' or 'description', not '" + w.matchOn() + "'");
        };
        try {
            return new Condition.Match(
                Pattern.compile(w.match(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE), target);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException(at + ": bad regex '" + w.match() + "': " + e.getDescription(), e);
        }
    }

    private static Condition direction(String at, String direction) {
        return switch (direction) {
            case "in" -> new Condition.Direction(true);
            case "out" -> new Condition.Direction(false);
            default -> throw new IllegalArgumentException(
                at + ": direction must be 'in' or 'out', not '" + direction + "'");
        };
    }
}
