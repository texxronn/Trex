package trex.v2.ingest;

/**
 * Where a feed's cursor lives (V2-PROPOSAL.md §12.2). The durable home is the index's
 * {@code source_cursor} table; a {@link FileCursorStore} is the offline spelling of the same seam.
 */
public interface CursorStore {

    String get(String source);

    void put(String source, String cursor);
}
