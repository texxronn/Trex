package trex.resolver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trex.core.CanonicalEvent;
import trex.core.EventState;
import trex.core.Flag;
import trex.core.Provenance;
import trex.core.TypeHint;
import trex.sequencer.journal.JsonlJournal;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JournalWatcherTest {

    @TempDir
    Path dir;

    static CanonicalEvent line(long n, String id, EventState state) {
        return new CanonicalEvent(n, id, "ing-savings", null, "AUD", LocalDate.parse("2026-06-01"), -500, 1000,
            "d " + id, "d " + id, TypeHint.WITHDRAWAL, null, null, null, state, null, List.of(), Provenance.BANK,
            "test", null, null, null, null, null, null, Instant.parse("2026-06-02T00:00:00Z"));
    }

    private final Clock clock = Clock.fixed(Instant.parse("2026-07-01T00:00:00Z"), ZoneOffset.UTC);

    private static List<String> ids(List<CanonicalEvent> lines) {
        return lines.stream().map(CanonicalEvent::externalId).toList();
    }

    @Test
    void foldsFromZeroThenTailsNewLinesAndIgnoresPartialTail() throws Exception {
        Path journal = dir.resolve("journal.jsonl");
        JournalWatcher w = new JournalWatcher(journal, clock);
        w.poll();
        assertEquals(0, w.status().view().held().size());
        assertNull(w.status().error());

        CanonicalEvent a = line(1, "a", EventState.HELD);
        CanonicalEvent b = line(2, "b", EventState.REVIEW);
        try (JsonlJournal j = new JsonlJournal(journal)) {
            j.appendBatch(List.of(a, b));
        }
        w.poll();
        assertEquals(List.of("a"), ids(w.status().view().held()));
        assertEquals(List.of("b"), ids(w.status().view().review()));
        long offset = w.status().offset();

        byte[] next = JsonlJournal.serialize(List.of(a.reappend(3, EventState.EXTERNAL, List.of(), "x")));
        Files.write(journal, Arrays.copyOf(next, 20), StandardOpenOption.APPEND);
        w.poll();
        assertEquals(offset, w.status().offset());
        assertEquals(List.of("a"), ids(w.status().view().held()));

        Files.write(journal, Arrays.copyOfRange(next, 20, next.length), StandardOpenOption.APPEND);
        w.poll();
        assertEquals(List.of(), ids(w.status().view().held()));
        assertEquals(3, w.status().view().highWaterN());
    }

    @Test
    void shrunkJournalIsRefoldedFromZero() throws Exception {
        Path journal = dir.resolve("journal.jsonl");
        try (JsonlJournal j = new JsonlJournal(journal)) {
            j.appendBatch(List.of(line(1, "a", EventState.HELD), line(2, "b", EventState.HELD)));
        }
        JournalWatcher w = new JournalWatcher(journal, clock);
        w.poll();
        assertEquals(2, w.status().view().held().size());

        Files.write(journal, JsonlJournal.serialize(List.of(line(1, "c", EventState.HELD))));
        w.poll();
        assertEquals(List.of("c"), ids(w.status().view().held()));
    }

    @Test
    void readFailureKeepsLastViewReportsErrorAndRefoldsLater() throws Exception {
        Path journal = dir.resolve("journal.jsonl");
        try (JsonlJournal j = new JsonlJournal(journal)) {
            j.appendBatch(List.of(line(1, "a", EventState.HELD)));
        }
        JournalWatcher w = new JournalWatcher(journal, clock);
        w.poll();
        Files.writeString(journal, "garbage\n", StandardOpenOption.APPEND);
        w.poll();
        assertNotNull(w.status().error());
        assertEquals(List.of("a"), ids(w.status().view().held()));

        byte[] good = JsonlJournal.serialize(List.of(line(1, "a", EventState.HELD), line(2, "b", EventState.HELD)));
        Files.write(journal, good);
        w.poll();
        assertNull(w.status().error());
        assertEquals(List.of("a", "b"), ids(w.status().view().held()));
    }

    @Test
    void fileChangeEventTriggersReadWithoutWaitingForFallbackPoll() throws Exception {
        Path journal = dir.resolve("journal.jsonl");
        java.util.concurrent.atomic.AtomicInteger changes = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.CountDownLatch seenLine = new java.util.concurrent.CountDownLatch(1);
        try (JournalWatcher w = new JournalWatcher(journal, clock)) {
            w.addListener(() -> {
                changes.incrementAndGet();
                if (w.status().view().highWaterN() == 1) {
                    seenLine.countDown();
                }
            });
            w.start(60_000);   // fallback far beyond the test's wait
            Thread.sleep(300); // let the initial fallback read and watch registration happen
            int before = changes.get();
            try (JsonlJournal j = new JsonlJournal(journal)) {
                j.appendBatch(List.of(line(1, "a", EventState.HELD)));
            }
            assertTrue(seenLine.await(5, java.util.concurrent.TimeUnit.SECONDS), "watch event should trigger a read");
            assertEquals(List.of("a"), ids(w.status().view().held()));
            assertTrue(changes.get() > before);
        }
    }

    @Test
    void listenersAreNotifiedOnlyOnChange() throws Exception {
        Path journal = dir.resolve("journal.jsonl");
        try (JsonlJournal j = new JsonlJournal(journal)) {
            j.appendBatch(List.of(line(1, "a", EventState.HELD)));
        }
        JournalWatcher w = new JournalWatcher(journal, clock);
        java.util.concurrent.atomic.AtomicInteger changes = new java.util.concurrent.atomic.AtomicInteger();
        w.addListener(changes::incrementAndGet);
        w.poll();
        w.poll();
        w.poll();
        assertEquals(1, changes.get());
    }

    @Test
    void flaggedDuplicateAppearsInReviewAndHeld() throws Exception {
        Path journal = dir.resolve("journal.jsonl");
        CanonicalEvent a = line(1, "a", EventState.HELD);
        try (JsonlJournal j = new JsonlJournal(journal)) {
            j.appendBatch(List.of(a, a.reappend(2, EventState.HELD, List.of(Flag.POTENTIAL_DUP), null)));
        }
        JournalWatcher w = new JournalWatcher(journal, clock);
        w.poll();
        assertEquals(List.of("a"), ids(w.status().view().held()));
        assertEquals(List.of("a"), ids(w.status().view().review()));
    }
}
