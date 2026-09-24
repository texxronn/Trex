package trex.category;

import trex.journal.Yaml;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Loads {@code categories.yaml} and {@code pins.yaml} (SPEC §6) into a {@link RuleCategorizer}.
 * The sequencer never calls this: categories belong to journal consumers (§0.7).
 * <p>
 * Two files, because they are two different things: rules are ordered and general, pins are
 * unordered and exact. {@code categories.yaml} is read first — it owns the vocabulary, and a pin
 * naming a category nobody declared is an error against the file that failed to declare it.
 * <p>
 * Everything that can be wrong is wrong at load, with the entry named: an undeclared or reserved
 * category, an uncompilable regex, an empty condition, or an inverted amount range. A rule wholly
 * shadowed by an earlier one is a warning, not an error — it is usually a mistake, but a harmless one.
 */
public final class CategoryRules {

    private static final Logger log = LoggerFactory.getLogger(CategoryRules.class);

    private CategoryRules() {}

    /** {@code categories.yaml} as written; bound strictly, so an unknown or duplicated key fails here. */
    public record File(List<String> categories, List<RuleEntry> pins, List<RuleEntry> rules) {}

    /** {@code pins.yaml} as written. */
    public record PinsFile(List<RuleEntry> pins) {}

    /** One entry as written. Public because the writer (§5.7) composes these before rendering. */
    public record RuleEntry(String category, String comment, WhenEntry when) {}

    public record WhenEntry(List<WhenEntry> all, List<WhenEntry> any, WhenEntry not,
                     List<String> externalId, String match, String matchOn,
                     String direction, List<String> accounts, Long amountMin, Long amountMax) {}

    /** Rules only, no pins — for a fixture or a consumer that has none. */
    public static Categorizer load(Path categoriesFile) {
        return load(categoriesFile, null);
    }

    /**
     * @param pinsFile {@code pins.yaml}, or null. A missing file is not an error: pins are optional
     *                 by nature and the file does not exist until the first one is written (§5.7).
     */
    public static Categorizer load(Path categoriesFile, Path pinsFile) {
        File parsed = Yaml.read(categoriesFile, File.class);
        String where = categoriesFile.getFileName().toString();

        // The key still binds, so the migration gets a pointed message instead of Jackson's.
        if (parsed.pins() != null && !parsed.pins().isEmpty()) {
            throw new IllegalArgumentException(
                where + ": pins now live in their own file, pins.yaml (§6) — move the 'pins' block there");
        }

        if (parsed.categories() == null || parsed.categories().isEmpty()) {
            throw new IllegalArgumentException(where + ": 'categories' must declare at least one category");
        }
        Set<String> declared = new LinkedHashSet<>();
        for (String c : parsed.categories()) {
            if (c == null || c.isBlank()) {
                throw new IllegalArgumentException(where + ": a declared category is blank");
            }
            if (Categories.isReserved(c)) {
                throw new IllegalArgumentException(where + ": '" + c + "' is reserved and must not be declared");
            }
            if (!declared.add(c)) {
                throw new IllegalArgumentException(where + ": category '" + c + "' is declared twice");
            }
        }

        List<Rule> rules = compile(where, "rule", parsed.rules(), declared);
        List<Rule> pins = loadPins(pinsFile, declared);
        warnOnShadowedPins(pins);

        log.info("categories loaded: {} categories and {} rules from {}, {} pins from {}",
            declared.size(), rules.size(), categoriesFile, pins.size(),
            pinsFile == null ? "(none)" : pinsFile);
        return new RuleCategorizer(List.copyOf(declared), pins, rules);
    }

    /** Pins are validated against the vocabulary {@code categories.yaml} declares, never their own. */
    private static List<Rule> loadPins(Path pinsFile, Set<String> declared) {
        if (pinsFile == null || !java.nio.file.Files.exists(pinsFile)) {
            return List.of();
        }
        PinsFile parsed = Yaml.read(pinsFile, PinsFile.class);
        return compile(pinsFile.getFileName().toString(), "pin", parsed.pins(), declared);
    }

    /** Compile one entry as if it were at {@code index}, for a dry run that writes nothing. */
    public static Rule compileOne(String where, boolean pin, int index, RuleEntry entry, Set<String> declared) {
        return compile(where, pin ? "pin" : "rule", List.of(entry), declared).getFirst().withIndex(index);
    }

    private static List<Rule> compile(String where, String kind, List<RuleEntry> entries, Set<String> declared) {
        if (entries == null) {
            return List.of();
        }
        List<Rule> out = new ArrayList<>();
        int index = 0;
        for (RuleEntry e : entries) {
            index++;
            String at = where + " " + kind + " #" + index;
            if (e.category() == null || e.category().isBlank()) {
                throw new IllegalArgumentException(at + ": missing 'category'");
            }
            if (Categories.isReserved(e.category())) {
                throw new IllegalArgumentException(
                    at + ": '" + e.category() + "' is reserved — it comes from the journal, not from a rule");
            }
            if (!declared.contains(e.category())) {
                throw new IllegalArgumentException(
                    at + ": category '" + e.category() + "' is not declared in categories.yaml");
            }
            if (e.when() == null) {
                throw new IllegalArgumentException(at + ": missing 'when'");
            }
            String comment = e.comment() == null || e.comment().isBlank() ? null : e.comment().strip();
            out.add(new Rule(index, "pin".equals(kind), e.category(), comment, condition(at, e.when())));
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

    /** A pin listed twice for the same id can never fire the second time; say so at load. */
    private static void warnOnShadowedPins(List<Rule> pins) {
        Set<String> seen = new LinkedHashSet<>();
        for (Rule pin : pins) {
            if (pin.when() instanceof Condition.ExternalId(Set<String> ids)) {
                for (String id : ids) {
                    if (!seen.add(id)) {
                        log.warn("pins.yaml pin #{}: externalId {} is already pinned by an earlier pin and will never fire",
                            pin.index(), id);
                    }
                }
            }
        }
    }
}
