package trex.ingress.ing;

import java.net.URI;
import java.nio.file.Path;

/** {@code trex-ingress-ing --account <accountRef> --url http://trex:PORT [--batch-rows N] [--no-gzip] <file.csv>} */
public final class Main {

    private static final String USAGE =
        "Usage: trex-ingress-ing --account <accountRef> --url http://trex:PORT [--batch-rows N] [--no-gzip] <file.csv>";

    private Main() {}

    public static void main(String[] args) {
        String account = null;
        String url = null;
        String file = null;
        int batchRows = Integer.MAX_VALUE;
        boolean gzip = true;
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--account" -> account = args[++i];
                    case "--url" -> url = args[++i];
                    case "--batch-rows" -> batchRows = Integer.parseInt(args[++i]);
                    case "--no-gzip" -> gzip = false;
                    default -> {
                        if (file != null || args[i].startsWith("--")) {
                            throw new IllegalArgumentException("unexpected argument: " + args[i]);
                        }
                        file = args[i];
                    }
                }
            }
            if (account == null || url == null || file == null || batchRows <= 0) {
                throw new IllegalArgumentException("missing required arguments");
            }
        } catch (RuntimeException e) {
            System.err.println(e.getMessage());
            System.err.println(USAGE);
            System.exit(64);
            return;
        }
        System.exit(IngressRunner.run(account, URI.create(url), Path.of(file), batchRows, gzip, System.out));
    }
}
