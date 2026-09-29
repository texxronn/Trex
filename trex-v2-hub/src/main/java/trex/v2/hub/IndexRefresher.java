package trex.v2.hub;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import trex.v2.core.config.DeriveConfig;
import trex.v2.index.Indexer;
import trex.v2.log.ConfigLoader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * The hub's index refresher (V2-PROPOSAL.md §7.4, §9.2): it watches the journal and the config
 * directory, and on any change reloads the config and re-derives. The watcher is registered
 * <em>before</em> the first pass (the snapshot + delta rule), so nothing appended while the
 * snapshot is taken can fall between the two, and the persisted offset is the delta's start.
 *
 * <p>Rule edits and registry changes are ordinary file events; there is no separate apply step.
 * Refreshes are coalesced so a burst — a statement ingest, an editor writing several files — costs
 * one derivation, not one per event.
 */
final class IndexRefresher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(IndexRefresher.class);

    private final Path journal;
    private final Path journalDir;
    private final Path configDir;
    private final Indexer indexer;
    private final long debounceMs;
    private final WatchService watch;
    private final Thread worker;

    private volatile DeriveConfig config;
    private volatile boolean running = true;

    IndexRefresher(Path journal, Path configDir, Indexer indexer, DeriveConfig initial, long debounceMs) {
        this.journal = journal.toAbsolutePath();
        this.journalDir = this.journal.getParent();
        this.configDir = configDir.toAbsolutePath();
        this.indexer = indexer;
        this.config = initial;
        this.debounceMs = debounceMs;
        try {
            this.watch = FileSystems.getDefault().newWatchService();
            journalDir.register(watch, StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);
            configDir.register(watch, StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot watch " + journalDir + " and " + configDir, e);
        }
        this.worker = Thread.ofPlatform().daemon(true).name("trex-hub-refresh").start(this::loop);
    }

    DeriveConfig config() {
        return config;
    }

    /** Force one refresh now (tests and the CLI's first-pass hook). */
    void refreshNow() {
        refresh("manual");
    }

    private void loop() {
        refresh("initial");
        while (running) {
            try {
                WatchKey key = watch.poll(1, TimeUnit.SECONDS);
                if (key == null) {
                    continue;
                }
                boolean relevant = false;
                boolean overflow = false;
                // Drain the burst: keep taking keys for the debounce window.
                do {
                    for (WatchEvent<?> event : key.pollEvents()) {
                        if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                            overflow = true;
                        } else if (relevant(key, event)) {
                            relevant = true;
                        }
                    }
                    key.reset();
                    key = watch.poll(debounceMs, TimeUnit.MILLISECONDS);
                } while (key != null && running);
                if (relevant || overflow) {
                    refresh(overflow ? "overflow" : "change");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (java.nio.file.ClosedWatchServiceException e) {
                return;
            }
        }
    }

    private boolean relevant(WatchKey key, WatchEvent<?> event) {
        if (!(event.context() instanceof Path name)) {
            return false;
        }
        Object watchable = key.watchable();
        if (journalDir.equals(watchable)) {
            return name.toString().equals(journal.getFileName().toString());
        }
        if (configDir.equals(watchable)) {
            return name.toString().endsWith(".yaml");
        }
        return false;
    }

    private void refresh(String why) {
        try {
            DeriveConfig reloaded = ConfigLoader.load(configDir).config();
            if (!reloaded.configRevision().equals(config.configRevision())) {
                config = reloaded;
                indexer.setConfig(reloaded);
                log.info("config revision changed to {}; re-deriving", reloaded.configRevision());
            }
        } catch (RuntimeException e) {
            log.warn("config reload failed ({}) keeping {}: {}", why, config.configRevision(), e.getMessage());
        }
        try {
            if (Files.notExists(journal)) {
                return;
            }
            long size = Files.size(journal);
            if (indexer.offset() > size) {
                log.warn("journal shrank (offset {} > size {}); refolding from 0", indexer.offset(), size);
                indexer.rebuild(journal, Instant.now());
                return;
            }
            if (indexer.apply(journal, Instant.now())) {
                log.debug("index refreshed ({})", why);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (RuntimeException e) {
            log.error("index refresh failed ({})", why, e);
        }
    }

    @Override
    public void close() {
        running = false;
        try {
            watch.close();
        } catch (IOException e) {
            log.debug("closing the watcher failed", e);
        }
        worker.interrupt();
        try {
            worker.join(TimeUnit.SECONDS.toMillis(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
