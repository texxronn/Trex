package trex.egress.firefly;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC §5.8. The cache is an accelerator: everything in it is recoverable from Firefly, so the
 * tests that matter are about it being cheap to throw away, not about it being durable.
 */
class ProjectionCacheTest {

    @TempDir
    Path dir;

    private ProjectionCache open() throws SQLException {
        return new ProjectionCache(dir.resolve("cache.db"));
    }

    private static ProjectionCache.Row row(String id, long n, String group, String category) {
        return new ProjectionCache.Row(id, n, group, category, "rev1");
    }

    @Test
    void remembersWhatWasProjectedAndAsWhat() throws SQLException {
        try (ProjectionCache c = open()) {
            c.record(row("a", 10, "100", "GROCERIES"));
            c.record(row("b", 11, "101", "SHOPPING"));
            assertEquals(2, c.all().size());
            assertEquals("GROCERIES", c.all().get("a").category());
            assertEquals("100", c.all().get("a").groupId());
        }
    }

    /**
     * The category recorded is what was SENT, not what is current — that is the whole point of
     * the column. Re-projecting the same transaction under a new category overwrites it.
     */
    @Test
    void recordingTheSameTransactionAgainUpdatesIt() throws SQLException {
        try (ProjectionCache c = open()) {
            c.record(row("a", 10, "100", "GROCERIES"));
            c.record(row("a", 12, "100", "FOOD"));
            assertEquals(1, c.all().size());
            assertEquals("FOOD", c.all().get("a").category());
            assertEquals(12, c.all().get("a").n(), "a corrected line moves n too");
        }
    }

    @Test
    void survivesReopening() throws SQLException {
        try (ProjectionCache c = open()) {
            c.record(row("a", 10, "100", "GROCERIES"));
            c.highWater(1883);
            c.rulesRevision("83f272f8");
        }
        try (ProjectionCache c = open()) {
            assertEquals(1, c.all().size());
            assertEquals(1883, c.highWater());
            assertEquals("83f272f8", c.rulesRevision());
        }
    }

    @Test
    void startsEmptyRatherThanFailing() throws SQLException {
        try (ProjectionCache c = open()) {
            assertTrue(c.all().isEmpty());
            assertEquals(0, c.highWater(), "nothing projected yet means start from the beginning");
            assertNull(c.rulesRevision());
        }
    }

    /**
     * The rebuild: what a deleted cache and a {@code --verify} both do. Replacing wholesale rather
     * than merging is deliberate — Firefly is the authority on what it holds, so anything the
     * cache believes that Firefly does not is wrong by definition.
     */
    @Test
    void rebuildReplacesEverything() throws SQLException {
        try (ProjectionCache c = open()) {
            c.record(row("stale", 1, "1", "GROCERIES"));
            c.record(row("alsoStale", 2, "2", "SHOPPING"));
            c.replaceAll(List.of(row("a", 10, "100", "FOOD"), row("b", 11, "101", "BILLS")));

            assertEquals(2, c.all().size());
            assertNull(c.all().get("stale"), "a row Firefly does not have is not a row");
            assertEquals("FOOD", c.all().get("a").category());
        }
    }

    /** Deleting the file costs nothing but a rebuild — the property the design depends on. */
    @Test
    void deletingTheFileLosesNothingThatMatters() throws Exception {
        try (ProjectionCache c = open()) {
            c.record(row("a", 10, "100", "GROCERIES"));
            c.highWater(500);
        }
        java.nio.file.Files.delete(dir.resolve("cache.db"));
        try (ProjectionCache c = open()) {
            assertTrue(c.all().isEmpty());
            assertEquals(0, c.highWater());
            // ...and the next run repopulates it from Firefly, which is FireflyEgress.rebuildCache.
        }
    }
}
