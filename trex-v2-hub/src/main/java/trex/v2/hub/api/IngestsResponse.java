package trex.v2.hub.api;

import java.time.LocalDate;
import java.util.List;

/** {@code GET /api/ingests}: the ingest history, paired from the log's markers (V2-PROPOSAL.md §12.6). */
public record IngestsResponse(List<IngestRow> rows) {

    /**
     * One ingest batch. {@code sourceType} is the adapter the batch was ingested with, from its
     * {@code trex.ingest start} marker — the Jobs page hands it back with a re-read so evidence can
     * never be paired with the wrong parser (QOL_Improvements.md §4).
     *
     * <p>{@code latestTxnDate} is the account's frontier (V2-INGEST-FRONTIER-PLAN.md): the newest
     * transaction date already processed for the account, clamped to today, or null when the
     * account has no facts — the lower bound for requesting the next statement file.
     */
    public record IngestRow(String batch, String file, String evidenceId, String accountRef, String sourceType,
                            long nStart, long nEnd, Integer appended, Integer duplicate, Integer flagged,
                            String status, long startedMs, long completedMs, LocalDate latestTxnDate) {}
}
