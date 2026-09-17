package trex.journal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trex.core.CanonicalEvent;
import trex.core.EventState;
import trex.core.Provenance;
import trex.core.TypeHint;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FramedReaderTest {

    @TempDir
    Path dir;

    private static CanonicalEvent line(long n) {
        return new CanonicalEvent(n, "ext" + n, "acc", null, "AUD", LocalDate.parse("2026-06-01"), -n, 0, "d", "d",
            TypeHint.WITHDRAWAL, null, null, null, EventState.EXTERNAL, null, List.of(), Provenance.BANK, "t",
            null, null, null, null, null, null, Instant.parse("2026-06-02T00:00:00Z"));
    }

    @Test
    void returnsCompleteRecordsWithRawBytesAndStopsAtPartialTail() throws IOException {
        byte[] first = Json.mapper().writeValueAsBytes(line(1));
        byte[] second = Json.mapper().writeValueAsBytes(line(2));
        Path p = dir.resolve("j.jsonl");
        Files.write(p, (new String(first, StandardCharsets.UTF_8) + "\n"
            + new String(second, StandardCharsets.UTF_8) + "\n{\"n\":3").getBytes(StandardCharsets.UTF_8));

        try (FramedReader r = new FramedReader(p, 0)) {
            FramedReader.Framed a = r.next();
            assertArrayEquals(first, a.bytes());
            assertEquals(line(1), a.event());
            assertEquals(first.length + 1, a.endOffset());
            FramedReader.Framed b = r.next();
            assertEquals(line(2), b.event());
            assertEquals(first.length + second.length + 2, b.endOffset());
            assertNull(r.next());
        }
        try (FramedReader r = new FramedReader(p, first.length + 1)) {
            assertEquals(line(2), r.next().event());
        }
    }

    @Test
    void corruptTerminatedLineThrows() throws IOException {
        Path p = dir.resolve("j.jsonl");
        Files.writeString(p, "{\"n\":1}\n");
        try (FramedReader r = new FramedReader(p, 0)) {
            assertThrows(JournalCorruptException.class, r::next);
        }
    }
}
