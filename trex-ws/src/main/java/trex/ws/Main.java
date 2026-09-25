package trex.ws;

import trex.ws.ledger.SequencerClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.Callable;

/**
 * {@code trex-ws --journal <path> --sequencer-url <url> [--config <dir>] [--port 8085] [--bind 127.0.0.1] [--poll-ms 10000]}
 * <p>
 * The consumer API (SPEC §5.7): the journal folded and categorised, served as a snapshot and a
 * stream, with the rule files and the decisions path to the sequencer behind it.
 */
@Command(name = "trex-ws", mixinStandardHelpOptions = true,
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

    @Option(names = "--admin-port", description = "Loopback only; every write lives here. "
        + "(default: ${DEFAULT-VALUE})")
    private int adminPort = 8085;

    @Option(names = "--port", description = "Pages and reads. (default: ${DEFAULT-VALUE})")
    private int port = 8090;

    @Option(names = "--bind", paramLabel = "<addr>",
        description = "Interface for the READ listener only. The admin listener is always "
            + "127.0.0.1. (default: ${DEFAULT-VALUE})")
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
        // Watched, not just loaded: an amendment that needs a restart is not an amendment, and
        // a file edited in an editor must behave exactly like one written through the API (§5.7).
        Rules rules = Rules.load(config).watch();

        JournalWatcher<JournalView> watcher =
            new JournalWatcher<>(journal, Clock.systemUTC(), CombinedFold::new).start(pollMs);
        SequencerClient sequencer = new SequencerClient(sequencerUrl);

        // Two listeners, one process (§5.7). While the rule writer was a separate service, the
        // process boundary kept it off the network; merging removed that, so the boundary is
        // explicit here. ADMIN refuses to bind anything but loopback — in its constructor, not by
        // convention — and READ simply has no mutating route registered on it.
        GatewayServer admin = new GatewayServer(watcher, sequencer, rules,
            "127.0.0.1", adminPort, 15_000, GatewayServer.Role.ADMIN).start();
        GatewayServer read = new GatewayServer(watcher, sequencer, rules,
            bind, port, 15_000, GatewayServer.Role.READ).start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            read.close();
            admin.close();
            watcher.close();
            rules.close();
        }));
        log.info("trex-ws pages on http://{}:{}, admin on http://127.0.0.1:{} "
            + "(journal {}, config {}, rules {})", bind, read.port(), admin.port(),
            journal, config == null ? "none" : config, rules.revision());
        return CommandLine.ExitCode.OK;
    }
}
