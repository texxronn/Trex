package trex.egress.sqlite;

import java.nio.file.Path;

/** {@code trex-egress-sqlite --journal <path> --db <path> [--poll-seconds N] [--once]} */
public final class Main {

    private static final String USAGE =
        "Usage: trex-egress-sqlite --journal <path> --db <path> [--poll-seconds N] [--once]";

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
            do {
                int n = follower.pass();
                if (n > 0) {
                    System.out.println("mirrored " + n + " lines");
                }
                if (!once) {
                    Thread.sleep(pollSeconds * 1000);
                }
            } while (!once);
        }
    }
}
