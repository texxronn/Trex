package trex.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import trex.journal.FramedReader;
import trex.journal.JournalChanges;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Follows the journal file and keeps a {@link Fold} of it (SPEC §5.4, §5.5). Nothing is persisted:
 * the fold is rebuilt from offset 0 at startup, and again whenever the file shrinks below the tail
 * offset or a read fails (e.g. the journal was replaced by a materialize).
 * Reads are triggered by journal change events ({@link JournalChanges}) with a slow fallback poll.
 */
public final class JournalWatcher<V> implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(JournalWatcher.class);

    /** Published snapshot. {@code n} is the highest journal n applied. */
    public record Status<V>(V view, long offset, long n, Instant updatedAt, String error) {}

    private final Path journal;
    private final Clock clock;
    private final Supplier<Fold<V>> folds;
    private final AtomicReference<Status<V>> status;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private Fold<V> fold;
    private long offset;
    private long n;
    private boolean refold;
    private JournalChanges changes;
    private Thread loop;
    private volatile boolean closed;

    public JournalWatcher(Path journal, Clock clock, Supplier<Fold<V>> folds) {
        this.journal = journal.toAbsolutePath();
        this.clock = clock;
        this.folds = folds;
        this.fold = folds.get();
        this.status = new AtomicReference<>(new Status<>(fold.snapshot(), 0, 0, clock.instant(), null));
    }

    public Status<V> status() {
        return status.get();
    }

    /** Called (on the watcher's thread) after a read that changed offset, n or error. Must not block. */
    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    /** Read any newly completed journal lines. Safe to call repeatedly; runs on one thread at a time. */
    public synchronized void poll() {
        Status<V> before = status.get();
        try {
            long size = Files.exists(journal) ? Files.size(journal) : 0;
            if (refold || size < offset) {
                log.info("refolding {} from offset 0 (size {}, tail offset {})", journal, size, offset);
                fold = folds.get();
                offset = 0;
                n = 0;
                refold = false;
            }
            if (size > offset) {
                try (FramedReader reader = new FramedReader(journal, offset)) {
                    FramedReader.Framed line;
                    while ((line = reader.next()) != null) {
                        if (line.event().n() <= n) {
                            throw new IllegalStateException("n not strictly increasing: " + line.event().n() + " after " + n);
                        }
                        fold.apply(line.event());
                        n = line.event().n();
                        offset = line.endOffset();
                    }
                }
            }
            status.set(new Status<>(fold.snapshot(), offset, n, clock.instant(), null));
        } catch (IOException | RuntimeException e) {
            // never let an exception escape: it would cancel the scheduled poll
            log.warn("journal read failed at offset {}; serving the last good view and refolding next pass",
                offset, e);
            refold = true;
            status.set(new Status<>(before.view(), before.offset(), before.n(), clock.instant(),
                "journal read failed: " + e.getMessage()));
        }
        Status<V> after = status.get();
        if (after.offset() != before.offset() || after.n() != before.n()
            || !Objects.equals(after.error(), before.error())) {
            listeners.forEach(Runnable::run);
        }
    }

    /**
     * Start event-driven tailing: read now, then read again on every journal change event or after
     * {@code fallbackPollMillis} without one (missed events, filesystems without inotify).
     */
    public JournalWatcher<V> start(long fallbackPollMillis) {
        changes = new JournalChanges(journal);   // registered before the first read: nothing is missed
        log.info("watching {} (change events {}, fallback poll {} ms)",
            journal, changes.available() ? "on" : "off", fallbackPollMillis);
        loop = daemon(() -> {
            try {
                while (!closed) {
                    poll();
                    changes.await(fallbackPollMillis);
                }
            } catch (InterruptedException _) {
                // shutting down
            }
        }, "journal-watch");
        loop.start();
        return this;
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    @Override
    public void close() {
        log.debug("stopping journal watcher for {}", journal);
        closed = true;
        if (loop != null) {
            loop.interrupt();
        }
        if (changes != null) {
            changes.close();
        }
    }
}
