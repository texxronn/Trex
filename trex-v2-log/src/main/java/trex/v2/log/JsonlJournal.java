package trex.v2.log;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import trex.v2.core.LogLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.List;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;

/**
 * Framed JSONL journal. One line per '\n'-terminated UTF-8 record; each batch is one write
 * followed by fsync before returning (V2-PROPOSAL.md §6.5). A failed append rolls back to the last
 * known head so later appends never follow garbage.
 */
public final class JsonlJournal implements Journal, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(JsonlJournal.class);

    private final Path path;
    private final FileChannel channel;
    private long head;
    private boolean broken;

    public JsonlJournal(Path path) {
        this.path = path;
        try {
            boolean created = java.nio.file.Files.notExists(path);
            this.channel = FileChannel.open(path, CREATE, WRITE, APPEND);
            if (created) {
                Recovery.syncFileAndDirectory(path);
            }
            this.head = channel.size();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public synchronized long appendBatch(List<LogLine> lines) {
        if (broken) {
            throw new IllegalStateException("journal is unusable after a failed append; restart to recover");
        }
        if (lines.isEmpty()) {
            return head;
        }
        ByteBuffer block = ByteBuffer.wrap(serialize(lines));
        try {
            while (block.hasRemaining()) {
                channel.write(block);
            }
            channel.force(true);
        } catch (IOException e) {
            log.error("journal append failed at head offset {} for {} lines, rolling back", head, lines.size(), e);
            rollback();
            throw new UncheckedIOException("journal append failed", e);
        }
        head += block.capacity();
        log.debug("appended {} lines, head offset {}", lines.size(), head);
        return head;
    }

    /** Remove a partially written block so later appends never follow garbage. */
    private void rollback() {
        try {
            channel.truncate(head);
            channel.force(true);
        } catch (IOException e) {
            broken = true;
            log.error("rollback to offset {} failed; journal is unusable until restart", head, e);
        }
    }

    public static byte[] serialize(List<LogLine> lines) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(lines.size() * 512);
        for (LogLine line : lines) {
            out.writeBytes(LogCodec.encode(line));
            out.write('\n');
        }
        return out.toByteArray();
    }

    @Override
    public Stream<LogLine> replayFrom(long offset) {
        FramedReader reader = new FramedReader(path, offset);
        Spliterator<LogLine> split = new Spliterators.AbstractSpliterator<>(Long.MAX_VALUE, Spliterator.ORDERED) {
            @Override
            public boolean tryAdvance(Consumer<? super LogLine> action) {
                FramedReader.Framed f = reader.next();
                if (f == null) {
                    return false;
                }
                action.accept(f.line());
                return true;
            }
        };
        return StreamSupport.stream(split, false).onClose(reader::close);
    }

    @Override
    public synchronized long headOffset() {
        return head;
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
