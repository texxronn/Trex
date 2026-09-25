package trex.ws;

import trex.category.Categorizer;
import trex.journal.JournalChanges;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The live rule set: the compiled {@link Categorizer}, its revision, and the only way to change
 * either. SPEC §5.7.
 * <p>
 * A rule set used to be loaded once at startup and read forever, which is why a {@link Categorizer}
 * could be passed around as a plain value. Now it can change while the service runs — from an API
 * call or from someone editing the file in an editor — so readers take it from here each time
 * rather than holding it. The field is volatile and replaced whole: a categorisation in flight
 * finishes against the rule set it started with, and never sees half of one.
 * <p>
 * <b>A failed reload keeps the previous rule set.</b> Serving the categories from ten seconds ago
 * is always better than serving none, and the file on disk is already refused by the writer if it
 * would not load — so a failure here means a hand edit, and the log says which.
 */
public final class Rules implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Rules.class);

    /** Long enough to let an editor finish writing, short enough that a save feels immediate. */
    private static final long SETTLE_MS = 150;

    private final RuleStore store;
    private volatile Categorizer categorizer;
    private volatile String revision;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private JournalChanges changes;
    private Thread watcher;
    private volatile boolean running = true;

    public Rules(RuleStore store, Categorizer initial, String revision) {
        this.store = store;
        this.categorizer = initial;
        this.revision = revision;
    }

    /** Load from disk, or fall back to an empty rule set when no config directory was given. */
    public static Rules load(Path configDir) {
        if (configDir == null) {
            // Every row reads UNCATEGORIZED except structural transfers: the column still works
            // and says, honestly, that nothing has been categorised yet.
            Categorizer empty = new trex.category.RuleCategorizer(List.of(), List.of(), List.of());
            return new Rules(null, empty, "none");
        }
        RuleStore store = new RuleStore(configDir.resolve("categories.yaml"), configDir.resolve("pins.yaml"));
        return new Rules(store, store.load(), store.revision());
    }

    public Categorizer categorizer() {
        return categorizer;
    }

    public String revision() {
        return revision;
    }

    /** Null when the service was started without a config directory; writes are then refused. */
    public RuleStore store() {
        return store;
    }

    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    /**
     * Re-read both files. Returns true if the rule set changed; a failure is logged and swallowed,
     * leaving the previous one in place.
     */
    public boolean reload() {
        if (store == null) {
            return false;
        }
        String next = store.revision();
        if (next.equals(revision)) {
            return false;
        }
        try {
            Categorizer loaded = store.load();
            this.categorizer = loaded;
            this.revision = next;
            log.info("rules reloaded: revision {}", next);
            listeners.forEach(Runnable::run);
            return true;
        } catch (RuntimeException e) {
            // Deliberately not fatal and deliberately not retried into a loop: the running rule
            // set is still good, and the next save will try again.
            log.error("rules on disk will not load, keeping revision {}: {}", revision, e.getMessage());
            return false;
        }
    }

    /**
     * Watch the config directory so an edit takes effect without a restart (§5.7). An amendment
     * that needs a restart is not an amendment, and a hand edit must behave exactly like an API
     * call — same files, same reload, same revision.
     */
    public Rules watch() {
        if (store == null) {
            return this;
        }
        changes = new JournalChanges(store.categoriesFile());
        watcher = Thread.ofVirtual().name("rules-watch").start(() -> {
            while (running) {
                try {
                    changes.await(2_000);
                    if (!running) {
                        return;
                    }
                    // An editor often writes in several steps; settling avoids reading a half-file
                    // and then reporting a failure that was never real.
                    Thread.sleep(SETTLE_MS);
                    reload();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (RuntimeException e) {
                    log.debug("rules watch hiccup: {}", e.toString());
                }
            }
        });
        return this;
    }

    @Override
    public void close() {
        running = false;
        if (watcher != null) {
            watcher.interrupt();
        }
        if (changes != null) {
            changes.close();
        }
    }
}
