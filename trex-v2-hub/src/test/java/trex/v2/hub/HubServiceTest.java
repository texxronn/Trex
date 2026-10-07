package trex.v2.hub;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trex.v2.core.Actor;
import trex.v2.core.Decision;
import trex.v2.core.Fact;
import trex.v2.core.Observation;
import trex.v2.core.Provenance;
import trex.v2.log.JsonlJournal;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hub follows the journal and re-derives on change without anyone asking it to (V2-PROPOSAL.md
 * §7.4, §9.2), and a config edit is picked up by the watcher and re-derived.
 */
class HubServiceTest {

    private static final Instant AT = Instant.parse("2026-09-30T00:00:00Z");

    @Test
    void followsTheJournalAndReDerivesOnChange(@TempDir Path dir) throws Exception {
        Path configDir = dir.resolve("config");
        config(configDir, "COLES");
        Path journal = dir.resolve("trex.jsonl");
        try (JsonlJournal j = new JsonlJournal(journal)) {
            j.appendBatch(java.util.List.of(fact(1, "a", "COLES 1234")));
        }

        Path index = dir.resolve("trex.sqlite");
        try (HubService hub = HubService.start(new HubConfig(journal, index, configDir, "127.0.0.1", 0, 50))) {
            await(() -> hub.status().counts().getOrDefault("txn_current", 0L) == 1L);
            assertEquals("GROCERIES", category(index, "a"));

            // A later append must be picked up by the watcher.
            try (JsonlJournal j = new JsonlJournal(journal)) {
                j.appendBatch(java.util.List.of(
                    fact(2, "b", "COLES 5678"),
                    new Decision.MarkExternal(3, "a", "ordinary", Actor.USER, "ron", AT)));
            }
            await(() -> hub.status().counts().getOrDefault("txn_current", 0L) == 2L
                && hub.status().counts().getOrDefault("decision", 0L) == 1L);
            await(() -> hub.head().lagBytes() == 0);

            // A rule edit is an ordinary file event: the watcher reloads and re-derives.
            config(configDir, "NEVERMATCH");
            await(() -> "UNCATEGORIZED".equals(category(index, "a")));
            assertTrue(hub.status().configRevision().startsWith("sha256:"));
        }
    }

    @Test
    void restartCatchesUpFromTheOffset(@TempDir Path dir) throws Exception {
        Path configDir = dir.resolve("config");
        config(configDir, "COLES");
        Path journal = dir.resolve("trex.jsonl");
        try (JsonlJournal j = new JsonlJournal(journal)) {
            j.appendBatch(java.util.List.of(fact(1, "a", "COLES 1234")));
        }
        Path index = dir.resolve("trex.sqlite");
        try (HubService first = HubService.start(new HubConfig(journal, index, configDir, "127.0.0.1", 0, 50))) {
            await(() -> first.status().counts().getOrDefault("txn_current", 0L) == 1L);
        }
        // Append while the hub is down, then restart: it catches up from the persisted offset.
        try (JsonlJournal j = new JsonlJournal(journal)) {
            j.appendBatch(java.util.List.of(fact(2, "b", "COLES 5678")));
        }
        try (HubService second = HubService.start(new HubConfig(journal, index, configDir, "127.0.0.1", 0, 50))) {
            await(() -> second.status().counts().getOrDefault("txn_current", 0L) == 2L);
        }
    }

    // ---- fixtures ---------------------------------------------------------------------------

    private static Fact fact(long n, String id, String raw) {
        return new Fact(n, id, "ing-savings", LocalDate.of(2026, 9, 1), -1000, 0, raw, null, 0,
            Observation.POSTED, "ing-csv", Provenance.BANK, null, "ing-csv/1", AT);
    }

    private static void config(Path configDir, String match) throws Exception {
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
                  match: "%s"
            """.formatted(match));
        Files.writeString(configDir.resolve("transfers.yaml"), """
            windowDays: 4
            allowlist:
              - 'Transfer'
            """);
    }

    private static String category(Path index, String externalId) {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + index.toAbsolutePath());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT category FROM category_current WHERE external_id = '" + externalId + "'")) {
            return rs.next() ? rs.getString(1) : null;
        } catch (Exception e) {
            throw new RuntimeException(e);
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
}
