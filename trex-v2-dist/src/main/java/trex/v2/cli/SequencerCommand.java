package trex.v2.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import trex.v2.log.ConfigLoader;
import trex.v2.sequencer.SequencerService;

import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.Callable;

/**
 * {@code trex sequencer}: the only writer, as a service (V2-PROPOSAL.md §5.3). Bind host/port and
 * the journal paths come from {@code sequencer.yaml}; the flags override them for dev and tests.
 */
@Command(name = "sequencer", mixinStandardHelpOptions = true,
    description = "Run the single writer: POST /facts, /decisions, /ingest; GET /head; POST /maintenance/snapshot.")
public final class SequencerCommand implements Callable<Integer> {

    @Option(names = "--config", required = true, description = "Config directory (holds sequencer.yaml).")
    Path config;

    @Option(names = "--archive",
        description = "Archive root for the maintenance journal snapshot (V2-PROPOSAL.md §12.6).")
    Path archive;

    @Option(names = "--journal", description = "Override journal.target from sequencer.yaml (also the source unless --journal-source).")
    Path journal;

    @Option(names = "--journal-source",
        description = "Authoritative journal to materialize over the target at startup; the original is never written.")
    Path journalSource;

    @Option(names = "--host", description = "Override bindHost.")
    String host;

    @Option(names = "--port", description = "Override bindPort.")
    Integer port;

    @Override
    public Integer call() throws Exception {
        ConfigLoader.ServerConfig cfg = ConfigLoader.loadSequencer(config);
        Path target = journal != null ? journal : cfg.journalTarget();
        Path source = journalSource != null ? journalSource : (journal != null ? journal : cfg.journalSource());
        String bindHost = host != null ? host : cfg.host();
        int bindPort = port != null ? port : cfg.port();

        SequencerService service = SequencerService.start(source, target, config, bindHost, bindPort,
            Clock.systemUTC(), archive);
        Runtime.getRuntime().addShutdownHook(new Thread(service::close, "trex-sequencer-shutdown"));
        System.out.println("trex sequencer listening on " + bindHost + ":" + service.port()
            + " (journal " + target + (source.equals(target) ? "" : ", materialized from " + source) + ")");
        Thread.currentThread().join();
        return 0;
    }
}
