package trex.v2.sequencer;

import trex.v2.log.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The declared sources (V2-PROPOSAL.md §6): the 8-char codes a writing process may stamp on a line.
 * The sequencer refuses an unregistered source, so a typo cannot invent one. A missing file means
 * "no registry" — the sequencer then accepts any well-formed source (tests, minimal setups).
 */
final class SourceRegistry {

    private SourceRegistry() {}

    /** Git-tracked config; each entry is an 8-char code. */
    record File(List<String> sources) {}

    static Set<String> load(Path configDir) {
        Path file = configDir.resolve("sources.yaml");
        if (Files.notExists(file)) {
            return Set.of();
        }
        File parsed = Yaml.read(file, File.class);
        return parsed.sources() == null ? Set.of() : new LinkedHashSet<>(parsed.sources());
    }
}
