package trex.v2.sequencer.api;

import java.util.List;

/** {@code POST /facts} body (V2-PROPOSAL.md §6.5): {@code { allOrNone, facts: […] }}. */
public record FactBatch(boolean allOrNone, List<FactDraft> facts) {}
