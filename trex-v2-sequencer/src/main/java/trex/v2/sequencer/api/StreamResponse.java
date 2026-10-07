package trex.v2.sequencer.api;

/**
 * The result of a stream append (V2-PROPOSAL.md §14.1): how many lines landed, the new head, and —
 * when a line was refused — which {@code n} stopped it and why. A bad line stops the ingest at that
 * line; the prefix below it has already landed and is reported by {@link #appended()}.
 */
public record StreamResponse(long appended, long headN, Long stoppedAt, String error) {}
