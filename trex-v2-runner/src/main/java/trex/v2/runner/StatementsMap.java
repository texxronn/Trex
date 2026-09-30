package trex.v2.runner;

import trex.v2.log.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The filename → (sourceType, account) map (V2-PROPOSAL.md §12.5). It is git-tracked config with no
 * private data — patterns and refs only — so the upload list can show a sensible default for a known
 * statement and leave the operator to choose for anything else. The UI may always override.
 */
public final class StatementsMap {

    /** One rule; {@code match} is an exact name or a simple {@code *}/{@code ?} glob. */
    public record Entry(String match, String sourceType, String account) {}

    record File(List<Entry> files) {}

    private final List<Entry> entries;

    private StatementsMap(List<Entry> entries) {
        this.entries = entries;
    }

    public static StatementsMap load(Path configDir) {
        Path file = configDir.resolve("statements.yaml");
        if (Files.notExists(file)) {
            return new StatementsMap(List.of());
        }
        File parsed = Yaml.read(file, File.class);
        return new StatementsMap(parsed.files() == null ? List.of() : parsed.files());
    }

    public List<Entry> entries() {
        return entries;
    }

    public Optional<Entry> resolve(String fileName) {
        if (fileName == null) {
            return Optional.empty();
        }
        for (Entry entry : entries) {
            if (matches(entry.match(), fileName)) {
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    /** Exact (case-insensitive) first, then a glob; the file is ordered, so the first rule wins. */
    static boolean matches(String pattern, String name) {
        if (pattern == null) {
            return false;
        }
        if (pattern.equalsIgnoreCase(name)) {
            return true;
        }
        if (pattern.indexOf('*') < 0 && pattern.indexOf('?') < 0) {
            return false;
        }
        StringBuilder regex = new StringBuilder("(?i)");
        for (char c : pattern.toCharArray()) {
            if (c == '*') {
                regex.append(".*");
            } else if (c == '?') {
                regex.append('.');
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return name.matches(regex.toString());
    }
}
