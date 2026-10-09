package trex.v2.hub;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Config drift (V2-QOL-IMPROVEMENTS-PLAN.md §3): the hub's read of the three versions of each config file
 * the compose {@code init} service seeds into the volume — the <em>shipped</em> file in the image
 * right now ({@code .shipped/<file>}), the shipped version last installed (<em>base</em>,
 * {@code .base/<file>}) and the live <em>current</em> file ({@code <file>}, possibly edited in the
 * Rules UI). The three-way comparison tells a file you never edited that the repo moved on
 * ({@code repo-newer}, safe for {@code sync-config}) from an edit of yours ({@code edited-here}) and
 * a collision ({@code both-changed}); without a base the tool refuses to guess ({@code unknown}).
 * Pure file reads; nothing is derived and nothing is stored.
 */
public final class ConfigDrift {

    /** The files the compose init service seeds, in the order the wire reports them. */
    public static final List<String> FILES = List.of(
        "sequencer.yaml", "accounts.yaml", "users.yaml", "categories.yaml", "transfers.yaml",
        "pins.yaml", "firefly.yaml", "statements.yaml", "sources.yaml", "schedule.yaml",
        "profiles.yaml");

    /** One file's comparison, as served by {@code GET /api/config/drift}. */
    public record Row(String file, String state) {}

    /** The table's states, with the wire spellings the UI and the sync script agree on. */
    public enum State {
        SAME("same"),
        REPO_NEWER("repo-newer"),
        EDITED_HERE("edited-here"),
        BOTH_CHANGED("both-changed"),
        UNKNOWN("unknown");

        private final String wire;

        State(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }
    }

    private ConfigDrift() {}

    /**
     * The table's three-way comparison (V2-QOL-IMPROVEMENTS-PLAN.md §3.2). {@code C == S} is {@code same}
     * — B is irrelevant. Otherwise a missing S (nothing to compare) or a missing B (no way to say
     * who moved) is {@code unknown}; {@code C == B} is {@code repo-newer} ({@code S != B} follows);
     * {@code S == B} is {@code edited-here}; all three different is {@code both-changed}. A
     * {@code null} array is an absent file, and an absent C is a difference from any present S or B
     * — a deleted file is an edit — as long as S and B allow classification.
     */
    public static State state(byte[] shipped, byte[] base, byte[] current) {
        if (shipped == null) {
            return State.UNKNOWN; // no S: cannot compare
        }
        if (Arrays.equals(current, shipped)) {
            return State.SAME;
        }
        if (base == null) {
            return State.UNKNOWN; // C differs and there is no B to say who moved
        }
        if (Arrays.equals(current, base)) {
            return State.REPO_NEWER;
        }
        if (Arrays.equals(shipped, base)) {
            return State.EDITED_HERE;
        }
        return State.BOTH_CHANGED;
    }

    /**
     * Reads {@code .shipped/<file>}, {@code .base/<file>} and {@code <file>} for every seeded file,
     * in {@link #FILES} order. An absent file is {@code null}; an unreadable one fails the read
     * rather than guessing.
     */
    public static List<Row> scan(Path configDir) throws IOException {
        List<Row> rows = new ArrayList<>(FILES.size());
        for (String file : FILES) {
            State state = state(
                read(configDir.resolve(".shipped").resolve(file)),
                read(configDir.resolve(".base").resolve(file)),
                read(configDir.resolve(file)));
            rows.add(new Row(file, state.wire()));
        }
        return rows;
    }

    private static byte[] read(Path file) throws IOException {
        return Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
    }
}
