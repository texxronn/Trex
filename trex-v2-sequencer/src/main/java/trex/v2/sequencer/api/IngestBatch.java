package trex.v2.sequencer.api;

/**
 * {@code POST /ingest} body (V2-PROPOSAL.md §6, §12.6): one ingest event, {@code { source, target?,
 * phase, batch, … }}. {@code source} is the writing process instance, exactly 8 chars and
 * registered.
 */
public record IngestBatch(
    String source,
    String target,
    String phase,
    String batch,
    String evidence,
    String file,
    String account,
    String sourceType,
    String parser,
    Integer appended,
    Integer duplicate,
    Integer flagged,
    String status) {}
