package trex.v2.hub.api;

import java.time.LocalDate;
import java.util.List;

/** {@code GET /api/ingests}: the ingest history, paired from the log's markers (V2-PROPOSAL.md §12.6). */
public record IngestsResponse(List<IngestRow> rows) {

    /**
     * One ingest batch. {@code latestTxnDate} is the account's frontier (V2-INGEST-FRONTIER-PLAN.md):
     * the newest transaction date already processed for the account, clamped to today, or null when
     * the account has no facts — the lower bound for requesting the next statement file.
     */
    public record IngestRow(String batch, String file, String evidenceId, String accountRef,
                            long nStart, long nEnd, Integer appended, Integer duplicate, Integer flagged,
                            String status, long startedMs, long completedMs, LocalDate latestTxnDate) {}
}
