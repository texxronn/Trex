package trex.v2.hub.api;

import trex.v2.sequencer.api.DecisionDraft;

import java.util.List;

/**
 * {@code POST /api/decisions}: a decision batch plus the index {@code n} the client's view was
 * built at (V2-PROPOSAL.md §6.6). If the hub's view has moved past {@code asOfN} the request is a
 * {@code 409}; if {@code asOfN} is null the staleness check is skipped.
 */
public record DecisionRequest(Boolean allOrNone, Long asOfN, List<DecisionDraft> decisions) {}
