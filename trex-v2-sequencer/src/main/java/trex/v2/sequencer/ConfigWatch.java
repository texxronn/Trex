package trex.v2.sequencer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import trex.v2.core.config.Registry;
import trex.v2.core.config.RuleSet;
import trex.v2.log.ConfigLoader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Keeps the writer's validation config in step with the config directory (V2-REVIEW-FIXES-PLAN.md
 * §8). The hub reloads on file change; a sequencer that read its config once rejected the first
 * batch naming a category, account or user added after it started — the hub's precheck passed and
 * the write failed until a restart.
 *
 * <p>No watcher thread: the writer asks before each write, inside its own lock, so a batch is
 * always validated against one consistent config. The check is a fingerprint of the directory's
 * YAML files (name, size, mtime); a change reloads all three inputs together. A config that does
 * not load keeps the last good one and logs why — a typo must not take the writer down.
 */
final class ConfigWatch {

    private static final Logger log = LoggerFactory.getLogger(ConfigWatch.class);

    /** What the writer validates against, swapped whole. */
    record Validation(Registry registry, RuleSet categories, Set<String> sources) {}

    private final Path configDir;
    private String fingerprint;

    ConfigWatch(Path configDir) {
        this.configDir = configDir;
        this.fingerprint = fingerprint(configDir);
    }

    /** Load the current config directory as the writer sees it; null sources means any. */
    static Validation load(Path configDir) {
        ConfigLoader.Loaded loaded = ConfigLoader.load(configDir);
        Set<String> sources = SourceRegistry.load(configDir);
        return new Validation(loaded.registry(), loaded.config().categories(), sources.isEmpty() ? null : sources);
    }

    /** The new validation config when the directory changed and loads cleanly, else null. */
    Validation changed() {
        String now = fingerprint(configDir);
        if (now.equals(fingerprint)) {
            return null;
        }
        fingerprint = now;
        try {
            Validation next = load(configDir);
            log.info("config changed in {}; the writer now validates against it", configDir);
            return next;
        } catch (RuntimeException e) {
            log.error("config in {} does not load; keeping the last good config: {}", configDir, e.getMessage());
            return null;
        }
    }

    private static String fingerprint(Path dir) {
        try (Stream<Path> files = Files.list(dir)) {
            StringBuilder out = new StringBuilder();
            for (Path f : files.filter(p -> p.getFileName().toString().endsWith(".yaml")).sorted().toList()) {
                out.append(f.getFileName()).append('|').append(Files.size(f)).append('|')
                    .append(Files.getLastModifiedTime(f).toMillis()).append('\n');
            }
            return out.toString();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list config directory " + dir, e);
        }
    }
}
