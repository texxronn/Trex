package trex.v2.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The scheduler fires through the same queue as the API, and skips a job that is already running. */
class JobSchedulerTest {

    private static RunnerConfig config(Path dir) {
        return new RunnerConfig("127.0.0.1", 0, dir, dir, dir, dir, dir, "http://seq", "http://hub",
            "http://ff", dir.resolve("firefly.yaml"), null, true, Duration.ofMinutes(10));
    }

    private static Schedule.Entry entry() {
        return new Schedule.Entry("demo", null, Duration.ofHours(24), LocalTime.of(0, 0), null,
            ZoneId.of("UTC"), true);
    }

    private static JobSpec spec() {
        return new JobSpec("demo", "Demo", "d", "read", List.of(), p -> List.of(new Step("one", List.of("x"))));
    }

    @Test
    void firesADueJobWithTheScheduleTrigger(@TempDir Path dir) throws Exception {
        JobRunnerService runner = new JobRunnerService(config(dir), Map.of("demo", spec()),
            (argv, line, handle) -> 0);
        JobScheduler scheduler = new JobScheduler(List.of(entry()), runner);
        try {
            scheduler.tick(Instant.now().plus(Duration.ofDays(1)));
            RunRecord last = runner.last("demo");
            assertNotNull(last);
            awaitTerminal(last);
            assertEquals("schedule", last.trigger());

            scheduler.tick(Instant.now().plus(Duration.ofDays(2)));
            assertEquals(2, runner.history().size());
            assertTrue(scheduler.nextRun("demo").isPresent());
        } finally {
            scheduler.close();
            runner.close();
        }
    }

    @Test
    void skipsAJobThatIsAlreadyActive(@TempDir Path dir) throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        JobRunnerService runner = new JobRunnerService(config(dir), Map.of("demo", spec()),
            (argv, line, handle) -> {
                gate.await(5, TimeUnit.SECONDS);
                return 0;
            });
        JobScheduler scheduler = new JobScheduler(List.of(entry()), runner);
        try {
            scheduler.tick(Instant.now().plus(Duration.ofDays(1)));
            long deadline = System.currentTimeMillis() + 3000;
            while (runner.active("demo") == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertNotNull(runner.active("demo"));

            scheduler.tick(Instant.now().plus(Duration.ofDays(2)));
            assertEquals(1, runner.history().size(), "the second tick must be skipped, not queued");
        } finally {
            gate.countDown();
            scheduler.close();
            runner.close();
        }
    }

    private static void awaitTerminal(RunRecord run) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline
            && run.state() != RunRecord.State.SUCCEEDED && run.state() != RunRecord.State.FAILED
            && run.state() != RunRecord.State.CANCELLED) {
            Thread.sleep(20);
        }
    }
}
