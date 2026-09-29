package trex.v2.sequencer;

import com.sun.net.httpserver.HttpServer;
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

    private final JournalLock lock;
    private final JsonlJournal journal;
    private final Sequencer sequencer;
    private final HttpServer server;

    private SequencerService(JournalLock lock, JsonlJournal journal, Sequencer sequencer, HttpServer server) {
        this.lock = lock;
        this.journal = journal;
        this.sequencer = sequencer;
        this.server = server;
    }

    /** Recover the journal, take the writer lock, load config and serve. Port 0 binds an ephemeral port. */
    public static SequencerService start(Path journalPath, Path configDir, String host, int port, Clock clock) {
        return start(journalPath, journalPath, configDir, host, port, clock);
    }

    /**
     * Start against {@code target}, recovering from {@code source} (V2-PROPOSAL.md §6.4). When
     * source and target differ, source is authoritative and is byte-copied over the target at
     * startup, SHA-256 verified: the sequencer then writes to the copy and never mutates the
     * original. This is how an alternative journal can be fed to the writer.
     */
    public static SequencerService start(Path source, Path target, Path configDir, String host, int port, Clock clock) {
        ConfigLoader.Loaded loaded = ConfigLoader.load(configDir);
        JournalLock lock = JournalLock.acquire(target);
        try {
            Recovery.recover(source, target);
            JsonlJournal journal = new JsonlJournal(target);
            Sequencer sequencer = new Sequencer(journal, loaded.registry(), loaded.config().categories(), clock);
            HttpServer server = HttpApi.start(host, port, sequencer);
            return new SequencerService(lock, journal, sequencer, server);
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

    @Override
    public void close() {
        server.stop(0);
        journal.close();
        lock.close();
    }
}
