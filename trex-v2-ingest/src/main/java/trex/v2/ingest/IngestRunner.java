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
        Parsed parsed = adapter.parse(content, fileName, accountRef);
        if (!parsed.clean()) {
            out.println("rejected: " + parsed.bad().size() + " bad row(s); nothing sent");
            for (BadRow bad : parsed.bad()) {
                out.printf("  %s:%d  %s '%s': %s%n", bad.file(), bad.line(), bad.field(), bad.value(),
                    bad.reason());
            }
            return BAD_ROWS;
        }
        Instant now = Instant.now();
        List<FactDraft> drafts = parsed.candidates().stream()
            .map(d -> d.withEvidence(evidenceId, adapter.parser(), now))
            .toList();
        if (drafts.isEmpty()) {
            out.println("nothing to send (" + parsed.skipped().size() + " skipped)");
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
                        return REJECTED;
                    }
                    default -> { }
                }
            }
        }
        out.printf("%s: %d appended, %d duplicate, %d flagged, evidence %s%n",
            accountRef, appended, duplicate, flagged, evidenceId);
        return OK;
    }
}
