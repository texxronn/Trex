package trex.v2.ingest;

import trex.v2.log.EvidenceStore;

import java.io.PrintStream;
import java.time.Instant;
import java.util.List;

/**
 * One ingest run (V2-PROPOSAL.md §6.5, §12.1): store the evidence, parse and validate the whole
 * file, then send it one day at a time. Nothing is sent until every row is clean. Exit codes are
 * v1's contract: 0 success, 1 a bad row (nothing sent), 2 transport failure, 3 a rejected batch,
 * 64 usage.
 */
public final class IngestRunner {

    public static final int OK = 0;
    public static final int BAD_ROWS = 1;
    public static final int TRANSPORT = 2;
    public static final int REJECTED = 3;
    public static final int USAGE = 64;

    private IngestRunner() {}

    public static int run(SourceAdapter adapter, byte[] content, String fileName, String accountRef,
                          EvidenceStore evidence, IngestClient client, PrintStream out) {
        String evidenceId = evidence.put(content);
        // The stream is self-documenting (§12.6): a start, then the facts, then a complete. Facts sit
        // strictly between the markers, so the batch's n range is the markers themselves.
        String batchId = java.util.UUID.randomUUID().toString();
        try {
            client.postIngest(startEvent(batchId, evidenceId, fileName, accountRef, adapter));
        } catch (IngestClient.IngestException e) {
            out.println("transport failure: " + e.getMessage());
            return TRANSPORT;
        }
        Parsed parsed = adapter.parse(content, fileName, accountRef);
        if (!parsed.clean()) {
            out.println("rejected: " + parsed.bad().size() + " bad row(s); nothing sent");
            for (BadRow bad : parsed.bad()) {
                out.printf("  %s:%d  %s '%s': %s%n", bad.file(), bad.line(), bad.field(), bad.value(),
                    bad.reason());
            }
            client.postIngest(completeEvent(batchId, "bad_rows", 0, 0, 0));
            return BAD_ROWS;
        }
        Instant now = Instant.now();
        List<FactDraft> drafts = parsed.candidates().stream()
            .map(d -> d.withEvidence(evidenceId, adapter.parser(), now))
            .toList();
        if (drafts.isEmpty()) {
            out.println("nothing to send (" + parsed.skipped().size() + " skipped)");
            client.postIngest(completeEvent(batchId, "duplicate", 0, 0, 0));
            return OK;
        }
        int appended = 0;
        int duplicate = 0;
        int flagged = 0;
        for (DayBatcher.Batch batch : DayBatcher.batch(drafts)) {
            IngestClient.BatchResponse response;
            try {
                response = client.postFacts(batch.facts(), true);
            } catch (IngestClient.IngestException e) {
                // A transport failure leaves the batch open (dangling start): the truth.
                out.println("transport failure: " + e.getMessage());
                return TRANSPORT;
            }
            for (IngestClient.RowResult result : response.results()) {
                switch (result.outcome()) {
                    case IngestClient.APPENDED -> appended++;
                    case IngestClient.DUPLICATE -> duplicate++;
                    case IngestClient.FLAGGED -> flagged++;
                    case IngestClient.REJECTED -> {
                        out.printf("rejected by the sequencer: %s (%s)%n", result.ref(), result.reason());
                        client.postIngest(completeEvent(batchId, "rejected", appended, duplicate, flagged));
                        return REJECTED;
                    }
                    default -> { }
                }
            }
        }
        client.postIngest(completeEvent(batchId, "ok", appended, duplicate, flagged));
        out.printf("%s: %d appended, %d duplicate, %d flagged, evidence %s%n",
            accountRef, appended, duplicate, flagged, evidenceId);
        return OK;
    }

    private static java.util.Map<String, Object> startEvent(String batchId, String evidenceId,
                                                            String fileName, String accountRef,
                                                            SourceAdapter adapter) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("phase", "start");
        m.put("batch", batchId);
        m.put("evidence", evidenceId);
        m.put("file", fileName);
        m.put("account", accountRef);
        m.put("sourceType", adapter.sourceType());
        m.put("parser", adapter.parser());
        return m;
    }

    private static java.util.Map<String, Object> completeEvent(String batchId, String status,
                                                               int appended, int duplicate, int flagged) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("phase", "complete");
        m.put("batch", batchId);
        m.put("status", status);
        m.put("appended", appended);
        m.put("duplicate", duplicate);
        m.put("flagged", flagged);
        return m;
    }
}
