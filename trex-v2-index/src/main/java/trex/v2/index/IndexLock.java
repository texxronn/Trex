package trex.v2.index;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * The index's single-writer lock (V2-PROPOSAL.md §7.4). {@code trex-hub} holds it while running;
 * {@code trex index --rebuild} takes the same lock and fails loudly if the hub is live, which is
 * what makes the offline rebuild safe.
 */
public final class IndexLock implements AutoCloseable {

    private final FileChannel channel;
    private final FileLock lock;

    private IndexLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    public static IndexLock acquire(Path dbPath) {
        Path lockPath = dbPath.resolveSibling(dbPath.getFileName() + ".lock");
        try {
            FileChannel ch = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock l;
            try {
                l = ch.tryLock();
            } catch (OverlappingFileLockException e) {
                ch.close();
                throw new IllegalStateException("the index is already open in this process: " + dbPath, e);
            }
            if (l == null) {
                ch.close();
                throw new IllegalStateException("the index is locked (is trex-hub running?): " + dbPath);
            }
            return new IndexLock(ch, l);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot lock the index at " + lockPath, e);
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
