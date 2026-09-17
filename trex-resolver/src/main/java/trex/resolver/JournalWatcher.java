package trex.resolver;

import trex.core.state.Ledger;
import trex.core.state.LedgerView;
import trex.journal.FramedReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Follows the journal file and keeps the folded state (SPEC §5.4). Nothing is persisted: state is
 * rebuilt from offset 0 at startup, and again whenever the file shrinks below the tail offset or a
 * read fails (e.g. the journal was replaced by a materialize).
 */
public final class JournalWatcher implements AutoCloseable {

    /** Published snapshot for the web API. */
    public record Status(LedgerView view, long offset, Instant updatedAt, String error) {}

    private final Path journal;
    private final Clock clock;
    private final AtomicReference<Status> status;
    private Ledger ledger = new Ledger();
    private long offset;
    private boolean refold;
    private ScheduledExecutorService scheduler;

    public JournalWatcher(Path journal, Clock clock) {
        this.journal = journal;
        this.clock = clock;
        this.status = new AtomicReference<>(new Status(LedgerView.EMPTY, 0, clock.instant(), null));
    }

    public Status status() {
        return status.get();
    }

    /** Read any newly completed journal lines. Safe to call repeatedly; runs on one thread at a time. */
    public synchronized void poll() {
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
            Status last = status.get();
            status.set(new Status(last.view(), last.offset(), clock.instant(),
                "journal read failed: " + e.getMessage()));
        }
    }

    public JournalWatcher start(long pollMillis) {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "journal-watcher");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::poll, 0, pollMillis, TimeUnit.MILLISECONDS);
        return this;
    }

    @Override
    public void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }
}
