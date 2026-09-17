package trex.sequencer.journal;

import trex.journal.FramedReader;
import trex.core.CanonicalEvent;
import trex.journal.Json;

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
 * Framed JSONL journal. One event per '\n'-terminated UTF-8 line; each batch is one write
 * followed by fsync before returning. SPEC §3.1.
 * Open only after {@link Recovery} has truncated any torn tail.
 */
public final class JsonlJournal implements Journal, AutoCloseable {

    private final Path path;
    private final FileChannel channel;
    private long head;
    private boolean broken;

    public JsonlJournal(Path path) {
        this.path = path;
        try {
            this.channel = FileChannel.open(path, CREATE, WRITE, APPEND);
            this.head = channel.size();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public synchronized long appendBatch(List<CanonicalEvent> events) {
        if (broken) {
            throw new IllegalStateException("journal is unusable after a failed append; restart to recover");
        }
        if (events.isEmpty()) {
            return head;
        }
        ByteBuffer block = ByteBuffer.wrap(serialize(events));
        try {
            while (block.hasRemaining()) {
                channel.write(block);
            }
            channel.force(true);
        } catch (IOException e) {
            rollback();
            throw new UncheckedIOException("journal append failed", e);
        }
        head += block.capacity();
        return head;
    }

    /** Remove a partially written block so later appends never follow garbage. */
    private void rollback() {
        try {
            channel.truncate(head);
            channel.force(true);
        } catch (IOException e) {
            broken = true;
        }
    }

    public static byte[] serialize(List<CanonicalEvent> events) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(events.size() * 512);
        try {
            for (CanonicalEvent e : events) {
                out.write(Json.mapper().writeValueAsBytes(e));
                out.write('\n');
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    @Override
    public Stream<CanonicalEvent> replayFrom(long offset) {
        FramedReader reader = new FramedReader(path, offset);
        Spliterator<CanonicalEvent> split = new Spliterators.AbstractSpliterator<>(Long.MAX_VALUE, Spliterator.ORDERED) {
            @Override
            public boolean tryAdvance(Consumer<? super CanonicalEvent> action) {
                FramedReader.Framed f = reader.next();
                if (f == null) {
                    return false;
                }
                action.accept(f.event());
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
