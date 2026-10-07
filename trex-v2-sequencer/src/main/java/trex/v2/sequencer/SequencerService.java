package trex.v2.sequencer;

import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import trex.v2.log.ConfigLoader;
import trex.v2.log.JsonlJournal;
import trex.v2.log.Recovery;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Clock;

/**
 * The sequencer role as a service (V2-PROPOSAL.md §5.3): recovery, the single-writer lock, the
 * journal and the HTTP API. One process; the role is this class, not a different artifact.
 */
public final class SequencerService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SequencerService.class);

    private final JournalLock lock;
    private final JsonlJournal journal;
    private final Sequencer sequencer;
    private final HttpServer server;
    private final Maintenance maintenance;
    private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();

    private SequencerService(JournalLock lock, JsonlJournal journal, Sequencer sequencer, HttpServer server,
                             Maintenance maintenance) {
        this.lock = lock;
        this.journal = journal;
        this.sequencer = sequencer;
        this.server = server;
        this.maintenance = maintenance;
    }

    /** Recover the journal, take the writer lock, load config and serve. Port 0 binds an ephemeral port. */
    public static SequencerService start(Path journalPath, Path configDir, String host, int port, Clock clock) {
        return start(journalPath, journalPath, configDir, host, port, clock, null);
    }

    public static SequencerService start(Path source, Path target, Path configDir, String host, int port, Clock clock) {
        return start(source, target, configDir, host, port, clock, null);
    }

    /**
     * Start against {@code target}, recovering from {@code source} (V2-PROPOSAL.md §6.4), with an
     * optional archive for the maintenance snapshot (§12.6).
     */
    public static SequencerService start(Path source, Path target, Path configDir, String host, int port, Clock clock,
                                         Path archive) {
        ConfigLoader.Loaded loaded = ConfigLoader.load(configDir);
        JournalLock lock = JournalLock.acquire(target);
        try {
            Recovery.recover(source, target);
            JsonlJournal journal = new JsonlJournal(target);
            java.util.Set<String> sources = SourceRegistry.load(configDir);
            Sequencer sequencer = new Sequencer(journal, loaded.registry(), loaded.config().categories(), clock,
                env(), sources.isEmpty() ? null : sources);
            Maintenance maintenance = new Maintenance(target, archive, sequencer);
            HttpServer server = HttpApi.start(host, port, sequencer, maintenance);
            log.info("trex sequencer listening on {}:{}; journal {} (head n={}); env [{}]; sources {}; archive {}",
                host, server.getAddress().getPort(), target, sequencer.headN(), env(),
                sources.isEmpty() ? "(any)" : sources, archive == null ? "(none)" : archive);
            if (!isLoopback(host)) {
                log.warn("API has no authentication and is bound to {}; anyone who can reach it can "
                    + "append facts and decisions", host);
            }
            return new SequencerService(lock, journal, sequencer, server, maintenance);
        } catch (IOException e) {
            lock.close();
            throw new UncheckedIOException("cannot start the sequencer", e);
        } catch (RuntimeException e) {
            lock.close();
            throw e;
        }
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public Sequencer sequencer() {
        return sequencer;
    }

    private static boolean isLoopback(String host) {
        try {
            return java.net.InetAddress.getByName(host).isLoopbackAddress();
        } catch (java.net.UnknownHostException e) {
            return false;
        }
    }

    /** The environment of this sequencer (§6): one log, one env; right-padded to 8. */
    static String env() {
        String raw = System.getenv("TREX_ENV");
        String value = raw == null || raw.isBlank() ? "Dev1" : raw.trim();
        if (value.length() > 8) {
            throw new IllegalArgumentException("TREX_ENV must be at most 8 characters: " + value);
        }
        return value + " ".repeat(8 - value.length());
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        server.stop(0);
        journal.close();
        lock.close();
        if (maintenance != null) {
            maintenance.close();
        }
    }
}
