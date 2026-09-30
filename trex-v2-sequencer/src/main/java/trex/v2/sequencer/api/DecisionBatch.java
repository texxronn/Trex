package trex.v2.sequencer.api;

import java.util.List;

/**
 * {@code POST /decisions} body: a batch, so closing a yearly backlog is one call (V2-PROPOSAL.md
 * §9.4). {@code allOrNone} mirrors the facts endpoint: when true, one bad row rejects the batch and
 * writes nothing. {@code source} is the writing process instance (§6).
 */
public record DecisionBatch(Boolean allOrNone, String source, String target, List<DecisionDraft> decisions) {

    public DecisionBatch(List<DecisionDraft> decisions) {
        this(null, "TST_0001", null, decisions);
    }

    public DecisionBatch(Boolean allOrNone, List<DecisionDraft> decisions) {
        this(allOrNone, "TST_0001", null, decisions);
    }
}
