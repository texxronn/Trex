package trex.sequencer.ingest;

import java.util.regex.Pattern;

/** Description cleaning: trim and collapse whitespace runs. Never affects identity. SPEC §3.3. */
public final class DescriptionCleaner {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private DescriptionCleaner() {}

    public static String clean(String rawDescription) {
        return WHITESPACE.matcher(rawDescription.strip()).replaceAll(" ");
    }
}
