package trex.v2.core;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deterministic merchant keys used by derivation (V2-PROPOSAL.md §8.4, §9.9.C/D/F).
 *
 * <p>None of these feed identity: identity hashes {@code rawDescription} verbatim (§8.3). They
 * are part of {@code deriveVersion}, so tuning them is a reflow — it moves matching and review
 * items but never a fact or an id.
 *
 * <p>{@link #stem} is v1's frozen grouping key ({@code trex.category.Merchant.stem}), restated:
 * the head before the per-transaction tail ({@code " - Visa Purchase"}, {@code " - Receipt"}, a
 * run of 3+ spaces), whitespace-collapsed, upper-cased. It is the "same merchant" equality used
 * by pending settlement and {@code POTENTIAL_DUP}.
 *
 * <p>{@link #transferStem} additionally drops the transfer allowlist vocabulary and digit runs,
 * so {@code "Transfer to Savings 4321"} and {@code "Transfer from Savings 4321"} compare equal
 * for T2/T3 (§9.9.C).
 *
 * <p>{@link #similar} is the RESTATEMENT comparison (§8.4): payment-network noise stripped,
 * tokens of 3+ alphabetic characters, case-folded, compared as sets at the configured overlap.
 */
public final class MerchantStem {

    /**
     * Where the merchant ends and the per-transaction tail begins. Three spaces, not two: across
     * the 666 distinct descriptions in a two-year journal, 414 contain a run of three or more
     * spaces (real padded columns) and 11 contain exactly two — and in all eleven the two spaces
     * are incidental. (Measured fact; belongs where it is used.)
     */
    private static final Pattern TAIL = Pattern.compile(" - Visa Purchase| - Receipt| {3,}");

    /** Words that name the movement of a transfer rather than the counterparty (§9.9.C). */
    private static final Pattern TRANSFER_WORDS = Pattern.compile(
        "\\b(TRANSFER|INTERNAL|FAST|TO|FROM|MY|ACCOUNT|OSKO|PAYID|BPAY|BILL|PAYMENT|RECEIVED)\\b");

    /** Payment-network noise that says nothing about who was paid (§8.4). */
    private static final Set<String> PAYMENT_NOISE = Set.of(
        "VISA", "EFTPOS", "POS", "AUTHORISATION", "AUTHORIZATION", "DEBIT", "CREDIT",
        "PURCHASE", "CARD", "PAYMENT", "RECEIPT", "WITHDRAWAL", "DEPOSIT", "TRANSACTION");

    private static final Pattern DIGITS = Pattern.compile("\\b\\d+\\b");
    private static final Pattern NON_ALNUM = Pattern.compile("[^A-Z0-9]+");

    private MerchantStem() {}

    /** v1's frozen grouping key. */
    public static String stem(String rawDescription) {
        if (rawDescription == null) {
            return "";
        }
        String head = TAIL.split(rawDescription.strip(), 2)[0];
        return head.replaceAll("\\s+", " ").strip().toUpperCase(Locale.ROOT);
    }

    /** {@link #stem} with the transfer vocabulary and digit runs removed, for T2/T3 equality. */
    public static String transferStem(String rawDescription) {
        String s = TRANSFER_WORDS.matcher(stem(rawDescription)).replaceAll(" ");
        s = DIGITS.matcher(s).replaceAll(" ");
        return s.replaceAll("\\s+", " ").strip();
    }

    /**
     * Payment-network noise stripped, 3+ character alphabetic tokens, case-folded. Used by
     * {@link #similar} for RESTATEMENT.
     */
    public static Set<String> tokens(String rawDescription) {
        String cleaned = NON_ALNUM.matcher(stem(rawDescription).toUpperCase(Locale.ROOT)).replaceAll(" ");
        Set<String> out = new LinkedHashSet<>();
        for (String token : cleaned.split(" ")) {
            if (token.length() >= 3 && token.chars().allMatch(Character::isLetter) && !PAYMENT_NOISE.contains(token)) {
                out.add(token);
            }
        }
        return out;
    }

    /**
     * Deterministic text similarity: the overlap coefficient of the two token sets,
     * {@code |A ∩ B| / min(|A|, |B|)}, compared against {@code threshold}. Two empty sets are not
     * similar (nothing was said); one empty set is never similar.
     */
    public static boolean similar(String a, String b, double threshold) {
        Set<String> ta = tokens(a);
        Set<String> tb = tokens(b);
        if (ta.isEmpty() || tb.isEmpty()) {
            return false;
        }
        long intersection = ta.stream().filter(tb::contains).count();
        return (double) intersection / Math.min(ta.size(), tb.size()) >= threshold;
    }

    /** For diagnostics and tests: the ordered tokens, as a list. */
    public static java.util.List<String> tokenList(String rawDescription) {
        return Arrays.asList(tokens(rawDescription).toArray(String[]::new));
    }
}
