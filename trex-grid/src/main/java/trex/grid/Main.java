package trex.grid;

import trex.category.CategoryRules;
import trex.category.Categorizer;
import trex.category.RuleCategorizer;
import trex.web.JournalWatcher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.Callable;

/** {@code trex-grid --journal <path> [--categories <path>] [--port 8091] [--bind 127.0.0.1] [--poll-ms 10000]} */
@Command(name = "trex-grid", mixinStandardHelpOptions = true,
    // 64 (EX_USAGE) is the documented contract; picocli would use 2.
    exitCodeOnInvalidInput = 64,
    description = "Read-only, live table of the journal.")
public final class Main implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    @Option(names = "--journal", required = true, paramLabel = "<path>", description = "Journal to tail.")
    private Path journal;

    @Option(names = "--categories", paramLabel = "<path>",
        description = "Category rules (SPEC §5.6). Without them every row reads UNCATEGORIZED.")
    private Path categories;

    @Option(names = "--port", description = "(default: ${DEFAULT-VALUE})")
    private int port = 8091;

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
        Categorizer categorizer = categories == null
            ? new RuleCategorizer(List.of(), List.of(), List.of())
            : CategoryRules.load(categories);

        JournalWatcher<GridData> watcher = new JournalWatcher<>(journal, Clock.systemUTC(), LinesFold::new).start(pollMs);
        GridServer server = new GridServer(watcher, categorizer, bind, port).start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            watcher.close();
        }));
        log.info("trex-grid on http://{}:{} (journal {}, categories {})", bind, server.port(), journal,
            categories == null ? "none" : categories);
        return CommandLine.ExitCode.OK;
    }
}
