package trex.v2.sequencer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * The sequencer's single-writer lock (V2-PROPOSAL.md §6.5): the journal has one writer, and a
 * second sequencer against the same file must fail loudly rather than interleave appends.
 */
public final class JournalLock implements AutoCloseable {

    private final FileChannel channel;
    private final FileLock lock;

    private JournalLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    public static JournalLock acquire(Path journalPath) {
        Path lockPath = journalPath.resolveSibling(journalPath.getFileName() + ".lock");
        try {
            FileChannel ch = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock l;
            try {
                l = ch.tryLock();
            } catch (OverlappingFileLockException e) {
                ch.close();
                throw new IllegalStateException("the journal is already locked in this process: " + journalPath, e);
            }
            if (l == null) {
                ch.close();
                throw new IllegalStateException("the journal is locked by another writer: " + journalPath);
            }
            return new JournalLock(ch, l);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot lock the journal at " + lockPath, e);
        }
    }

    @Override
    public void close() {
        try {
            lock.release();
            channel.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
