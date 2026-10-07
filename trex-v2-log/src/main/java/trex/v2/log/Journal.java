package trex.v2.log;

import trex.v2.core.LogLine;

import java.util.List;
import java.util.stream.Stream;

/**
 * The append-only log (V2-PROPOSAL.md §6). One batch is one atomic write plus one fsync; {@code n}
 * is assigned by the caller in append order. Open only after {@link Recovery} has truncated any
 * torn tail.
 */
public interface Journal extends AutoCloseable {

    /** Append a batch atomically and fsync; returns the new head offset. */
    long appendBatch(List<LogLine> lines);

    /** The byte offset of the end of the last appended record. */
    long headOffset();

    /** Replay complete records from a byte offset. */
    Stream<LogLine> replayFrom(long offset);

    @Override
    void close();
}
