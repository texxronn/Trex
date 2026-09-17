package trex.egress.archive;

import trex.journal.FramedReader;
import trex.journal.JournalChanges;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.IntConsumer;

import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

/**
 * Log-mirror follower: appends each journal line verbatim to an archive JSONL (SPEC §5.2).
 * Idempotent by n; cursor is a plain offset file advanced only after the archive is fsynced.
 */
public final class ArchiveFollower {

    private final Path journal;
    private final Path archive;
    private final Path offsetFile;
    private long lastArchivedN = -1;

    public ArchiveFollower(Path journal, Path archive) {
        this.journal = journal;
        this.archive = archive;
        this.offsetFile = archive.resolveSibling(archive.getFileName() + ".offset");
    }

    /**
     * Follow until interrupted: a pass now, then a pass on every journal change event, or after
     * {@code fallbackMillis} without one. {@code onPass} receives each pass's archived-line count.
     */
    public void follow(long fallbackMillis, IntConsumer onPass) throws IOException, InterruptedException {
        try (JournalChanges changes = new JournalChanges(journal)) {
            while (!Thread.currentThread().isInterrupted()) {
                onPass.accept(pass());
                changes.await(fallbackMillis);
            }
        }
    }

    /** One follower pass. Returns the number of lines newly archived. */
    public int pass() throws IOException {
        if (lastArchivedN < 0) {
            lastArchivedN = recoverArchive();
        }
        long offset = readOffset();
        long advanced = offset;
        int archived = 0;
        try (FramedReader tail = new FramedReader(journal, offset);
             FileChannel out = FileChannel.open(archive, CREATE, WRITE, APPEND)) {
            FramedReader.Framed line;
            while ((line = tail.next()) != null) {
                if (line.event().n() > lastArchivedN) {
                    ByteBuffer buf = ByteBuffer.allocate(line.bytes().length + 1).put(line.bytes()).put((byte) '\n').flip();
                    while (buf.hasRemaining()) {
                        out.write(buf);
                    }
                    lastArchivedN = line.event().n();
                    archived++;
                }
                advanced = line.endOffset();
            }
            if (archived > 0) {
                out.force(true);
            }
        }
        if (advanced != offset) {
            writeOffset(advanced);
        }
        return archived;
    }

    /** Truncate a torn archive tail and return the highest archived n (0 if empty). */
    private long recoverArchive() throws IOException {
        if (Files.notExists(archive)) {
            return 0;
        }
        long end = 0;
        long lastN = 0;
        try (FramedReader tail = new FramedReader(archive, 0)) {
            FramedReader.Framed line;
            while ((line = tail.next()) != null) {
                end = line.endOffset();
                lastN = line.event().n();
            }
        }
        try (FileChannel ch = FileChannel.open(archive, WRITE)) {
            if (ch.size() > end) {
                ch.truncate(end);
                ch.force(true);
            }
        }
        return lastN;
    }

    private long readOffset() throws IOException {
        if (Files.notExists(offsetFile)) {
            return 0;
        }
        return Long.parseLong(Files.readString(offsetFile, StandardCharsets.US_ASCII).strip());
    }

    private void writeOffset(long offset) throws IOException {
        Path tmp = offsetFile.resolveSibling(offsetFile.getFileName() + ".tmp");
        try (FileChannel ch = FileChannel.open(tmp, CREATE, WRITE, TRUNCATE_EXISTING)) {
            ch.write(ByteBuffer.wrap((offset + "\n").getBytes(StandardCharsets.US_ASCII)));
            ch.force(true);
        }
        Files.move(tmp, offsetFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        try (FileChannel dir = FileChannel.open(offsetFile.toAbsolutePath().getParent(), READ)) {
            dir.force(true);
        } catch (IOException ignored) {
            // directory fsync is not supported on every platform
        }
    }
}
