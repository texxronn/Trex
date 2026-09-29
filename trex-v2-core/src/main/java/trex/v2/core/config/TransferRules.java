package trex.v2.core.config;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The transfer-matching contract (V2-PROPOSAL.md §9.9.C, §9.9.D, §9.9.F). Loaded from
 * {@code transfers.yaml}; the loader compiles the allowlist regexes and applies the defaults.
 *
 * @param windowDays        T3 widens the date by at most this many days; it never widens the text
 * @param dupTolerance      cents; POTENTIAL_DUP needs {@code |Δamount| <= dupTolerance}
 * @param amountTolerance   cents; pending settlement needs {@code |Δamount| <= amountTolerance}
 * @param holdWindowDays    a HELD leg older than this opens UNMATCHED_LEG (measured on {@code asOf})
 * @param restatementOverlap token-set overlap for RESTATEMENT; deterministic, never a scoring library
 * @param allowlist         case-insensitive {@code find} patterns that make a description transfer-shaped
 */
public record TransferRules(int windowDays, int dupTolerance, int amountTolerance, int holdWindowDays,
                            double restatementOverlap, List<Pattern> allowlist) {

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
        allowlist = List.copyOf(allowlist);
        if (allowlist.isEmpty()) {
            throw new IllegalArgumentException("transfers.allowlist must declare at least one pattern");
        }
    }

    /** Defaults for the fields the proposal specifies (§12.7 of the plan): 4 / 0 / 0 / 30. */
    public static final int DEFAULT_WINDOW_DAYS = 4;
    public static final int DEFAULT_DUP_TOLERANCE = 0;
    public static final int DEFAULT_AMOUNT_TOLERANCE = 0;
    public static final int DEFAULT_HOLD_WINDOW_DAYS = 30;
    public static final double DEFAULT_RESTATEMENT_OVERLAP = 0.5;

    /** A ruleset with the proposal's defaults and the given allowlist patterns. */
    public static TransferRules defaults(List<String> allowlist) {
        List<Pattern> compiled = allowlist.stream()
            .map(s -> Pattern.compile(s, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE))
            .toList();
        return new TransferRules(DEFAULT_WINDOW_DAYS, DEFAULT_DUP_TOLERANCE, DEFAULT_AMOUNT_TOLERANCE,
            DEFAULT_HOLD_WINDOW_DAYS, DEFAULT_RESTATEMENT_OVERLAP, compiled);
    }

    /** A leg is transfer-shaped when its cleaned description matches any allowlist regex (§9.9.C). */
    public boolean isTransferShaped(String rawDescription) {
        String cleaned = trex.v2.core.Clean.clean(rawDescription);
        for (Pattern p : allowlist) {
            if (p.matcher(cleaned).find()) {
                return true;
            }
        }
        return false;
    }
}
