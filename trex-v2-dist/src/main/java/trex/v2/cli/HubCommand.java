package trex.v2.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import trex.v2.hub.HubConfig;
import trex.v2.hub.HubService;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * {@code trex hub}: the index owner, the blotter API and (later) the UI (V2-PROPOSAL.md §7.4).
 * It watches the journal and the config, applies and re-derives, and holds the index lock.
 */
@Command(name = "hub", mixinStandardHelpOptions = true,
    description = "Run the index owner and blotter API: watches the journal and config.")
public final class HubCommand implements Callable<Integer> {

    @Option(names = "--journal", required = true, description = "Path to trex.jsonl (read-only).")
    Path journal;

    @Option(names = "--config", required = true, description = "Config directory.")
    Path config;

    @Option(names = "--index", required = true, description = "Path to trex.sqlite (owned by the hub).")
    Path index;

    @Option(names = "--host", defaultValue = "127.0.0.1", description = "Bind host.")
    String host;

    @Option(names = "--port", defaultValue = "8090", description = "Bind port.")
    int port;

    @Option(names = "--sequencer-url",
        description = "The only writer's base URL; without it the hub is read-only.")
    String sequencerUrl;

    @Override
    public Integer call() throws Exception {
        HubConfig hubConfig = new HubConfig(journal, index, config, host, port,
            HubConfig.DEFAULT_DEBOUNCE_MS, sequencerUrl);
        HubService service = HubService.start(hubConfig);
        Runtime.getRuntime().addShutdownHook(new Thread(service::close, "trex-hub-shutdown"));
        System.out.println("trex hub listening on " + host + ":" + service.port()
            + " (journal " + journal + ", index " + index + ")");
        Thread.currentThread().join();
        return 0;
    }
}
