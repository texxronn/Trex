package trex.v2.hub;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trex.v2.core.Fact;
import trex.v2.core.Observation;
import trex.v2.core.Provenance;
import trex.v2.hub.api.ReflowPreview;
import trex.v2.log.JsonlJournal;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** §15.6: a reflow preview diff equals the result of applying it (V2-PROPOSAL.md §9.3). */
class HubReflowTest {

    private static final Instant AT = Instant.parse("2026-09-30T00:00:00Z");

    @Test
    void previewDiffEqualsAppliedResult(@TempDir Path dir) throws Exception {
        Path configDir = dir.resolve("config");
        config(configDir, "COLES");
        Path journal = dir.resolve("trex.jsonl");
        try (JsonlJournal j = new JsonlJournal(journal)) {
            j.appendBatch(List.of(new Fact(1, "a", "ing-savings", LocalDate.of(2026, 9, 1), -1000, 0,
                "COLES 1234", null, 0, Observation.POSTED, "ing-csv", Provenance.BANK, null, "ing-csv/1", AT)));
        }

        String candidate = """
            categories: [GROCERIES]
            rules:
              - category: GROCERIES
                when:
                  match: "NEVERMATCH"
            """;

        Path index = dir.resolve("trex.sqlite");
        try (HubService hub = HubService.start(new HubConfig(journal, index, configDir, "127.0.0.1", 0, 50))) {
            await(() -> hub.status().counts().getOrDefault("txn_current", 0L) == 1L);
            assertEquals("GROCERIES", category(index, "a"));

            ReflowPreview preview = (ReflowPreview) hub.reflowPreview(candidate).body();
            assertEquals(1, preview.categoriesMoved());
            assertEquals("a", preview.moved().getFirst().externalId());
            assertEquals("GROCERIES", preview.moved().getFirst().from());
            assertEquals("UNCATEGORIZED", preview.moved().getFirst().to());

            // Apply the candidate: saving the file is the only gate.
            config(configDir, "NEVERMATCH");
            await(() -> "UNCATEGORIZED".equals(category(index, "a")));

            // Re-running the same preview now shows nothing to do (preview ≡ applied).
            ReflowPreview again = (ReflowPreview) hub.reflowPreview(candidate).body();
            assertEquals(0, again.categoriesMoved());
        }
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
