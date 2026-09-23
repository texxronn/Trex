package trex.sequencer.ingest;

import java.util.List;
import java.util.regex.Pattern;

/** Phase-1 rule set inputs (transfers.yaml). SPEC §3.4, §6. */
public final class TransferRules {

    private final List<Pattern> allowlist;
    private final int windowDays;

    public TransferRules(List<String> allowlistRegexes, int windowDays) {
        if (windowDays < 0) {
            throw new IllegalArgumentException("windowDays must be >= 0");
        }
        this.allowlist = allowlistRegexes.stream()
            .map(r -> Pattern.compile(r, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE))
            .toList();
        this.windowDays = windowDays;
    }

    /** Transfer-shaped: rawDescription matches any allowlist regex (case-insensitive). */
    public boolean isTransferShaped(String rawDescription) {
        return rawDescription != null && allowlist.stream().anyMatch(p -> p.matcher(rawDescription).find());
    }

    public int windowDays() {
        return windowDays;
    }
}
