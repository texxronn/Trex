package trex.v2.hub;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trex.v2.core.Fact;
import trex.v2.core.Observation;
import trex.v2.core.Provenance;
import trex.v2.hub.api.ProjectionRequest;
import trex.v2.index.ProjectionRow;
import trex.v2.log.JsonlJournal;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §11.6: projection state is an accelerator that is <b>never</b> wiped by a re-derive — only
 * {@code --verify} replaces it.
 */
class HubProjectionTest {

    private static final Instant AT = Instant.parse("2026-09-30T00:00:00Z");

    @Test
    void projectionStateSurvivesADeriveAndReplace(@TempDir Path dir) throws Exception {
        Path configDir = dir.resolve("config");
        Files.createDirectories(configDir);
        config(configDir);
        Path journal = dir.resolve("trex.jsonl");
        try (JsonlJournal j = new JsonlJournal(journal)) {
            j.appendBatch(List.of(fact(1, "a")));
        }

        Path index = dir.resolve("trex.sqlite");
        try (HubService hub = HubService.start(new HubConfig(journal, index, configDir, "127.0.0.1", 0, 50))) {
            await(() -> hub.status().counts().getOrDefault("txn_current", 0L) == 1L);

            hub.putProjection(new ProjectionRequest(false, List.of(
                new ProjectionRow("u1", "EXTERNAL", "g1", "GROCERIES", "h", "cfg", "derive/1", "now"))));
            assertEquals(1, hub.projection().rows().size());

            // A new fact forces a full re-derive; the accelerator must not be wiped by it.
            try (JsonlJournal j = new JsonlJournal(journal)) {
                j.appendBatch(List.of(fact(2, "b")));
            }
            await(() -> hub.status().counts().getOrDefault("txn_current", 0L) == 2L);
            assertTrue(hub.projection().rows().stream().anyMatch(r -> r.unitId().equals("u1")),
                "a re-derive replaced the derived tables but left projection_state alone");

            hub.putProjection(new ProjectionRequest(true, List.of(
                new ProjectionRow("u2", "TRANSFER", "g2", "TRANSFER", "h2", "cfg", "derive/1", "now"))));
            assertEquals(List.of("u2"), hub.projection().rows().stream().map(ProjectionRow::unitId).toList(),
                "a --verify replace is wholesale");
        }
    }

    private static Fact fact(long n, String id) {
        return new Fact(n, id, "ing-savings", LocalDate.of(2026, 9, 1), -1000, 0, "COLES 1234", null, 0,
            Observation.POSTED, "ing-csv", Provenance.BANK, null, "ing-csv/1", AT);
    }

    private static void config(Path configDir) throws Exception {
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
