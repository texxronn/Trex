package trex.sequencer;

import trex.sequencer.config.Config;
import trex.sequencer.http.HttpApi;
import trex.sequencer.ingest.Sequencer;
import trex.sequencer.journal.JsonlJournal;
import trex.sequencer.journal.Recovery;
import trex.sequencer.state.Fold;
import trex.core.state.Ledger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Clock;

/** {@code trex-sequencer <configDir>}: recover, fold, serve. */
public final class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    private Main() {}

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: trex-sequencer <configDir>");
            System.exit(2);
        }
        Config config = Config.load(Path.of(args[0]));
        long head = Recovery.recover(config.journalSource(), config.journalTarget());
        JsonlJournal journal = new JsonlJournal(config.journalTarget());
        Ledger ledger = Fold.fold(journal);
        Sequencer sequencer = new Sequencer(journal, ledger, config.registry(), config.rules(), Clock.systemUTC());
        HttpApi api = new HttpApi(sequencer, config.bindHost(), config.bindPort(), HttpApi.DEFAULT_MAX_BODY_BYTES).start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("shutting down");
            api.close();
            journal.close();
        }));
        log.info("trex-sequencer listening on {}:{}; journal {} head={} n={}",
            config.bindHost(), api.port(), config.journalTarget(), head, ledger.highWaterN());
        if (!isLoopback(config.bindHost())) {
            log.warn("API has no authentication and is bound to {}; anyone who can reach it can "
                + "ingest candidates and make decisions", config.bindHost());
        }
    }

    private static boolean isLoopback(String host) {
        try {
            return java.net.InetAddress.getByName(host).isLoopbackAddress();
        } catch (java.net.UnknownHostException e) {
            return false;
        }
    }
}
