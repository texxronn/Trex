package trex.egress.archive;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/** {@code trex-egress-archive --journal <path> --archive <path> [--poll-seconds N (fallback)] [--once]} */
public final class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    private static final String USAGE =
        "Usage: trex-egress-archive --journal <path> --archive <path> [--poll-seconds N (fallback)] [--once]";

    private Main() {}

    public static void main(String[] args) throws Exception {
        String journal = null;
        String archive = null;
        long pollSeconds = 30;
        boolean once = false;
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--journal" -> journal = args[++i];
                    case "--archive" -> archive = args[++i];
                    case "--poll-seconds" -> pollSeconds = Long.parseLong(args[++i]);
                    case "--once" -> once = true;
                    default -> throw new IllegalArgumentException("unexpected argument: " + args[i]);
                }
            }
            if (journal == null || archive == null || pollSeconds <= 0) {
                throw new IllegalArgumentException("missing required arguments");
            }
        } catch (RuntimeException e) {
            System.err.println(e.getMessage());
            System.err.println(USAGE);
            System.exit(64);
            return;
        }
        ArchiveFollower follower = new ArchiveFollower(Path.of(journal), Path.of(archive));
        if (once) {
            report(follower.pass());
        } else {
            follower.follow(pollSeconds * 1000, Main::report);
        }
    }

    private static void report(int archived) {
        if (archived > 0) {
            log.info("archived {} lines", archived);
        }
    }
}
