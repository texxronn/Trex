package trex.v2.runner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The runner's scheduler (V2-PROPOSAL.md §5.5, §9.1): one daemon thread that enqueues jobs through
 * the same {@link JobRunnerService} the trigger API uses. A due job that is already active is
 * skipped, not queued twice. Intervals only; there is no catch-up — a slot missed while the runner
 * was down is missed, and the manual button covers it.
 */
public final class JobScheduler implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(JobScheduler.class);

    private final List<Schedule.Entry> entries;
    private final JobRunnerService runner;
    private final Map<String, Instant> next = new ConcurrentHashMap<>();
    private final Thread thread;
    private volatile boolean running = true;

    JobScheduler(List<Schedule.Entry> entries, JobRunnerService runner) {
        this.entries = List.copyOf(entries);
        this.runner = runner;
        Instant now = Instant.now();
        for (Schedule.Entry entry : this.entries) {
            next.put(entry.job(), entry.next(now));
        }
        this.thread = Thread.ofPlatform().daemon(true).name("trex-scheduler").start(this::loop);
    }

    public Optional<Instant> nextRun(String job) {
        return Optional.ofNullable(next.get(job));
    }

    /** Fire every entry due at {@code now}; package-private so a test can drive it without waiting. */
    synchronized void tick(Instant now) {
        for (Schedule.Entry entry : entries) {
            if (!entry.enabled()) {
                continue;
            }
            Instant due = next.get(entry.job());
            if (due == null || due.isAfter(now)) {
                continue;
            }
            if (runner.active(entry.job()) == null) {
                try {
                    runner.submit(entry.job(), entry.params(), "schedule");
                    log.info("scheduled {} (every {})", entry.job(), entry.every());
                } catch (NothingToDo e) {
                    log.debug("scheduled {}: {}", entry.job(), e.getMessage());
                } catch (RuntimeException e) {
                    log.warn("scheduled {} could not start: {}", entry.job(), e.getMessage());
                }
            } else {
                log.info("skipping scheduled {}: already active", entry.job());
            }
            next.put(entry.job(), entry.next(now));
        }
    }

    private void loop() {
        while (running) {
            try {
                Instant now = Instant.now();
                Instant earliest = next.values().stream().min(Comparator.naturalOrder())
                    .orElse(now.plusSeconds(60));
                long sleepMs = Math.max(100, Duration.between(now, earliest).toMillis());
                Thread.sleep(sleepMs);
                tick(Instant.now());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                log.warn("scheduler loop error", e);
            }
        }
    }

    @Override
    public void close() {
        running = false;
        thread.interrupt();
    }
}
