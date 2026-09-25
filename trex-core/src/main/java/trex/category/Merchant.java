package trex.category;

import java.util.regex.Pattern;

/**
 * The merchant a bank line is about, with the per-transaction tail removed. SPEC §5.6.
 * <p>
 * Banks append things that differ on every row of the same shop — a receipt number, "Visa
 * Purchase", a padded column of branch text — so grouping on {@code rawDescription} produces one
 * group per transaction and a worklist nobody can act on. The stem is what makes "this merchant
 * appears 23 times" answerable, which is the number that decides rule-versus-pin (§5.7).
 * <p>
 * It is used for <em>grouping and display only</em>. Identity hashes {@code rawDescription}
 * verbatim (§2.4) and rules match against it; nothing here may feed either, or changing this
 * logic would re-mint ids.
 */
public final class Merchant {

    /**
     * Where the merchant ends and the per-transaction tail begins:
     * <ul>
     *   <li>{@code " - Visa Purchase"} / {@code " - Receipt …"} — ING's card and payment suffixes</li>
     *   <li>three or more spaces — a padded column, as in {@code "AMAZON AU RETAIL   SYDNEY"}</li>
     * </ul>
     * Three, not two: across the 666 distinct descriptions in a two-year journal, 414 contain a
     * run of three or more spaces (real padded columns) and 11 contain exactly two — and in all
     * eleven the two spaces are incidental, with the {@code " - Receipt"} rule already cutting
     * them correctly. A two-space threshold would split merchant names that merely contain a
     * double space.
     * <p>
     * Deliberately not stripped: trailing store numbers ({@code "WOOLWORTHS 1234"}), because
     * {@code "COSTCO 5"} and {@code "COSTCO"} can be different shops; and processor prefixes
     * ({@code "SQ *"}, {@code "COM*"}, {@code "PAYPAL *"}), because the merchant after the prefix
     * is often truncated to nothing useful and merging every PayPal charge into one group would
     * hide exactly what the worklist is for.
     */
    private static final Pattern TAIL = Pattern.compile(" - Visa Purchase| - Receipt| {3,}");

    private Merchant() {}

    /**
     * The grouping key for a raw bank description: the head before the tail, whitespace collapsed,
     * upper-cased. Upper-casing is what makes {@code "Piccolo Me"} and {@code "PICCOLO ME"} — the
     * same shop written two ways by two banks — one row in the worklist rather than two.
     */
    public static String stem(String rawDescription) {
        if (rawDescription == null) {
            return "";
        }
        // Strip first: a leading run of spaces is not a padded column, and cutting on it would
        // leave an empty stem. (Caught by MerchantTest, on a description that starts indented.)
        String head = TAIL.split(rawDescription.strip(), 2)[0];
        return head.replaceAll("\\s+", " ").strip().toUpperCase(java.util.Locale.ROOT);
    }
}
