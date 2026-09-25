package trex.egress.sqlite;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/** {@code trex-egress-sqlite --journal <path> --db <path> [--poll-seconds N (fallback)] [--once]} */
@Command(name = "sqlite", mixinStandardHelpOptions = true,
    // 64 (EX_USAGE) is the documented contract; picocli would use 2.
    exitCodeOnInvalidInput = 64,
    description = "Mirror the journal into a SQLite database (WAL).")
public final class SqliteCommand implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(SqliteCommand.class);

    @Option(names = "--journal", required = true, paramLabel = "<path>", description = "Journal to tail.")
    private Path journal;

    @Option(names = "--db", required = true, paramLabel = "<path>", description = "SQLite database file.")
    private Path database;

    @Option(names = "--poll-seconds", paramLabel = "N",
        description = "Fallback wake interval; journal changes wake it sooner (default: ${DEFAULT-VALUE}).")
    private long pollSeconds = 30;

    @Option(names = "--once", description = "Drain what is there and exit.")
    private boolean once;


    @Override
    public Integer call() throws Exception {
        if (pollSeconds <= 0) {
            throw new CommandLine.ParameterException(new CommandLine(this), "--poll-seconds must be positive");
        }
        try (SqliteFollower follower = new SqliteFollower(journal, database)) {
            if (once) {
                report(follower.pass());
            } else {
                follower.follow(pollSeconds * 1000, SqliteCommand::report);
            }
        }
        return CommandLine.ExitCode.OK;
    }

    private static void report(int mirrored) {
        if (mirrored > 0) {
            log.info("mirrored {} lines", mirrored);
        }
    }
}
