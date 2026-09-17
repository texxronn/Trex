package trex.journal;

import trex.core.CanonicalEvent;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Reads framed JSONL journal records (SPEC §3.1). A record is complete iff it ends in '\n' and
 * parses as a {@link CanonicalEvent}. A trailing line without '\n' is a torn/partial tail: not
 * returned, offset not advanced. A '\n'-terminated line that does not parse is corruption and throws.
 * Shared by every journal reader: sequencer recovery, followers and the resolver.
 */
public final class FramedReader implements AutoCloseable {

    /** One complete record: its raw line bytes (without '\n'), the parsed event, and the offset just past its '\n'. */
    public record Framed(byte[] bytes, CanonicalEvent event, long endOffset) {}

    private final FileChannel channel;
    private final InputStream in;
    private long offset;

    public FramedReader(Path path, long startOffset) {
        try {
            this.channel = FileChannel.open(path, StandardOpenOption.READ);
            channel.position(startOffset);
            this.in = new BufferedInputStream(Channels.newInputStream(channel), 1 << 16);
            this.offset = startOffset;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Next complete record, or null at end of complete data (a partial tail is left unread). */
    public Framed next() {
        ByteArrayOutputStream line = new ByteArrayOutputStream(512);
        try {
            int b;
            while ((b = in.read()) != -1) {
                if (b == '\n') {
                    long lineStart = offset;
                    byte[] bytes = line.toByteArray();
                    offset += bytes.length + 1;
                    return new Framed(bytes, parse(bytes, lineStart), offset);
                }
                line.write(b);
            }
            return null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static CanonicalEvent parse(byte[] line, long lineStart) {
        try {
            return Json.mapper().readValue(line, CanonicalEvent.class);
        } catch (IOException | RuntimeException e) {
            throw new JournalCorruptException("unparseable journal line at offset " + lineStart, e);
        }
    }

    @Override
    public void close() {
        try {
            channel.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
