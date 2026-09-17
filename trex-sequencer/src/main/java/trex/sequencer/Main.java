package trex.sequencer;

import trex.sequencer.config.Config;
import trex.sequencer.http.HttpApi;
import trex.sequencer.ingest.Sequencer;
import trex.sequencer.journal.JsonlJournal;
import trex.sequencer.journal.Recovery;
import trex.sequencer.state.Fold;
import trex.core.state.Ledger;

import java.nio.file.Path;
import java.time.Clock;

/** {@code trex-sequencer <configDir>}: recover, fold, serve. */
public final class Main {

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
            api.close();
            journal.close();
        }));
        System.out.printf("trex-sequencer listening on %s:%d; journal %s head=%d n=%d%n",
            config.bindHost(), api.port(), config.journalTarget(), head, ledger.highWaterN());
        if (!isLoopback(config.bindHost())) {
            System.out.println("WARNING: API has no authentication and is bound to " + config.bindHost()
                + "; anyone who can reach it can ingest candidates and make decisions");
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
