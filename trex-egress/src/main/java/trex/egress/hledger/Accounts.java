package trex.egress.hledger;

import trex.journal.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Where each trex account sits in the hledger account tree. SPEC §5.9.
 * <p>
 * The tree <em>is</em> the reporting model in plain-text accounting — {@code hledger balance
 * liabilities} works because the name says so — which is why this is config rather than a
 * convention baked into the code. It is optional: without it every account lands under
 * {@code assets:}, which is right for most of them and wrong for a credit card, and the file says
 * so in a comment rather than quietly misreporting net worth.
 * <p>
 * Separate from the Firefly egress's own mapping on purpose. That one exists because Firefly needs
 * to know an account's <em>type</em> to choose a transaction type; this one exists because hledger
 * needs a <em>name</em>. Sharing them would put one target's vocabulary in another's config.
 */
public record Accounts(Map<String, String> byRef, Set<String> income, String unresolved, String equity) {

    /** Where a movement goes when its contra is not yet known (a HELD or REVIEW row). */
    public static final String DEFAULT_UNRESOLVED = "assets:unresolved";

    /** Where the balance an account held before trex saw it comes from. hledger's own convention. */
    public static final String DEFAULT_EQUITY = "equity:opening-balances";

    record File(Map<String, String> accounts, List<String> income, String unresolved, String equity) {}

    public static Accounts none() {
        return new Accounts(Map.of(), Set.of(), DEFAULT_UNRESOLVED, DEFAULT_EQUITY);
    }

    public static Accounts load(Path file) {
        if (file == null || !Files.exists(file)) {
            return none();
        }
        File parsed = Yaml.read(file, File.class);
        Map<String, String> out = new LinkedHashMap<>();
        if (parsed.accounts() != null) {
            parsed.accounts().forEach((ref, name) -> {
                if (name == null || name.isBlank()) {
                    throw new IllegalArgumentException(
                        file.getFileName() + ": " + ref + " has no hledger account name");
                }
                out.put(ref, name.strip());
            });
        }
        Set<String> income = parsed.income() == null ? Set.of()
            : parsed.income().stream().map(c -> c.strip().toUpperCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new Accounts(Map.copyOf(out), income,
            parsed.unresolved() == null ? DEFAULT_UNRESOLVED : parsed.unresolved(),
            parsed.equity() == null ? DEFAULT_EQUITY : parsed.equity());
    }

    /**
     * {@code income:} or {@code expenses:} for a category.
     * <p>
     * Declared categories win, so a refund in a spending category stays a negative expense instead
     * of being counted as income. With nothing declared this falls back to the sign, which is
     * crude but complete — and the run warns, because the fallback files every refund under
     * {@code income:} and overstates both sides of every report.
     */
    public String top(String category, long amount) {
        if (!income.isEmpty()) {
            return income.contains(category == null ? "" : category.toUpperCase(Locale.ROOT))
                ? "income" : "expenses";
        }
        return amount < 0 ? "expenses" : "income";
    }

    /** The hledger account for a trex ref; {@code assets:<ref>} when nothing maps it. */
    public String of(String ref) {
        String mapped = byRef.get(ref);
        return mapped != null ? mapped : "assets:" + slug(ref);
    }

    public boolean maps(String ref) {
        return byRef.containsKey(ref);
    }

    /**
     * An account-name segment: lower case, spaces and punctuation to hyphens. Colons are stripped
     * rather than escaped, because a colon is hledger's tree separator and a merchant called
     * "SQ *FOO: BAR" must not silently become two levels of the tree.
     */
    public static String slug(String text) {
        if (text == null || text.isBlank()) {
            return "unknown";
        }
        String s = text.toLowerCase(java.util.Locale.ROOT)
            .replace(':', ' ')
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("(^-+)|(-+$)", "");
        return s.isBlank() ? "unknown" : s;
    }
}
