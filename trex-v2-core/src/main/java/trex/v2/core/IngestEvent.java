package trex.v2.core;

import java.util.Objects;

/**
 * An ingest event: what an ingest did (V2-PROPOSAL.md §6, §12.6). A {@code start} opens the batch
 * and a {@code complete} closes it with counts and a status; the facts sit strictly between them, so
 * a batch's {@code n} range is the markers. This is bookkeeping about a process — not a fact about
 * the world and not a person's conclusion — and the third explicit kind in the log.
 */
public record IngestEvent(
    Envelope envelope,
    String phase,       // start | complete
    String batch,       // links the pair
    String evidence,    // nullable; the content-addressed source bytes
    String file,        // nullable; the original name
    String accountRef,  // nullable
    String sourceType,  // nullable
    String parser,      // nullable
    Integer appended,   // complete only
    Integer duplicate,
    Integer flagged,
    String status       // complete only: ok | bad_rows | transport | rejected | duplicate
) implements LogLine {

    /** The namespaced wire kind. */
    public static final String KIND = "trex.ingest";

    public static final String START = "start";
    public static final String COMPLETE = "complete";

    public IngestEvent {
        Envelope.require(envelope);
        Objects.requireNonNull(phase, "phase");
        if (!START.equals(phase) && !COMPLETE.equals(phase)) {
            throw new IllegalArgumentException("phase must be start or complete, not '" + phase + "'");
        }
        Objects.requireNonNull(batch, "batch");
    }

    public boolean isStart() {
        return START.equals(phase);
    }
}
