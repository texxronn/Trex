package trex.sequencer.journal;

import trex.journal.JournalCorruptException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trex.core.CanonicalEvent;
import trex.core.EventState;
import trex.sequencer.TestEvents;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonlJournalTest {

    @TempDir
    Path dir;

    @Test
    void lineFormatIsDeterministicWithNullsWrittenAndIsoDates() throws IOException {
        Path p = dir.resolve("journal.jsonl");
        try (JsonlJournal j = new JsonlJournal(p)) {
            j.appendBatch(List.of(TestEvents.line(1, "ext1", EventState.HELD, -500)));
        }
        String expected = """
            {"n":1,"externalId":"ext1","accountRef":"ing-savings","toAccountRef":null,"currency":"AUD",\
            "date":"2026-06-01","amount":-500,"balance":1001,"description":"desc ext1","rawDescription":"desc  ext1",\
            "typeHint":"WITHDRAWAL","transferKey":null,"legIds":null,"corrects":null,"state":"HELD","confidence":null,\
            "flags":[],"provenance":"BANK","sourceType":"test","receipt":null,"counterpartyBsb":null,"counterpartyAcct":null,\
            "foreignAmount":null,"foreignCurrency":null,"comment":null,"ingestedAt":"2026-06-02T03:04:05Z"}
            """;
        assertEquals(expected, Files.readString(p, StandardCharsets.UTF_8));
    }

    @Test
    void appendReturnsHeadAndReplayRoundTrips() {
        Path p = dir.resolve("journal.jsonl");
        List<CanonicalEvent> batch = List.of(
            TestEvents.line(1, "a", EventState.EXTERNAL, 100),
            TestEvents.line(2, "b", EventState.HELD, -100));
        try (JsonlJournal j = new JsonlJournal(p)) {
            long head = j.appendBatch(batch);
            assertEquals(head, j.headOffset());
            assertEquals(JsonlJournal.serialize(batch).length, head);
            try (Stream<CanonicalEvent> s = j.replayFrom(0)) {
                assertEquals(batch, s.toList());
            }
        }
    }

    @Test
    void tornTailIsNotReturned() throws IOException {
        Path p = dir.resolve("journal.jsonl");
        try (JsonlJournal j = new JsonlJournal(p)) {
            j.appendBatch(List.of(TestEvents.line(1, "a", EventState.EXTERNAL, 100)));
        }
        Files.writeString(p, "{\"n\":2,\"externalId\":\"b\"", StandardOpenOption.APPEND);
        try (JsonlJournal j = new JsonlJournal(p); Stream<CanonicalEvent> s = j.replayFrom(0)) {
            assertEquals(List.of("a"), s.map(CanonicalEvent::externalId).toList());
        }
    }

    @Test
    void corruptTerminatedLineThrows() throws IOException {
        Path p = dir.resolve("journal.jsonl");
        Files.writeString(p, "not json\n");
        try (JsonlJournal j = new JsonlJournal(p); Stream<CanonicalEvent> s = j.replayFrom(0)) {
            assertThrows(JournalCorruptException.class, s::toList);
        }
    }

    @Test
    void replayFromOffsetSkipsEarlierRecords() {
        Path p = dir.resolve("journal.jsonl");
        try (JsonlJournal j = new JsonlJournal(p)) {
            long afterFirst = j.appendBatch(List.of(TestEvents.line(1, "a", EventState.EXTERNAL, 100)));
            j.appendBatch(List.of(TestEvents.line(2, "b", EventState.EXTERNAL, 100)));
            try (Stream<CanonicalEvent> s = j.replayFrom(afterFirst)) {
                assertEquals(List.of("b"), s.map(CanonicalEvent::externalId).toList());
            }
            assertTrue(j.headOffset() > afterFirst);
        }
    }
}
