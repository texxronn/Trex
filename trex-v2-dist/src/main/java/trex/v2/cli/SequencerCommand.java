package trex.v2.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import trex.v2.sequencer.SequencerService;

import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.Callable;

/** {@code trex sequencer}: the only writer, as a service (V2-PROPOSAL.md §5.3). */
@Command(name = "sequencer", mixinStandardHelpOptions = true,
    description = "Run the single writer: POST /facts, POST /decisions, GET /head.")
public final class SequencerCommand implements Callable<Integer> {

    @Option(names = "--journal", required = true, description = "Path to trex.jsonl.")
    Path journal;

    @Option(names = "--config", required = true, description = "Config directory.")
    Path config;

    @Option(names = "--host", defaultValue = "127.0.0.1", description = "Bind host.")
    String host;

    @Option(names = "--port", defaultValue = "8080", description = "Bind port.")
    int port;

    @Override
    public Integer call() throws Exception {
        SequencerService service = SequencerService.start(journal, config, host, port, Clock.systemUTC());
        System.out.println("trex sequencer listening on " + host + ":" + service.port()
            + " (journal " + journal + ")");
        try (service) {
            Thread.currentThread().join();
        }
        return 0;
    }
}
