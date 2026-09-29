package trex.v2.core;

import java.util.regex.Pattern;

/**
 * Description cleaning: trim and collapse whitespace runs, nothing else (V2-PROPOSAL.md §6.1).
 * A pure function of {@code rawDescription}; it never affects identity, and it is applied by
 * derivation, so changing it is a reflow and not a re-import.
 */
public final class Clean {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private Clean() {}

    public static String clean(String rawDescription) {
        return WHITESPACE.matcher(rawDescription.strip()).replaceAll(" ");
    }
}
