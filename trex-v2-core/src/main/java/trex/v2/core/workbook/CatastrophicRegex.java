package trex.v2.core.workbook;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import trex.v2.core.config.Condition;

/**
 * A conservative detector for regexes with catastrophic-backtracking risk (V2-PROPOSAL.md §10.4):
 * a quantified group whose body is itself quantified ({@code (a+)+}), or repeated dot-stars
 * ({@code .*.*}). It is a lint warning, not a proof — false negatives are fine, false positives are
 * avoided so the report stays trustworthy.
 */
public final class CatastrophicRegex {

    private static final Pattern NESTED_QUANTIFIER =
        Pattern.compile("\\([^)]*[*+][^)]*\\)[*+]|\\([^)]*\\{\\d*,?\\d*\\}[^)]*\\)[*+]");
    private static final Pattern REPEATED_DOTSTAR =
        Pattern.compile("(\\.\\*){2,}|(\\.\\+){2,}");

    private CatastrophicRegex() {}

    public static boolean risky(String regex) {
        return regex != null
            && (NESTED_QUANTIFIER.matcher(regex).find() || REPEATED_DOTSTAR.matcher(regex).find());
    }

    /** Every regex source in a condition tree, for linting. */
    public static List<String> sources(Condition condition) {
        List<String> out = new ArrayList<>();
        collect(condition, out);
        return out;
    }

    private static void collect(Condition condition, List<String> out) {
        switch (condition) {
            case Condition.Match match -> out.add(match.pattern().pattern());
            case Condition.All all -> all.of().forEach(c -> collect(c, out));
            case Condition.Any any -> any.of().forEach(c -> collect(c, out));
            case Condition.Not not -> collect(not.of(), out);
            default -> {
                // ExternalId, Direction, Accounts, AmountRange carry no regex
            }
        }
    }
}
