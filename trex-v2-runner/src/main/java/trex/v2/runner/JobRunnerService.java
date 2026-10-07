package trex.v2.runner;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The runner's core (V2-PROPOSAL.md §5.5): a single worker, a FIFO queue, a bounded in-memory run
 * history. Jobs are serialized because ingest and {@code --apply} mutate external state and there is
 * one operator; read-only jobs wait too, for now.
 */
public final class JobRunnerService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(JobRunnerService.class);
    private static final int HISTORY = 50;

    private final RunnerConfig config;
    private final Map<String, JobSpec> jobs;
    private final CommandRunner executor;
    private final BlockingQueue<RunRecord> queue = new LinkedBlockingQueue<>();
    private final Deque<RunRecord> history = new ArrayDeque<>();
    private final ScheduledExecutorService scheduler =
        Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon(true).name("trex-runner-timer").factory());
    private final Thread worker;
    private volatile boolean running = true;

    public JobRunnerService(RunnerConfig config, Map<String, JobSpec> jobs, CommandRunner executor) {
        this.config = config;
        this.jobs = Map.copyOf(jobs);
        this.executor = executor;
        this.worker = Thread.ofPlatform().daemon(true).name("trex-runner-worker").start(this::loop);
    }

    public Map<String, JobSpec> jobs() {
        return jobs;
    }

    /** Build the steps now (so a bad request fails before it queues) and enqueue the run. */
    public RunRecord submit(String jobName, JsonNode params) {
        return submit(jobName, params, "manual");
    }

    /** Build the steps now (so a bad request fails before it queues) and enqueue the run. */
    public RunRecord submit(String jobName, JsonNode params, String trigger) {
        JobSpec spec = jobs.get(jobName);
        if (spec == null) {
            throw new IllegalArgumentException("unknown job: " + jobName);
        }
        List<Step> steps = spec.steps().build(params == null ? com.fasterxml.jackson.databind.node.JsonNodeFactory
            .instance.nullNode() : params);
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("no work to do");
        }
        RunRecord run = new RunRecord(UUID.randomUUID().toString(), jobName, params, steps, trigger);
        synchronized (history) {
            history.addFirst(run);
            while (history.size() > HISTORY) {
                history.removeLast();
            }
        }
        queue.add(run);
        return run;
    }

    public List<RunRecord> history() {
        synchronized (history) {
            return new ArrayList<>(history);
        }
    }

    public RunRecord find(String id) {
        synchronized (history) {
            for (RunRecord run : history) {
                if (run.id().equals(id)) {
                    return run;
                }
            }
        }
        return null;
    }

    /** The queued or running run for a job, if any — so the UI can disable its buttons. */
    public RunRecord active(String jobName) {
        synchronized (history) {
            for (RunRecord run : history) {
                if (run.job().equals(jobName)
                    && (run.state() == RunRecord.State.QUEUED || run.state() == RunRecord.State.RUNNING)) {
                    return run;
                }
            }
        }
        return null;
    }

    public RunRecord last(String jobName) {
        synchronized (history) {
            for (RunRecord run : history) {
                if (run.job().equals(jobName)) {
                    return run;
                }
            }
        }
        return null;
    }

    public boolean cancel(String id) {
        RunRecord run = find(id);
        return run != null && run.requestCancel();
    }

    private void loop() {
        while (running) {
            RunRecord run;
            try {
                run = queue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            execute(run);
        }
    }

    private void execute(RunRecord run) {
        run.markRunning();
        run.append("== " + run.job());
        ScheduledFuture<?> timeout = scheduler.schedule(() -> {
            run.append("!! timed out after " + config.jobTimeout().toMinutes() + "m; terminating");
            run.requestCancel();
        }, config.jobTimeout().toMillis(), TimeUnit.MILLISECONDS);
        int firstBad = 0;
        try {
            for (Step step : run.steps()) {
                if (run.cancelRequested()) {
                    break;
                }
                run.append("== " + step.label());
                int code;
                try {
                    code = executor.run(step.argv(), run::append, run::attach);
                } catch (Exception e) {
                    run.append("!! " + e.getClass().getSimpleName() + ": " + e.getMessage());
                    code = -1;
                }
                run.stepExit(step.label(), code);
                run.append("-- " + step.label() + " exit " + code);
                if (code != 0 && firstBad == 0) {
                    firstBad = code;
                }
                if (run.cancelRequested()) {
                    break;
                }
            }
        } finally {
            timeout.cancel(false);
        }
        if (run.cancelRequested()) {
            run.mark(RunRecord.State.CANCELLED, -1);
        } else {
            run.mark(firstBad == 0 ? RunRecord.State.SUCCEEDED : RunRecord.State.FAILED, firstBad);
        }
        log.info("job {} run {} finished {} exit {}", run.job(), run.id(), run.state(), run.exit());
    }

    @Override
    public void close() {
        running = false;
        worker.interrupt();
        scheduler.shutdownNow();
    }
}
