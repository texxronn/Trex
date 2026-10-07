package trex.v2.sequencer.api;

/** A refused request (V2-PROPOSAL.md §6.7): a response describes an attempt, never a fact. */
public record ErrorResponse(String error) {}
