package trex.v2.core.config;

import trex.v2.core.Clean;
import trex.v2.core.Rail;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The transfer-matching contract (V2-PROPOSAL.md §9.9.C, §9.9.D, §9.9.F). Loaded from
 * {@code transfers.yaml}; the loader compiles the regexes and applies the defaults.
 *
 * <p>{@code patterns} is the transfer vocabulary: per-account ordered lists, plus a {@code "*"}
 * default. A leg is judged only by the list of the account it sits in — its own entries first, then
 * the default — and the <b>first match wins</b>. The match decides both whether the leg shapes
 * (enters the matching pool) and its {@link Rail} method; a pattern with {@code shape: false} is
 * rail-only (tagged, never pooled). This is the ordering that separates "Osko to self" (a
 * transfer) from "Osko to anyone else" (an expense).
 *
 * @param windowDays        T3 widens the date by at most this many days; it never widens the text
 * @param dupTolerance      cents; POTENTIAL_DUP needs {@code |Δamount| <= dupTolerance}
 * @param amountTolerance   cents; pending settlement needs {@code |Δamount| <= amountTolerance}
 * @param holdWindowDays    a HELD leg older than this opens UNMATCHED_LEG (measured on {@code asOf})
 * @param restatementOverlap token-set overlap for RESTATEMENT; deterministic, never a scoring library
 * @param patterns          account ref (or {@link #ANY_ACCOUNT}) to its ordered patterns
 */
public record TransferRules(int windowDays, int dupTolerance, int amountTolerance, int holdWindowDays,
                            double restatementOverlap, Map<String, List<TransferPattern>> patterns) {

    /** The reserved key for patterns that apply to every account, tried after the account's own. */
    public static final String ANY_ACCOUNT = "default";

    /** One pattern: a case-insensitive {@code find} over the cleaned description, a rail and shape. */
    public record TransferPattern(String match, Pattern compiled, Rail rail, boolean shape) {}

    public TransferRules {
        if (windowDays < 0) {
            throw new IllegalArgumentException("transfers.windowDays must be >= 0");
        }
        if (dupTolerance < 0 || amountTolerance < 0 || holdWindowDays < 0) {
            throw new IllegalArgumentException("transfer tolerances must be >= 0");
        }
        if (restatementOverlap < 0 || restatementOverlap > 1) {
            throw new IllegalArgumentException("transfers.restatementOverlap must be in [0,1]");
        }
        Map<String, List<TransferPattern>> copy = new LinkedHashMap<>();
        patterns.forEach((account, list) -> copy.put(account, List.copyOf(list)));
        patterns = Map.copyOf(copy);
    }

    /** Defaults for the fields the proposal specifies (plan §12.7): 4 / 0 / 0 / 30. */
    public static final int DEFAULT_WINDOW_DAYS = 4;
    public static final int DEFAULT_DUP_TOLERANCE = 0;
    public static final int DEFAULT_AMOUNT_TOLERANCE = 0;
    public static final int DEFAULT_HOLD_WINDOW_DAYS = 30;
    public static final double DEFAULT_RESTATEMENT_OVERLAP = 0.5;

    /**
     * A ruleset with the proposal's defaults and a default-account pattern per string, shaping with
     * {@code BANK_TRANSFER}. The legacy allowlist shape, kept for tests and simple configs.
     */
    public static TransferRules defaults(List<String> allowlist) {
        List<TransferPattern> defaultPatterns = allowlist.stream()
            .map(s -> new TransferPattern(s,
                Pattern.compile(s, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE), Rail.BANK_TRANSFER, true))
            .toList();
        return new TransferRules(DEFAULT_WINDOW_DAYS, DEFAULT_DUP_TOLERANCE, DEFAULT_AMOUNT_TOLERANCE,
            DEFAULT_HOLD_WINDOW_DAYS, DEFAULT_RESTATEMENT_OVERLAP,
            defaultPatterns.isEmpty() ? Map.of() : Map.of(ANY_ACCOUNT, defaultPatterns));
    }

    /** The account's effective vocabulary: its own patterns first, then the default. */
    public List<TransferPattern> effective(String accountRef) {
        List<TransferPattern> out = new ArrayList<>();
        List<TransferPattern> own = patterns.get(accountRef);
        if (own != null) {
            out.addAll(own);
        }
        List<TransferPattern> def = patterns.get(ANY_ACCOUNT);
        if (def != null) {
            out.addAll(def);
        }
        return out;
    }

    /** The first pattern matching this account and cleaned description, or null. */
    public TransferPattern patternFor(String accountRef, String rawDescription) {
        String cleaned = Clean.clean(rawDescription);
        for (TransferPattern p : effective(accountRef)) {
            if (p.compiled().matcher(cleaned).find()) {
                return p;
            }
        }
        return null;
    }

    /** A leg is transfer-shaped when its account's first matching pattern says {@code shape: true}. */
    public boolean isTransferShaped(String accountRef, String rawDescription) {
        TransferPattern p = patternFor(accountRef, rawDescription);
        return p != null && p.shape();
    }

    /** The rail method the first matching pattern declares, or null when none matches (§9.9.C.4). */
    public Rail railFor(String accountRef, String rawDescription) {
        TransferPattern p = patternFor(accountRef, rawDescription);
        return p == null ? null : p.rail();
    }
}
