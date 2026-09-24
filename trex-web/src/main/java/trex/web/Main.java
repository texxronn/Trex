package trex.web;

import trex.category.CategoryRules;
import trex.category.Categorizer;
import trex.category.RuleCategorizer;
import trex.resolver.SequencerClient;

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
 * {@code trex-web --journal <path> --sequencer-url <url> [--config <dir>] [--port 8090] [--bind 127.0.0.1] [--poll-ms 10000]}
 * <p>
 * Browsing at {@code /}, the resolution workflow at {@code /resolve} (SPEC §5.4).
 */
@Command(name = "trex-web", mixinStandardHelpOptions = true,
    // 64 (EX_USAGE) is the documented contract; picocli would use 2.
    exitCodeOnInvalidInput = 64,
    description = "Browse the journal and resolve its HELD/REVIEW worklist.")
public final class Main implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    @Option(names = "--journal", required = true, paramLabel = "<path>", description = "Journal to tail.")
    private Path journal;

    @Option(names = "--sequencer-url", required = true, paramLabel = "<url>",
        description = "Base URL of the sequencer API; every decision goes through it.")
    private URI sequencerUrl;

    @Option(names = "--config", paramLabel = "<dir>",
        description = "Directory holding categories.yaml (SPEC §5.6). Without it every row reads UNCATEGORIZED.")
    private Path config;

    @Option(names = "--port", description = "(default: ${DEFAULT-VALUE})")
    private int port = 8090;

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
            : CategoryRules.load(config.resolve("categories.yaml"));

        JournalWatcher<JournalView> watcher =
            new JournalWatcher<>(journal, Clock.systemUTC(), CombinedFold::new).start(pollMs);
        WebServer server = new WebServer(watcher, new SequencerClient(sequencerUrl), categorizer, bind, port).start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            watcher.close();
        }));
        log.info("trex-web on http://{}:{} (journal {}, config {})", bind, server.port(), journal,
            config == null ? "none" : config);
        if (!bind.equals("127.0.0.1") && !bind.equals("localhost")) {
            log.warn("no authentication; anyone who can reach {} can resolve transactions", bind);
        }
        return CommandLine.ExitCode.OK;
    }
}
