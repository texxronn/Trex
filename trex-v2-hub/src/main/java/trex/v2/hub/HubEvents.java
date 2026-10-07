package trex.v2.hub;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The hub's change signal (V2-PROPOSAL.md §7.4): every derived-state change is published to SSE
 * subscribers. A subscriber registers before it reads the snapshot, so no delta can fall between
 * the snapshot and the stream — the same snapshot + delta rule the index itself follows.
 */
public final class HubEvents implements AutoCloseable {

    /** One change: the index moved to this offset at this {@code n} under these revisions. */
    public record Change(long n, long offset, String configRevision, String deriveVersion, String hashVersion) {}

    private static final int QUEUE_DEPTH = 64;

    private final Map<Long, BlockingQueue<Change>> subscribers = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();
    private final AtomicBoolean open = new AtomicBoolean(true);

    public boolean isOpen() {
        return open.get();
    }

    public Subscription subscribe() {
        long id = ids.incrementAndGet();
        BlockingQueue<Change> queue = new ArrayBlockingQueue<>(QUEUE_DEPTH);
        subscribers.put(id, queue);
        return new Subscription(id, queue);
    }

    /** Publish to every subscriber; a full queue drops its oldest change (the next supersedes it). */
    public void publish(Change change) {
        for (BlockingQueue<Change> queue : subscribers.values()) {
            while (!queue.offer(change)) {
                queue.poll();
            }
        }
    }

    public final class Subscription implements AutoCloseable {
        private final long id;
        private final BlockingQueue<Change> queue;

        private Subscription(long id, BlockingQueue<Change> queue) {
            this.id = id;
            this.queue = queue;
        }

        /** The next change, or empty on timeout or close. */
        public Optional<Change> poll(Duration timeout) throws InterruptedException {
            return Optional.ofNullable(queue.poll(timeout.toMillis(), TimeUnit.MILLISECONDS));
        }

        @Override
        public void close() {
            subscribers.remove(id);
        }
    }

    @Override
    public void close() {
        open.set(false);
        subscribers.clear();
    }
}
