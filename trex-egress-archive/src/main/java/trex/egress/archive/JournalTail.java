package trex.egress.archive;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import trex.core.CanonicalEvent;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Tails the journal file by byte offset with framing (SPEC §5.1). Only complete '\n'-terminated
 * lines are returned; a trailing partial line is left for the next pass. A terminated line that
 * does not parse is corruption and throws. (Duplicated minimally in each follower.)
 */
final class JournalTail implements AutoCloseable {

    record Line(byte[] bytes, CanonicalEvent event, long endOffset) {}

    static final ObjectMapper MAPPER = JsonMapper.builder()
        .addModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .build();

    private final FileChannel channel;
    private final InputStream in;
    private long offset;

    JournalTail(Path journal, long startOffset) throws IOException {
        channel = FileChannel.open(journal, StandardOpenOption.READ);
        channel.position(startOffset);
        in = new BufferedInputStream(Channels.newInputStream(channel), 1 << 16);
        offset = startOffset;
    }

    /** Next complete line, or null when only a partial line (or nothing) remains. */
    Line next() throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(512);
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                long start = offset;
                byte[] bytes = line.toByteArray();
                offset += bytes.length + 1;
                try {
                    return new Line(bytes, MAPPER.readValue(bytes, CanonicalEvent.class), offset);
                } catch (IOException | RuntimeException e) {
                    throw new IllegalStateException("corrupt journal line at offset " + start, e);
                }
            }
            line.write(b);
        }
        return null;
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
