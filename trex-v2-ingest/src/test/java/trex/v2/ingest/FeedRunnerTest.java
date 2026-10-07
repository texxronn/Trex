package trex.v2.ingest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trex.v2.core.Observation;
import trex.v2.core.Provenance;
import trex.v2.log.EvidenceStore;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The feed tick (V2-PROPOSAL.md §12.2): a page at a time, cursor committed only after the posts. */
class FeedRunnerTest {

    private static FactDraft draft(String raw) {
        return new FactDraft("cdr-feed", LocalDate.of(2026, 9, 1), -100L, 0L, raw, null,
            Observation.POSTED, "cdr-feed", Provenance.BANK, null, null, null);
    }

    private static FeedAdapter.FeedRecord record(String id) {
        return new FeedAdapter.FeedRecord(("payload-" + id).getBytes(StandardCharsets.UTF_8), draft(id));
    }

    private static final class FakeFeed implements FeedAdapter {
        @Override
        public String sourceType() {
            return "cdr-feed";
        }

        @Override
        public FeedPage read(String cursor) {
            if (cursor == null) {
                return new FeedPage(List.of(record("a"), record("b")), "c1");
            }
            if (cursor.equals("c1")) {
                return new FeedPage(List.of(record("c")), "c2");
            }
            return new FeedPage(List.of(), "c2");
        }
    }

    @Test
    void readsAPageAndCommitsTheCursor(@TempDir Path dir) throws Exception {
        try (FakeSequencer sequencer = new FakeSequencer("Appended")) {
            EvidenceStore evidence = new EvidenceStore(dir.resolve("evidence"));
            FileCursorStore cursors = new FileCursorStore(dir.resolve("cursors"));
            FeedAdapter feed = new FakeFeed();
            java.io.PrintStream quiet = new java.io.PrintStream(OutputStream.nullOutputStream());

            assertEquals(IngestRunner.OK, FeedRunner.run(feed, cursors, evidence,
                new IngestClient(sequencer.url()), quiet));
            assertEquals(2, sequencer.factsSeen);
            assertEquals("c1", cursors.get("cdr-feed"));
            assertEquals(2, evidence.list().size());

            assertEquals(IngestRunner.OK, FeedRunner.run(feed, cursors, evidence,
                new IngestClient(sequencer.url()), quiet));
            assertEquals(3, sequencer.factsSeen);
            assertEquals("c2", cursors.get("cdr-feed"));

            assertEquals(IngestRunner.OK, FeedRunner.run(feed, cursors, evidence,
                new IngestClient(sequencer.url()), quiet));
            assertEquals(3, sequencer.factsSeen, "nothing new: no post");
        }
    }
}
