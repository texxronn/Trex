package trex.sequencer.journal;

import trex.core.CanonicalEvent;

import java.util.List;
import java.util.stream.Stream;

/** Append-only, single-writer journal (the swap seam for JSONL↔SQLite-log later). SPEC §3.1. */
public interface Journal {

    /** Append a committed batch atomically. Returns the new head offset. */
    long appendBatch(List<CanonicalEvent> events);

    /** Replay complete records from a byte offset. Torn tail is ignored (not returned). */
    Stream<CanonicalEvent> replayFrom(long offset);

    long headOffset();
}
