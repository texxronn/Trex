package trex.egress.sqlite;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/** {@code trex-egress-sqlite --journal <path> --db <path> [--poll-seconds N (fallback)] [--once]} */
public final class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    private static final String USAGE =
        "Usage: trex-egress-sqlite --journal <path> --db <path> [--poll-seconds N (fallback)] [--once]";

    private Main() {}

    public static void main(String[] args) throws Exception {
        String journal = null;
        String database = null;
        long pollSeconds = 30;
        boolean once = false;
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--journal" -> journal = args[++i];
                    case "--db" -> database = args[++i];
                    case "--poll-seconds" -> pollSeconds = Long.parseLong(args[++i]);
                    case "--once" -> once = true;
                    default -> throw new IllegalArgumentException("unexpected argument: " + args[i]);
                }
            }
            if (journal == null || database == null || pollSeconds <= 0) {
                throw new IllegalArgumentException("missing required arguments");
            }
        } catch (RuntimeException e) {
            System.err.println(e.getMessage());
            System.err.println(USAGE);
            System.exit(64);
            return;
        }
        try (SqliteFollower follower = new SqliteFollower(Path.of(journal), Path.of(database))) {
            if (once) {
                report(follower.pass());
            } else {
                follower.follow(pollSeconds * 1000, Main::report);
            }
        }
    }

    private static void report(int mirrored) {
        if (mirrored > 0) {
            log.info("mirrored {} lines", mirrored);
        }
    }
}
