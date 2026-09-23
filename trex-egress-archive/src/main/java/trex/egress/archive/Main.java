package trex.egress.archive;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/** {@code trex-egress-archive --journal <path> --archive <path> [--poll-seconds N (fallback)] [--once]} */
@Command(name = "trex-egress-archive", mixinStandardHelpOptions = true,
    // 64 (EX_USAGE) is the documented contract; picocli would use 2.
    exitCodeOnInvalidInput = 64,
    description = "Mirror the journal into an append-only archive file.")
public final class Main implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    @Option(names = "--journal", required = true, paramLabel = "<path>", description = "Journal to tail.")
    private Path journal;

    @Option(names = "--archive", required = true, paramLabel = "<path>", description = "Archive file to append to.")
    private Path archive;

    @Option(names = "--poll-seconds", paramLabel = "N",
        description = "Fallback wake interval; journal changes wake it sooner (default: ${DEFAULT-VALUE}).")
    private long pollSeconds = 30;

    @Option(names = "--once", description = "Drain what is there and exit.")
    private boolean once;

    public static void main(String[] args) {
        int exit = new CommandLine(new Main()).execute(args);
        if (exit != CommandLine.ExitCode.OK) {
            System.exit(exit);
        }
    }

    @Override
    public Integer call() throws Exception {
        if (pollSeconds <= 0) {
            throw new CommandLine.ParameterException(new CommandLine(this), "--poll-seconds must be positive");
        }
        ArchiveFollower follower = new ArchiveFollower(journal, archive);
        if (once) {
            report(follower.pass());
        } else {
            follower.follow(pollSeconds * 1000, Main::report);
        }
        return CommandLine.ExitCode.OK;
    }

    private static void report(int archived) {
        if (archived > 0) {
            log.info("archived {} lines", archived);
        }
    }
}
