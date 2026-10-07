package trex.v2.ingest;

import java.util.List;

/** The result of parsing one file: candidates, bad rows and skips. A non-empty bad list sends nothing. */
public record Parsed(List<FactDraft> candidates, List<BadRow> bad, List<SkippedRow> skipped) {

    public boolean clean() {
        return bad.isEmpty();
    }
}
