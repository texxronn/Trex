package trex.resolver;

import trex.core.state.Ledger;
import trex.core.state.LedgerView;
import trex.journal.FramedReader;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Follows the journal file and keeps the folded state (SPEC §5.4). Nothing is persisted: state is
 * rebuilt from offset 0 at startup, and again whenever the file shrinks below the tail offset or a
 * read fails (e.g. the journal was replaced by a materialize).
 * Reads are triggered by file-change events (WatchService, inotify on Linux) with a slow fallback poll.
 */
public final class JournalWatcher implements AutoCloseable {

    /** Published snapshot for the web API. */
    public record Status(LedgerView view, long offset, Instant updatedAt, String error) {}

    private final Path journal;
    private final Clock clock;
    private final AtomicReference<Status> status;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private Ledger ledger = new Ledger();
    private long offset;
    private boolean refold;
    private ScheduledExecutorService scheduler;
    private WatchService watchService;
    private Thread watchThread;

    public JournalWatcher(Path journal, Clock clock) {
        this.journal = journal.toAbsolutePath();
        this.clock = clock;
        this.status = new AtomicReference<>(new Status(LedgerView.EMPTY, 0, clock.instant(), null));
    }

    public Status status() {
        return status.get();
    }

    /** Called (on the watcher's thread) after a read that changed offset, n or error. Must not block. */
    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    /** Read any newly completed journal lines. Safe to call repeatedly; runs on one thread at a time. */
    public synchronized void poll() {
        Status before = status.get();
        try {
            long size = Files.exists(journal) ? Files.size(journal) : 0;
            if (refold || size < offset) {
                ledger = new Ledger();
                offset = 0;
                refold = false;
            }
            if (size > offset) {
                try (FramedReader reader = new FramedReader(journal, offset)) {
                    FramedReader.Framed line;
                    while ((line = reader.next()) != null) {
                        ledger.apply(line.event());
                        offset = line.endOffset();
                    }
                }
            }
            ledger.setHeadOffset(offset);
            status.set(new Status(ledger.snapshot(), offset, clock.instant(), null));
        } catch (IOException | RuntimeException e) {
            // never let an exception escape: it would cancel the scheduled poll
            refold = true;
            status.set(new Status(before.view(), before.offset(), clock.instant(), "journal read failed: " + e.getMessage()));
        }
        Status after = status.get();
        if (after.offset() != before.offset()
            || after.view().highWaterN() != before.view().highWaterN()
            || !Objects.equals(after.error(), before.error())) {
            listeners.forEach(Runnable::run);
        }
    }

    /**
     * Start event-driven tailing plus a fallback poll every {@code fallbackPollMillis}.
     * If the directory cannot be watched, the fallback poll alone keeps working.
     */
    public JournalWatcher start(long fallbackPollMillis) {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "journal-fallback-poll"));
        scheduler.scheduleWithFixedDelay(this::poll, 0, fallbackPollMillis, TimeUnit.MILLISECONDS);
        try {
            watchService = FileSystems.getDefault().newWatchService();
            journal.getParent().register(watchService, StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);
            watchThread = daemon(this::watchLoop, "journal-watch");
            watchThread.start();
        } catch (IOException | RuntimeException e) {
            System.err.println("journal watch unavailable, using fallback poll only: " + e.getMessage());
        }
        return this;
    }

    private void watchLoop() {
        Path name = journal.getFileName();
        try {
            while (true) {
                WatchKey key = watchService.take();
                boolean relevant = false;
                for (WatchEvent<?> event : key.pollEvents()) {
                    relevant |= event.kind() == StandardWatchEventKinds.OVERFLOW || name.equals(event.context());
                }
                if (relevant) {
                    poll();
                }
                if (!key.reset()) {
                    System.err.println("journal directory no longer watchable; using fallback poll only");
                    return;
                }
            }
        } catch (InterruptedException | ClosedWatchServiceException e) {
            // shutting down
        }
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    @Override
    public void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException ignored) {
                // best effort
            }
        }
    }
}
