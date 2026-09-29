package trex.v2.ingest;

import trex.v2.log.EvidenceStore;

import java.io.PrintStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One feed tick (V2-PROPOSAL.md §12.2): read one page from the stored cursor, store each row as
 * evidence, post the facts day-atomically, and only then commit the cursor. A crash mid-page is
 * safe: the evidence hash makes the re-read a no-op.
 */
public final class FeedRunner {

    private FeedRunner() {}

    public static int run(FeedAdapter feed, CursorStore cursors, EvidenceStore evidence,
                          IngestClient client, PrintStream out) {
        String cursor = cursors.get(feed.sourceType());
        FeedAdapter.FeedPage page;
        try {
            page = feed.read(cursor);
        } catch (RuntimeException e) {
            out.println("feed read failed: " + e.getMessage());
            return IngestRunner.TRANSPORT;
        }
        if (page.records().isEmpty()) {
            out.println(feed.sourceType() + ": no new rows (cursor " + cursor + ")");
            return IngestRunner.OK;
        }
        Instant now = Instant.now();
        List<FactDraft> drafts = new ArrayList<>();
        for (FeedAdapter.FeedRecord record : page.records()) {
            String evidenceId = evidence.put(record.raw());
            drafts.add(record.draft().withEvidence(evidenceId, feed.parser(), now));
        }
        for (DayBatcher.Batch batch : DayBatcher.batch(drafts)) {
            IngestClient.BatchResponse response;
            try {
                response = client.postFacts(batch.facts(), true);
            } catch (IngestClient.IngestException e) {
                out.println("transport failure: " + e.getMessage());
                return IngestRunner.TRANSPORT;
            }
            for (IngestClient.RowResult result : response.results()) {
                if (IngestClient.REJECTED.equals(result.outcome())) {
                    out.printf("rejected by the sequencer: %s (%s)%n", result.ref(), result.reason());
                    return IngestRunner.REJECTED;
                }
            }
        }
        cursors.put(feed.sourceType(), page.nextCursor());
        out.printf("%s: %d row(s), cursor -> %s%n", feed.sourceType(), drafts.size(), page.nextCursor());
        return IngestRunner.OK;
    }
}
