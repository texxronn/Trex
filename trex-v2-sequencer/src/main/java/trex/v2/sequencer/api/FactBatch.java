package trex.v2.sequencer.api;

import java.util.List;

/**
 * {@code POST /facts} body (V2-PROPOSAL.md §6.5, §6): {@code { allOrNone, source, target?, facts }}.
 * {@code source} is the writing process instance, exactly 8 chars and registered (§6);
 * {@code target} is the destination stream, absent for {@code none}.
 */
public record FactBatch(boolean allOrNone, String source, String target, List<FactDraft> facts) {

    /** Quick construction (tests): stamps a test source; production sends its own (§6). */
    public FactBatch(boolean allOrNone, List<FactDraft> facts) {
        this(allOrNone, "TST_0001", null, facts);
    }
}
