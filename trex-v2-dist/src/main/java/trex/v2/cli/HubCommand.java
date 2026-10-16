package trex.v2.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import trex.v2.hub.HubConfig;
import trex.v2.hub.HubService;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * {@code trex hub}: the index owner, the blotter API and (later) the UI (V2-PROPOSAL.md §7.4).
 * It watches the journal and the config, applies and re-derives, and holds the index lock.
 * {@code trex hub passwd} is the host-side credential editor (V2-SPEC.md §9.5).
 */
@Command(name = "hub", mixinStandardHelpOptions = true,
    description = "Run the index owner and blotter API: watches the journal and config.",
    subcommands = HubPasswdCommand.class)
public final class HubCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Option(names = "--journal", description = "Path to trex.jsonl (read-only).")
    Path journal;

    @Option(names = "--config", description = "Config directory.")
    Path config;

    @Option(names = "--index", description = "Path to trex.sqlite (owned by the hub).")
    Path index;

    @Option(names = "--host", defaultValue = "127.0.0.1", description = "Bind host.")
    String host;

    @Option(names = "--port", defaultValue = "8090", description = "Bind port.")
    int port;

    @Option(names = "--sequencer-url",
        description = "The only writer's base URL; without it the hub is read-only.")
    String sequencerUrl;

    @Option(names = "--evidence", description = "Evidence store to index (optional).")
    Path evidence;

    @Option(names = "--runner-url",
        description = "The loopback job runner base URL; without it the Jobs view is hidden.")
    String runnerUrl;

    @Option(names = "--runner-token",
        description = "Shared secret presented to the runner (or TREX_RUNNER_TOKEN).")
    String runnerToken;

    @Option(names = "--auth",
        description = "Credential file for sign-in (or TREX_AUTH_FILE); without it the hub is open.")
    Path auth;

    @Override
    public Integer call() throws Exception {
        if (journal == null || config == null || index == null) {
            // These are not picocli `required` options: a parent's required options are validated
            // even when a subcommand runs, so `hub passwd` would demand --journal/--index. Checked
            // here instead, keeping the documented EX_USAGE (64) exit for a bare `hub` (V2-PROPOSAL.md §5.2).
            spec.commandLine().usage(System.err);
            System.err.println("Missing required options: '--journal', '--config' and '--index'");
            return 64;
        }
        String token = runnerToken != null ? runnerToken : System.getenv("TREX_RUNNER_TOKEN");
        String envAuth = System.getenv("TREX_AUTH_FILE");
        Path authFile = auth != null ? auth
            : (envAuth == null || envAuth.isBlank() ? null : Path.of(envAuth));
        HubConfig hubConfig = new HubConfig(journal, index, config, host, port,
            HubConfig.DEFAULT_DEBOUNCE_MS, sequencerUrl, evidence, runnerUrl, token,
            HubConfig.DEFAULT_ASOF_REFRESH_MS, authFile);
        HubService service = HubService.start(hubConfig);
        Runtime.getRuntime().addShutdownHook(new Thread(service::close, "trex-hub-shutdown"));
        System.out.println("trex hub listening on " + host + ":" + service.port()
            + " (journal " + journal + ", index " + index + ")");
        Thread.currentThread().join();
        return 0;
    }
}
