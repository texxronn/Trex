package trex.v2.sequencer.api;

import java.util.List;

/** {@code POST /decisions} body: a batch, so closing a yearly backlog is one call (V2-PROPOSAL.md §9.4). */
public record DecisionBatch(List<DecisionDraft> decisions) {}
