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
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.Callable;

/** {@code trex-sequencer <configDir>}: recover, fold, serve. */
@Command(name = "trex-sequencer", mixinStandardHelpOptions = true,
    // 64 (EX_USAGE) is the documented contract; picocli would use 2.
    exitCodeOnInvalidInput = 64,
    description = "The journal writer and HTTP API.")
public final class Main implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    @Parameters(index = "0", paramLabel = "<configDir>",
        description = "Directory holding sequencer.yaml, accounts.yaml and transfers.yaml.")
    private Path configDir;

    public static void main(String[] args) {
        int exit = new CommandLine(new Main()).execute(args);
        if (exit != CommandLine.ExitCode.OK) {
            System.exit(exit);
        }
    }

    @Override
    public Integer call() {
        Config config = Config.load(configDir);
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
        return CommandLine.ExitCode.OK;
    }

    private static boolean isLoopback(String host) {
        try {
            return java.net.InetAddress.getByName(host).isLoopbackAddress();
        } catch (java.net.UnknownHostException _) {
            return false;
        }
    }
}
