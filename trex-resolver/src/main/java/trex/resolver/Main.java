package trex.resolver;

import trex.core.state.LedgerView;
import trex.web.JournalWatcher;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;

/**
 * {@code trex-resolver --journal <path> --sequencer-url <url> [--port 8090] [--bind 127.0.0.1] [--poll-ms 10000]}
 */
public final class Main {

    private static final String USAGE =
        "Usage: trex-resolver --journal <path> --sequencer-url <url> [--port 8090] [--bind 127.0.0.1] [--poll-ms 10000]";

    private Main() {}

    public static void main(String[] args) {
        String journal = null;
        String sequencerUrl = null;
        int port = 8090;
        String bind = "127.0.0.1";
        long pollMs = 10_000;
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--journal" -> journal = args[++i];
                    case "--sequencer-url" -> sequencerUrl = args[++i];
                    case "--port" -> port = Integer.parseInt(args[++i]);
                    case "--bind" -> bind = args[++i];
                    case "--poll-ms" -> pollMs = Long.parseLong(args[++i]);
                    default -> throw new IllegalArgumentException("unexpected argument: " + args[i]);
                }
            }
            if (journal == null || sequencerUrl == null || pollMs <= 0) {
                throw new IllegalArgumentException("missing required arguments");
            }
        } catch (RuntimeException e) {
            System.err.println(e.getMessage());
            System.err.println(USAGE);
            System.exit(64);
            return;
        }
        JournalWatcher<LedgerView> watcher = new JournalWatcher<>(Path.of(journal), Clock.systemUTC(), LedgerFold::new).start(pollMs);
        ResolverServer server = new ResolverServer(watcher, new SequencerClient(URI.create(sequencerUrl)), bind, port).start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            watcher.close();
        }));
        System.out.printf("trex-resolver on http://%s:%d (journal %s, sequencer %s)%n", bind, server.port(), journal, sequencerUrl);
        if (!bind.equals("127.0.0.1") && !bind.equals("localhost")) {
            System.out.println("WARNING: no authentication; anyone who can reach this address can resolve transactions");
        }
    }
}
