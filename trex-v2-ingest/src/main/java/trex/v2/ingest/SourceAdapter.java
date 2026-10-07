package trex.v2.ingest;

/**
 * One source adapter (V2-PROPOSAL.md §12.1). {@code parse} is pure and versioned: the same bytes and
 * the same parser give the same drafts, which is what makes {@code --reparse} reproducible.
 */
public interface SourceAdapter {

    String sourceType();

    /** The parser version stamped onto every fact, e.g. {@code ing-csv/1}. */
    default String parser() {
        return sourceType() + "/1";
    }

    Parsed parse(byte[] content, String fileName, String accountRef);
}
