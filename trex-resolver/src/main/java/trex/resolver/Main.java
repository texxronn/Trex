package trex.resolver;

import trex.category.CategoryRules;
import trex.category.Categorizer;
import trex.category.RuleCategorizer;
import trex.core.state.LedgerView;
import trex.web.JournalWatcher;

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
 * {@code trex-resolver --journal <path> --sequencer-url <url> [--categories <path>] [--port 8090] [--bind 127.0.0.1] [--poll-ms 10000]}
 */
@Command(name = "trex-resolver", mixinStandardHelpOptions = true,
    // 64 (EX_USAGE) is the documented contract; picocli would use 2.
    exitCodeOnInvalidInput = 64,
    description = "Admin web service for the HELD/REVIEW resolution workflow.")
public final class Main implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    @Option(names = "--journal", required = true, paramLabel = "<path>", description = "Journal to tail.")
    private Path journal;

    @Option(names = "--sequencer-url", required = true, paramLabel = "<url>",
        description = "Base URL of the sequencer API; every decision goes through it.")
    private URI sequencerUrl;

    @Option(names = "--categories", paramLabel = "<path>",
        description = "Category rules (SPEC §5.6), shown read-only beside each row.")
    private Path categories;

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
        // No rules file: every row reads UNCATEGORIZED apart from structural transfers, which is
        // the honest answer until categories.yaml exists.
        Categorizer categorizer = categories == null
            ? new RuleCategorizer(List.of(), List.of(), List.of())
            : CategoryRules.load(categories);

        JournalWatcher<LedgerView> watcher = new JournalWatcher<>(journal, Clock.systemUTC(), LedgerFold::new).start(pollMs);
        ResolverServer server = new ResolverServer(watcher, new SequencerClient(sequencerUrl), categorizer,
            bind, port).start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            watcher.close();
        }));
        log.info("trex-resolver on http://{}:{} (journal {}, sequencer {})", bind, server.port(), journal, sequencerUrl);
        if (!bind.equals("127.0.0.1") && !bind.equals("localhost")) {
            log.warn("no authentication; anyone who can reach {} can resolve transactions", bind);
        }
        return CommandLine.ExitCode.OK;
    }
}
