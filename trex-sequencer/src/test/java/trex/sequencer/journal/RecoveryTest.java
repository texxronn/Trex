package trex.sequencer.journal;

import trex.journal.JournalCorruptException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trex.core.CanonicalEvent;
import trex.core.EventState;
import trex.sequencer.TestEvents;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** SPEC §7 test 9 plus in-place recovery. */
class RecoveryTest {

    @TempDir
    Path dir;

    private long writeTwoBatches(Path p) {
        try (JsonlJournal j = new JsonlJournal(p)) {
            j.appendBatch(List.of(TestEvents.line(1, "a", EventState.EXTERNAL, 100),
                TestEvents.line(2, "b", EventState.HELD, -100)));
            return j.appendBatch(List.of(TestEvents.line(3, "c", EventState.EXTERNAL, 5)));
        }
    }

    @Test
    void inPlaceRecoveryTruncatesTornTail() throws IOException {
        Path p = dir.resolve("j.jsonl");
        long head = writeTwoBatches(p);
        Files.writeString(p, "{\"n\":4,\"extern", StandardOpenOption.APPEND);
        assertEquals(head, Recovery.recover(p, p));
        assertEquals(head, Files.size(p));
    }

    @Test
    void inPlaceRecoveryCreatesMissingJournal() {
        Path p = dir.resolve("new.jsonl");
        assertEquals(0, Recovery.recover(p, p));
    }

    @Test
    void materializeIsBitIdenticalOffsetsValidAndTornTailTruncated() throws IOException {
        Path source = dir.resolve("source.jsonl");
        Path target = dir.resolve("target.jsonl");
        long head;
        long afterFirstBatch;
        try (JsonlJournal j = new JsonlJournal(source)) {
            afterFirstBatch = j.appendBatch(List.of(TestEvents.line(1, "a", EventState.EXTERNAL, 100)));
            head = j.appendBatch(List.of(TestEvents.line(2, "b", EventState.HELD, -100)));
        }
        byte[] complete = Files.readAllBytes(source);
        Files.writeString(source, "{\"n\":3", StandardOpenOption.APPEND);
        Files.writeString(target, "stale target content that must be overwritten\n");

        assertEquals(head, Recovery.recover(source, target));
        assertArrayEquals(complete, Files.readAllBytes(target));

        try (JsonlJournal j = new JsonlJournal(target); Stream<CanonicalEvent> s = j.replayFrom(afterFirstBatch)) {
            assertEquals(List.of("b"), s.map(CanonicalEvent::externalId).toList());
        }
    }

    @Test
    void materializeWithoutTornTailHashesMatch() throws IOException {
        Path source = dir.resolve("source.jsonl");
        Path target = dir.resolve("target.jsonl");
        writeTwoBatches(source);
        Recovery.recover(source, target);
        assertArrayEquals(Recovery.sha256(source), Recovery.sha256(target));
    }

    @Test
    void missingSourceAborts() {
        assertThrows(IllegalStateException.class,
            () -> Recovery.recover(dir.resolve("absent.jsonl"), dir.resolve("target.jsonl")));
    }

    @Test
    void corruptCompleteLineAbortsRecovery() throws IOException {
        Path p = dir.resolve("j.jsonl");
        writeTwoBatches(p);
        Files.writeString(p, "garbage\n", StandardOpenOption.APPEND);
        assertThrows(JournalCorruptException.class, () -> Recovery.recover(p, p));
    }
}
