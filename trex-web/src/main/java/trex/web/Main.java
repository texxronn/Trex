package trex.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.net.URI;
import java.util.concurrent.Callable;

/**
 * {@code trex-web [--gateway-url <url>] [--port 8090] [--bind 127.0.0.1]}
 * <p>
 * The pages and nothing else (SPEC §5.4). No {@code --journal}, no {@code --config} and no
 * {@code --sequencer-url}: it reads no file and knows one upstream, trex-gateway, which owns the
 * fold, the rule files and the decisions path.
 */
@Command(name = "trex-web", mixinStandardHelpOptions = true,
    // 64 (EX_USAGE) is the documented contract; picocli would use 2.
    exitCodeOnInvalidInput = 64,
    description = "Serve the trex pages against a trex-gateway.")
public final class Main implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    @Option(names = "--gateway-url", paramLabel = "<url>",
        description = "Base URL of trex-gateway (default: ${DEFAULT-VALUE}).")
    private URI gatewayUrl = URI.create("http://127.0.0.1:8085");

    @Option(names = "--port", description = "(default: ${DEFAULT-VALUE})")
    private int port = 8090;

    @Option(names = "--bind", paramLabel = "<addr>", description = "(default: ${DEFAULT-VALUE})")
    private String bind = "127.0.0.1";

    public static void main(String[] args) {
        int exit = new CommandLine(new Main()).execute(args);
        if (exit != CommandLine.ExitCode.OK) {
            System.exit(exit);
        }
    }

    @Override
    public Integer call() {
        WebServer server = new WebServer(new Upstream(gatewayUrl), bind, port).start();
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        log.info("trex-web on http://{}:{} (gateway {})", bind, server.port(), gatewayUrl);
        if (!bind.equals("127.0.0.1") && !bind.equals("localhost")) {
            log.warn("no authentication; anyone who can reach {} can resolve transactions", bind);
        }
        return CommandLine.ExitCode.OK;
    }
}
