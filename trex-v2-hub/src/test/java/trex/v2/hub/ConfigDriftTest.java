package trex.v2.hub;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trex.v2.hub.ConfigDrift.State;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The three-way config comparison (QOL_Improvements.md §3): one case per row of the table plus the
 * absent-side rules — absent S cannot compare, absent B with a differing C is unknown, and a
 * deleted C is a difference (a deletion is an edit) whenever S and B allow classification.
 */
class ConfigDriftTest {

    private static final byte[] SHIPPED = bytes("shipped");
    private static final byte[] BASE = bytes("base");
    private static final byte[] CURRENT = bytes("current");

    @Test
    void sameIsExactlyCurrentEqualsShipped() {
        assertEquals(State.SAME, ConfigDrift.state(SHIPPED, BASE, SHIPPED), "B is irrelevant");
        assertEquals(State.SAME, ConfigDrift.state(SHIPPED, null, SHIPPED), "B is not needed");
    }

    @Test
    void repoNewerIsCurrentEqualsBaseWithShippedMoved() {
        assertEquals(State.REPO_NEWER, ConfigDrift.state(bytes("new"), BASE, BASE));
    }

    @Test
    void editedHereIsShippedEqualsBaseWithCurrentMoved() {
        assertEquals(State.EDITED_HERE, ConfigDrift.state(SHIPPED, SHIPPED, CURRENT));
    }

    @Test
    void bothChangedIsAllThreeDifferent() {
        assertEquals(State.BOTH_CHANGED, ConfigDrift.state(SHIPPED, BASE, CURRENT));
    }

    @Test
    void unknownWhenShippedIsAbsent() {
        assertEquals(State.UNKNOWN, ConfigDrift.state(null, BASE, CURRENT), "no S to compare");
        assertEquals(State.UNKNOWN, ConfigDrift.state(null, BASE, BASE));
        assertEquals(State.UNKNOWN, ConfigDrift.state(null, null, null));
    }

    @Test
    void unknownWhenBaseIsAbsentAndCurrentDiffers() {
        assertEquals(State.UNKNOWN, ConfigDrift.state(SHIPPED, null, CURRENT));
    }

    @Test
    void deletedCurrentIsAnEdit() {
        assertEquals(State.EDITED_HERE, ConfigDrift.state(SHIPPED, SHIPPED, null));
        assertEquals(State.BOTH_CHANGED, ConfigDrift.state(SHIPPED, BASE, null));
        assertEquals(State.UNKNOWN, ConfigDrift.state(SHIPPED, null, null), "no base to classify a deletion");
    }

    @Test
    void wireSpellings() {
        assertEquals("same", State.SAME.wire());
        assertEquals("repo-newer", State.REPO_NEWER.wire());
        assertEquals("edited-here", State.EDITED_HERE.wire());
        assertEquals("both-changed", State.BOTH_CHANGED.wire());
        assertEquals("unknown", State.UNKNOWN.wire());
    }

    @Test
    void scanReadsTheThreeDirectoriesInTheSeededOrder(@TempDir Path dir) throws Exception {
        Path shipped = Files.createDirectories(dir.resolve(".shipped"));
        Path base = Files.createDirectories(dir.resolve(".base"));
        Files.write(shipped.resolve("accounts.yaml"), SHIPPED);
        Files.write(base.resolve("accounts.yaml"), SHIPPED);
        Files.write(dir.resolve("accounts.yaml"), CURRENT); // edited-here

        Files.write(shipped.resolve("profiles.yaml"), SHIPPED); // no live file, no base -> unknown

        Files.write(shipped.resolve("users.yaml"), CURRENT);
        Files.write(dir.resolve("users.yaml"), CURRENT); // same without a base

        List<ConfigDrift.Row> rows = ConfigDrift.scan(dir);
        assertEquals(ConfigDrift.FILES, rows.stream().map(ConfigDrift.Row::file).toList());
        assertEquals(11, rows.size());
        assertEquals("edited-here", row(rows, "accounts.yaml").state());
        assertEquals("unknown", row(rows, "profiles.yaml").state());
        assertEquals("same", row(rows, "users.yaml").state());
        assertEquals("unknown", row(rows, "sequencer.yaml").state(), "no .shipped copy");
    }

    private static ConfigDrift.Row row(List<ConfigDrift.Row> rows, String file) {
        return rows.stream().filter(r -> r.file().equals(file)).findFirst().orElseThrow();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
