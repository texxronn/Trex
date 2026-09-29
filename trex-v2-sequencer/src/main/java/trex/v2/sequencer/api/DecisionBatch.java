package trex.v2.sequencer.api;

import java.util.List;

/**
 * {@code POST /decisions} body: a batch, so closing a yearly backlog is one call (V2-PROPOSAL.md
 * §9.4). {@code allOrNone} mirrors the facts endpoint: when true, one bad row rejects the batch and
 * writes nothing.
 */
public record DecisionBatch(Boolean allOrNone, List<DecisionDraft> decisions) {

    public DecisionBatch(List<DecisionDraft> decisions) {
        this(null, decisions);
    }
}
