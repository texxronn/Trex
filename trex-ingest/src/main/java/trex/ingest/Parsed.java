package trex.ingest;

import trex.core.Candidate;

import java.util.List;

/**
 * A parser's result: every candidate, or (if anything was bad) none and every bad row.
 *
 * @param skipped rows the parser deliberately did not turn into candidates because the bank has
 *                not finalised them (SPEC §4, {@code bw-csv} pending authorisations). They do not
 *                make the file invalid, and they are reported so nothing is dropped in silence.
 */
public record Parsed(List<Candidate> candidates, List<BadRow> badRows, List<SkippedRow> skipped) {

    public Parsed(List<Candidate> candidates, List<BadRow> badRows) {
        this(candidates, badRows, List.of());
    }

    public boolean valid() {
        return badRows.isEmpty();
    }
}
