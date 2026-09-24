package trex.gateway;

import trex.category.CategoryRules;
import trex.category.Categorizer;
import trex.category.RuleCategorizer;
import trex.gateway.ledger.SequencerClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code trex-gateway --journal <path> --sequencer-url <url> [--config <dir>] [--port 8085] [--bind 127.0.0.1] [--poll-ms 10000]}
 * <p>
 * The consumer API (SPEC §5.7): the journal folded and categorised, served as a snapshot and a
 * stream, with the rule files and the decisions path to the sequencer behind it.
 */
@Command(name = "trex-gateway", mixinStandardHelpOptions = true,
    // 64 (EX_USAGE) is the documented contract; picocli would use 2.
    exitCodeOnInvalidInput = 64,
    description = "Serve the materialized journal view, its rules, and the decisions gateway.")
public final class Main implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    @Option(names = "--journal", required = true, paramLabel = "<path>", description = "Journal to tail.")
    private Path journal;

    @Option(names = "--sequencer-url", required = true, paramLabel = "<url>",
        description = "Base URL of the sequencer API; every decision goes through it.")
    private URI sequencerUrl;

    @Option(names = "--config", paramLabel = "<dir>",
        description = "Directory holding categories.yaml and pins.yaml (SPEC §6). Without it every row reads UNCATEGORIZED.")
    private Path config;

    @Option(names = "--port", description = "(default: ${DEFAULT-VALUE})")
    private int port = 8085;

    @Option(names = "--bind", paramLabel = "<addr>", description = "(default: ${DEFAULT-VALUE})")
    private String bind = "127.0.0.1";

    @Option(names = "--poll-ms", paramLabel = "N",
        description = "Fallback journal read interval; file changes wake it sooner (default: ${DEFAULT-VALUE}).")
    private long pollMs = 10_000;

    public static void main(String[] args) {
        int exit = new CommandLine(new Main()).execute(args);
        if (exit != CommandLine.ExitCode.OK) {
            System.exit(exit);
        }
    }

    @Override
    public Integer call() {
        if (pollMs <= 0) {
            throw new CommandLine.ParameterException(new CommandLine(this), "--poll-ms must be positive");
        }
        // Without a rules file every row is UNCATEGORIZED except structural transfers: the column
        // still works and says, honestly, that nothing has been categorised yet.
        Categorizer categorizer = config == null
            ? new RuleCategorizer(List.of(), List.of(), List.of())
            : CategoryRules.load(config.resolve("categories.yaml"), config.resolve("pins.yaml"));

        JournalWatcher<JournalView> watcher =
            new JournalWatcher<>(journal, Clock.systemUTC(), CombinedFold::new).start(pollMs);
        GatewayServer server =
            new GatewayServer(watcher, new SequencerClient(sequencerUrl), categorizer, bind, port).start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            watcher.close();
        }));
        log.info("trex-gateway on http://{}:{} (journal {}, config {})", bind, server.port(), journal,
            config == null ? "none" : config);
        if (!bind.equals("127.0.0.1") && !bind.equals("localhost")) {
            // §5.7: this service writes the rule files and forwards decisions. It has no
            // authentication, so loopback is not a default here, it is the design.
            log.warn("trex-gateway is bound to {} with no authentication: it writes config and "
                + "forwards decisions, and SPEC §5.7 says it binds loopback only", bind);
        }
        return CommandLine.ExitCode.OK;
    }
}
