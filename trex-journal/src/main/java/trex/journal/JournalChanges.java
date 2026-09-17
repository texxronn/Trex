package trex.journal;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.concurrent.TimeUnit;

/**
 * Wakes a journal reader when the journal file changes (SPEC §5.1). Uses a WatchService on the
 * journal's directory (inotify on Linux). Events are hints, never counts: a wake means "read now".
 * Create it before the first read so a change between a read and the next wait is not missed.
 * If the directory cannot be watched, {@link #await} simply waits for the timeout (fallback polling).
 */
public final class JournalChanges implements AutoCloseable {

    private final Path fileName;
    private final WatchService watchService;

    public JournalChanges(Path journal) {
        Path absolute = journal.toAbsolutePath();
        this.fileName = absolute.getFileName();
        WatchService ws = null;
        try {
            ws = FileSystems.getDefault().newWatchService();
            absolute.getParent().register(ws, StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);
        } catch (IOException | RuntimeException e) {
            System.err.println("journal change events unavailable, using fallback polling only: " + e.getMessage());
            closeQuietly(ws);
            ws = null;
        }
        this.watchService = ws;
    }

    /** Whether change events are delivered (false: only timeouts). */
    public boolean available() {
        return watchService != null;
    }

    /**
     * Block until the journal changes or {@code timeoutMillis} elapses.
     *
     * @return true if a change (or an event overflow) was seen, false on timeout
     */
    public boolean await(long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        if (watchService == null) {
            TimeUnit.MILLISECONDS.sleep(timeoutMillis);
            return false;
        }
        try {
            while (true) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                WatchKey key = watchService.poll(remaining, TimeUnit.NANOSECONDS);
                if (key == null) {
                    return false;
                }
                if (relevant(key)) {
                    drainPending();
                    return true;
                }
            }
        } catch (ClosedWatchServiceException e) {
            return false;
        }
    }

    /** Coalesce events already queued, so one burst of writes causes one read. */
    private void drainPending() {
        WatchKey key;
        while ((key = watchService.poll()) != null) {
            relevant(key);
        }
    }

    private boolean relevant(WatchKey key) {
        boolean relevant = false;
        for (WatchEvent<?> event : key.pollEvents()) {
            relevant |= event.kind() == StandardWatchEventKinds.OVERFLOW || fileName.equals(event.context());
        }
        key.reset();
        return relevant;
    }

    @Override
    public void close() {
        closeQuietly(watchService);
    }

    private static void closeQuietly(WatchService ws) {
        if (ws != null) {
            try {
                ws.close();
            } catch (IOException ignored) {
                // best effort
            }
        }
    }
}
