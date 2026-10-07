package trex.v2.sequencer.api;

/** {@code GET /head} (V2-PROPOSAL.md §6.5): the highest {@code n} and the byte offset. */
public record HeadResponse(long n, long offset) {}
