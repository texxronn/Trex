package trex.grid;

import trex.web.JournalWatcher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Clock;

/** {@code trex-grid --journal <path> [--port 8091] [--bind 127.0.0.1] [--poll-ms 10000]} */
public final class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    private static final String USAGE =
        "Usage: trex-grid --journal <path> [--port 8091] [--bind 127.0.0.1] [--poll-ms 10000]";

    private Main() {}

    public static void main(String[] args) {
        String journal = null;
        int port = 8091;
        String bind = "127.0.0.1";
        long pollMs = 10_000;
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--journal" -> journal = args[++i];
                    case "--port" -> port = Integer.parseInt(args[++i]);
                    case "--bind" -> bind = args[++i];
                    case "--poll-ms" -> pollMs = Long.parseLong(args[++i]);
                    default -> throw new IllegalArgumentException("unexpected argument: " + args[i]);
                }
            }
            if (journal == null || pollMs <= 0) {
                throw new IllegalArgumentException("missing required arguments");
            }
        } catch (RuntimeException e) {
            System.err.println(e.getMessage());
            System.err.println(USAGE);
            System.exit(64);
            return;
        }
        JournalWatcher<GridData> watcher = new JournalWatcher<>(Path.of(journal), Clock.systemUTC(), LinesFold::new).start(pollMs);
        GridServer server = new GridServer(watcher, bind, port).start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            watcher.close();
        }));
        log.info("trex-grid on http://{}:{} (journal {})", bind, server.port(), journal);
    }
}
