package trex.v2.runner;

import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

/**
 * The runner role as a service (V2-PROPOSAL.md §5.5): the staged-file inbox, the job queue and the
 * loopback trigger API. One process; the role is this class, not a different artifact.
 */
public final class RunnerService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RunnerService.class);

    private final RunnerConfig config;
    private final JobRunnerService runner;
    private final HttpServer server;
    private final JobScheduler scheduler;

    private RunnerService(RunnerConfig config, JobRunnerService runner, HttpServer server, JobScheduler scheduler) {
        this.config = config;
        this.runner = runner;
        this.server = server;
        this.scheduler = scheduler;
    }

    public static RunnerService start(RunnerConfig config) {
        StatementsMap statements = StatementsMap.load(config.configDir());
        StagingStore staging = new StagingStore(config.stagingDir());
        Map<String, JobSpec> jobs = JobCatalogue.of(config, statements, staging);
        JobRunnerService runner = new JobRunnerService(config, jobs, new ProcessCommandRunner());
        java.util.List<Schedule.Entry> schedule = Schedule.load(config.configDir());
        for (Schedule.Entry entry : schedule) {
            if (!jobs.containsKey(entry.job())) {
                runner.close();
                throw new IllegalArgumentException("schedule.yaml names an unknown job: " + entry.job());
            }
        }
        JobScheduler scheduler = schedule.isEmpty() ? null : new JobScheduler(schedule, runner);
        try {
            HttpServer server = RunnerHttpApi.start(config, runner, staging, jobs, statements, scheduler);
            log.info("trex runner listening on {}:{}; staging {}; jobs {}",
                config.host(), server.getAddress().getPort(), config.stagingDir(), jobs.keySet());
            if (!config.allowApply()) {
                log.info("--apply is disabled (start with --allow-apply to unlock it)");
            } else {
                log.warn("--apply is ENABLED: anyone who can reach the hub on this origin can "
                    + "project to Firefly; front the hub with auth before exposing it");
            }
            if (scheduler != null) {
                log.info("scheduled jobs: {}", schedule.stream()
                    .map(e -> e.job() + " every " + e.every()).toList());
            }
            return new RunnerService(config, runner, server, scheduler);
        } catch (IOException e) {
            runner.close();
            throw new UncheckedIOException("cannot start the runner", e);
        }
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public RunnerConfig config() {
        return config;
    }

    @Override
    public void close() {
        if (scheduler != null) {
            scheduler.close();
        }
        server.stop(0);
        runner.close();
    }
}
