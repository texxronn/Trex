package trex.v2.hub.api;

import java.util.List;

/** {@code GET /api/ingests}: the ingest history, paired from the log's markers (V2-PROPOSAL.md §12.6). */
public record IngestsResponse(List<IngestRow> rows) {

    public record IngestRow(String batch, String file, String evidenceId, String accountRef,
                            long nStart, long nEnd, Integer appended, Integer duplicate, Integer flagged,
                            String status, long startedMs, long completedMs) {}
}
