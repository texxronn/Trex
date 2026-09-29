package trex.v2.ingest;

import java.util.List;

/**
 * A feed is the same adapter contract with a cursor instead of a file (V2-PROPOSAL.md §12.2):
 * {@code read(cursor)} returns a page of raw provider payloads and the cursor to resume from.
 * Ingest is idempotent by evidence hash, so a re-read of a page appends nothing.
 */
public interface FeedAdapter {

    String sourceType();

    default String parser() {
        return sourceType() + "/1";
    }

    /** @param cursor the last committed cursor, or null for the beginning */
    FeedPage read(String cursor);

    /** One feed row: its raw provider payload (evidence) and the draft it parses to. */
    record FeedRecord(byte[] raw, FactDraft draft) {}

    record FeedPage(List<FeedRecord> records, String nextCursor) {}
}
