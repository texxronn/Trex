package trex.egress.archive;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trex.core.CanonicalEvent;
import trex.core.EventState;
import trex.core.Provenance;
import trex.core.TypeHint;
import trex.sequencer.journal.JsonlJournal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** SPEC §7 test 8 for the archive follower. */
class ArchiveFollowerTest {

    @TempDir
    Path dir;

    static CanonicalEvent line(long n) {
        return new CanonicalEvent(n, "ext" + n, "ing-savings", null, "AUD", LocalDate.parse("2026-06-01"), -100 * n,
            1000 - 100 * n, "row " + n, "row " + n, TypeHint.WITHDRAWAL, null, null, null, EventState.EXTERNAL, null,
            List.of(), Provenance.BANK, "test", null, null, null, null, null, null, Instant.parse("2026-06-02T00:00:00Z"));
    }

    static List<CanonicalEvent> lines(long fromN, long toN) {
        return LongStream.rangeClosed(fromN, toN).mapToObj(ArchiveFollowerTest::line).toList();
    }

    @Test
    void resumesAtPersistedOffsetWithoutGapOrDuplicate() throws IOException {
        Path journal = dir.resolve("journal.jsonl");
        Path archive = dir.resolve("archive.jsonl");
        try (JsonlJournal j = new JsonlJournal(journal)) {
            j.appendBatch(lines(1, 5));
        }
        // a partial line the writer has not finished yet
        byte[] next = JsonlJournal.serialize(lines(6, 6));
        Files.write(journal, Arrays.copyOf(next, 40), StandardOpenOption.APPEND);

        assertEquals(5, new ArchiveFollower(journal, archive).pass());
        long offset = Long.parseLong(Files.readString(dir.resolve("archive.jsonl.offset")).strip());
        assertEquals(JsonlJournal.serialize(lines(1, 5)).length, offset);

        // "kill" the follower: a new instance resumes; the writer finishes the line and appends more
        Files.write(journal, Arrays.copyOfRange(next, 40, next.length), StandardOpenOption.APPEND);
        Files.write(journal, JsonlJournal.serialize(lines(7, 9)), StandardOpenOption.APPEND);
        assertEquals(4, new ArchiveFollower(journal, archive).pass());
        assertArrayEquals(Files.readAllBytes(journal), Files.readAllBytes(archive));
    }

    @Test
    void followWakesOnJournalChangeWithoutWaitingForFallback() throws Exception {
        Path journal = dir.resolve("journal.jsonl");
        Path archive = dir.resolve("archive.jsonl");
        try (JsonlJournal j = new JsonlJournal(journal)) {
            j.appendBatch(lines(1, 2));
        }
        java.util.concurrent.atomic.AtomicInteger archived = new java.util.concurrent.atomic.AtomicInteger();
        Thread follower = Thread.ofVirtual().start(() -> {
            try {
                new ArchiveFollower(journal, archive).follow(60_000, archived::addAndGet);
            } catch (InterruptedException | IOException e) {
                // stopped
            }
        });
        try {
            long deadline = System.currentTimeMillis() + 5_000;
            while (archived.get() < 2 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertEquals(2, archived.get());
            Files.write(journal, JsonlJournal.serialize(lines(3, 4)), StandardOpenOption.APPEND);
            deadline = System.currentTimeMillis() + 5_000;
            while (archived.get() < 4 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertEquals(4, archived.get(), "woken by the change event, far before the 60 s fallback");
            assertArrayEquals(Files.readAllBytes(journal), Files.readAllBytes(archive));
        } finally {
            follower.interrupt();
            follower.join(5_000);
        }
    }

    @Test
    void crashAfterArchiveWriteBeforeOffsetPersistDoesNotDuplicate() throws IOException {
        Path journal = dir.resolve("journal.jsonl");
        Path archive = dir.resolve("archive.jsonl");
        try (JsonlJournal j = new JsonlJournal(journal)) {
            j.appendBatch(lines(1, 3));
        }
        new ArchiveFollower(journal, archive).pass();

        Files.write(journal, JsonlJournal.serialize(lines(4, 6)), StandardOpenOption.APPEND);
        // simulated crash: line 4 reached the archive (plus a torn piece of line 5), offset not advanced
        Files.write(archive, JsonlJournal.serialize(lines(4, 4)), StandardOpenOption.APPEND);
        Files.write(archive, Arrays.copyOf(JsonlJournal.serialize(lines(5, 5)), 25), StandardOpenOption.APPEND);

        assertEquals(2, new ArchiveFollower(journal, archive).pass());
        assertArrayEquals(Files.readAllBytes(journal), Files.readAllBytes(archive));
        assertEquals(0, new ArchiveFollower(journal, archive).pass());
    }

    @Test
    void passOnAMissingJournalConsumesNothingAndDoesNotThrow() throws IOException {
        Path journal = dir.resolve("journal.jsonl");
        Path archive = dir.resolve("archive.jsonl");
        ArchiveFollower follower = new ArchiveFollower(journal, archive);

        assertEquals(0, follower.pass(), "started before the sequencer created the journal");
        assertFalse(Files.exists(archive), "nothing written, so no archive and no cursor either");
        assertFalse(Files.exists(dir.resolve("archive.jsonl.offset")));

        try (JsonlJournal j = new JsonlJournal(journal)) {
            j.appendBatch(lines(1, 3));
        }
        assertEquals(3, follower.pass(), "the same follower picks up once the journal appears");
        assertArrayEquals(Files.readAllBytes(journal), Files.readAllBytes(archive));
    }

    @Test
    void journalMovedAsideMidRunLeavesTheOffsetAloneAndResumes() throws IOException {
        Path journal = dir.resolve("journal.jsonl");
        Path archive = dir.resolve("archive.jsonl");
        try (JsonlJournal j = new JsonlJournal(journal)) {
            j.appendBatch(lines(1, 3));
        }
        ArchiveFollower follower = new ArchiveFollower(journal, archive);
        assertEquals(3, follower.pass());
        byte[] cursorBefore = Files.readAllBytes(dir.resolve("archive.jsonl.offset"));

        // §3.2 recovery replaces the journal under a running follower.
        Path aside = dir.resolve("journal.jsonl.aside");
        Files.move(journal, aside);
        assertEquals(0, follower.pass());
        assertArrayEquals(cursorBefore, Files.readAllBytes(dir.resolve("archive.jsonl.offset")),
            "a skipped pass never advances the cursor");

        Files.move(aside, journal);
        Files.write(journal, JsonlJournal.serialize(lines(4, 5)), StandardOpenOption.APPEND);
        assertEquals(2, follower.pass(), "resumes where it left off, no gap and no duplicate");
        assertArrayEquals(Files.readAllBytes(journal), Files.readAllBytes(archive));
    }
}
