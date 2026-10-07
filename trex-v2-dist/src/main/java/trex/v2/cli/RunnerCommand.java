package trex.v2.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import trex.v2.runner.RunnerConfig;
import trex.v2.runner.RunnerService;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Callable;

/**
 * {@code trex runner}: the on-demand job dispatcher and staged-file inbox (V2-PROPOSAL.md §5.5,
 * §12.5). Loopback only — the hub is the one origin the browser talks to and proxies here.
 */
@Command(name = "runner", mixinStandardHelpOptions = true,
    description = "Run on-demand jobs (firefly, ingest) and accept staged uploads; loopback only.")
public final class RunnerCommand implements Callable<Integer> {

    @Option(names = "--host", defaultValue = "127.0.0.1", description = "Bind host (keep it loopback).")
    String host;

    @Option(names = "--port", defaultValue = "8091", description = "Bind port.")
    int port;

    @Option(names = "--config", required = true, description = "Config directory (statements.yaml, firefly.yaml).")
    Path config;

    @Option(names = "--statements", description = "The host's statement directory (read-only).")
    Path statements;

    @Option(names = "--staging", required = true, description = "The staged-file inbox (read-write).")
    Path staging;

    @Option(names = "--evidence", required = true, description = "The evidence store ingest writes.")
    Path evidence;

    @Option(names = "--archive", description = "The archive root; ingest archives sources beneath it (§12.6).")
    Path archive;

    @Option(names = "--journal", description = "The journal file, read-only, for the stream export job (§14.1).")
    Path journal;

    @Option(names = "--sequencer-url", description = "The sequencer base URL (for ingest).")
    String sequencerUrl;

    @Option(names = "--hub-url", description = "The hub base URL (for egress).")
    String hubUrl;

    @Option(names = "--firefly-url", description = "The Firefly base URL (for egress).")
    String fireflyUrl;

    @Option(names = "--accounts", description = "firefly.yaml account mapping (for egress).")
    Path accounts;

    @Option(names = "--token", description = "Shared secret the hub must present (or TREX_RUNNER_TOKEN).")
    String token;

    @Option(names = "--allow-apply",
        description = "Unlock the egress apply job (also TREX_RUNNER_ALLOW_APPLY=true).")
    boolean allowApply;

    @Option(names = "--job-timeout-minutes", defaultValue = "30", description = "Wall-clock cap per run.")
    long jobTimeoutMinutes;

    @Override
    public Integer call() throws Exception {
        String secret = token != null ? token : System.getenv("TREX_RUNNER_TOKEN");
        String applyEnv = System.getenv("TREX_RUNNER_ALLOW_APPLY");
        boolean apply = allowApply || "true".equalsIgnoreCase(applyEnv == null ? "" : applyEnv.trim());
        RunnerConfig runnerConfig = new RunnerConfig(host, port, config, statements, staging, evidence,
            archive, journal, sequencerUrl, hubUrl, fireflyUrl, accounts, secret, apply,
            Duration.ofMinutes(jobTimeoutMinutes));
        RunnerService service = RunnerService.start(runnerConfig);
        Runtime.getRuntime().addShutdownHook(new Thread(service::close, "trex-runner-shutdown"));
        System.out.println("trex runner listening on " + host + ":" + service.port()
            + " (staging " + staging + ")");
        Thread.currentThread().join();
        return 0;
    }
}
