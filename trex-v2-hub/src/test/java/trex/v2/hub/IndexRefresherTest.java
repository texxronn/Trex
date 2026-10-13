package trex.v2.hub;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trex.v2.core.Fact;
import trex.v2.core.Observation;
import trex.v2.core.Provenance;
import trex.v2.core.config.DeriveConfig;
import trex.v2.index.Indexer;
import trex.v2.log.ConfigLoader;
import trex.v2.log.JsonlJournal;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The time clock is decoupled from the write clock (V2-INCREMENTAL-REFRESH-PLAN.md §2.3, §5 slice
 * 5): with no append, the refresher still forces a full derive on its cadence, so the time-driven
 * outputs keep up. A row-scoped patch would not, so the timer is the only thing advancing
 * {@code as_of} here.
 */
class IndexRefresherTest {

    private static final Instant AT = Instant.parse("2026-09-30T00:00:00Z");

    @Test
    void advancesAsOfOnTheTimerWithoutAnAppend(@TempDir Path dir) throws Exception {
        Path configDir = dir.resolve("config");
        config(configDir);
        Path journal = dir.resolve("trex.jsonl");
        try (JsonlJournal j = new JsonlJournal(journal)) {
            j.appendBatch(List.of(new Fact(1, "a", "ing-savings", LocalDate.of(2026, 9, 1), -1000, 0,
                "COLES 1234", null, 0, Observation.POSTED, "ing-csv", Provenance.BANK, null, "ing-csv/1", AT)));
        }
        Path index = dir.resolve("trex.sqlite");
        DeriveConfig initial = ConfigLoader.load(configDir).config();
        try (HubEvents events = new HubEvents();
             Indexer indexer = Indexer.open(index, initial);
             IndexRefresher refresher = new IndexRefresher(journal, configDir, indexer, initial,
                 20, 200, events, null)) {
            await(() -> readMeta(index, "as_of") != null);
            String first = readMeta(index, "as_of");
            // A 200 ms cadence and the 1 s idle poll advance as_of with no write in between.
            await(() -> !first.equals(readMeta(index, "as_of")));
        }
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        assertTrue(condition.getAsBoolean(), "condition not met within 10s");
    }

    /** Read a meta value through a second reader; the hub's writer is a WAL connection. */
    private static String readMeta(Path db, String key) {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
             var ps = c.prepareStatement("SELECT value FROM meta WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void config(Path configDir) throws Exception {
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("accounts.yaml"), """
            accounts:
              - ref: "ing-savings"
                currency: "AUD"
                balanceSource: statement
            """);
        Files.writeString(configDir.resolve("users.yaml"), """
            users:
              - id: "ron"
                name: "Ron"
                active: true
            """);
        Files.writeString(configDir.resolve("categories.yaml"), """
            categories: [GROCERIES]
            rules:
              - category: GROCERIES
                when:
                  match: "COLES"
            """);
        Files.writeString(configDir.resolve("transfers.yaml"), """
            windowDays: 4
            allowlist:
              - 'Transfer'
            """);
    }
}
