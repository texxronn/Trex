package trex.v2.log;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trex.v2.core.Actor;
import trex.v2.core.Decision;
import trex.v2.core.Fact;
import trex.v2.core.LogLine;
import trex.v2.core.Observation;
import trex.v2.core.Provenance;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Framing, torn-tail recovery and materialize (V2-PROPOSAL.md §6.4). */
class JournalRecoveryTest {

    private static final Instant AT = Instant.parse("2026-09-29T08:31:00Z");

    private static Fact fact(long n, String id) {
        return new Fact(n, id, "ing-savings", LocalDate.of(2026, 9, 1), -1000, 0, "COLES 1234", null, 0,
            Observation.POSTED, "ing-csv", Provenance.BANK, null, "ing-csv/1", AT);
    }

    @Test
    void batchAppendIsAtomicAndFsynced(@TempDir Path dir) {
        Path journal = dir.resolve("trex.jsonl");
        try (JsonlJournal j = new JsonlJournal(journal)) {
            long head = j.appendBatch(List.of(fact(1, "a"), fact(2, "b")));
            assertTrue(head > 0);
            assertEquals(head, j.headOffset());
            try (Stream<LogLine> replay = j.replayFrom(0)) {
                assertEquals(2, replay.count());
            }
        }
    }

    @Test
    void tornTailIsNotReturnedAndIsTruncated(@TempDir Path dir) throws Exception {
        Path journal = dir.resolve("trex.jsonl");
        try (JsonlJournal j = new JsonlJournal(journal)) {
            j.appendBatch(List.of(fact(1, "a"), fact(2, "b")));
        }
        long good = Files.size(journal);
        Files.write(journal, "{\"n\":3,\"kind\":\"fact\"".getBytes(StandardCharsets.UTF_8),
            java.nio.file.StandardOpenOption.APPEND);
        assertEquals(good, Recovery.scanToLastCompleteRecord(journal));
        Recovery.recover(journal, journal);
        assertEquals(good, Files.size(journal));
    }

    @Test
    void corruptCompleteLineThrows(@TempDir Path dir) throws Exception {
        Path journal = dir.resolve("trex.jsonl");
        Files.writeString(journal, "{\"n\":1,\"kind\":\"fact\"}\n");
        assertThrows(JournalCorruptException.class, () -> Recovery.scanToLastCompleteRecord(journal));
    }

    @Test
    void materializeCopiesSourceByteForByte(@TempDir Path dir) throws Exception {
        Path source = dir.resolve("source.jsonl");
        Path target = dir.resolve("target.jsonl");
        Files.write(source, JsonlJournal.serialize(List.of(fact(1, "a"), fact(2, "b"))));
        long head = Recovery.recover(source, target);
        assertTrue(head > 0);
        assertEquals(Files.readAllBytes(source).length, Files.readAllBytes(target).length);
    }

    @Test
    void replayFromResumesAtOffset(@TempDir Path dir) {
        Path journal = dir.resolve("trex.jsonl");
        try (JsonlJournal j = new JsonlJournal(journal)) {
            long afterFirst = j.appendBatch(List.of(fact(1, "a")));
            j.appendBatch(List.of(fact(2, "b")));
            try (Stream<LogLine> replay = j.replayFrom(afterFirst)) {
                List<LogLine> rest = replay.toList();
                assertEquals(1, rest.size());
                assertEquals(2, rest.getFirst().n());
            }
        }
    }

    @Test
    void decisionsAndFactsShareOneStream(@TempDir Path dir) {
        Path journal = dir.resolve("trex.jsonl");
        try (JsonlJournal j = new JsonlJournal(journal)) {
            j.appendBatch(List.of(fact(1, "a"), new Decision.MarkExternal(2, "a", "x", Actor.USER, "ron", AT)));
            try (Stream<LogLine> replay = j.replayFrom(0)) {
                List<LogLine> lines = replay.toList();
                assertEquals(2, lines.size());
                assertTrue(lines.getFirst() instanceof Fact);
                assertTrue(lines.get(1) instanceof Decision.MarkExternal);
            }
        }
    }
}
