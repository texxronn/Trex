package trex.v2.hub;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trex.v2.core.Fact;
import trex.v2.core.Observation;
import trex.v2.core.Provenance;
import trex.v2.hub.api.CursorRequest;
import trex.v2.log.EvidenceStore;
import trex.v2.log.JsonlJournal;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Feed cursors (source_cursor) and evidence indexing (V2-PROPOSAL.md §7.2, §12.2). */
class HubEvidenceCursorsTest {

    private static final Instant AT = Instant.parse("2026-09-30T00:00:00Z");

    @Test
    void indexesEvidenceAndStoresFeedCursors(@TempDir Path dir) throws Exception {
        Path configDir = dir.resolve("config");
        Files.createDirectories(configDir);
        config(configDir);
        Path journal = dir.resolve("trex.jsonl");
        try (JsonlJournal j = new JsonlJournal(journal)) {
            j.appendBatch(List.of(new Fact(1, "a", "ing-savings", LocalDate.of(2026, 9, 1), -1000, 0,
                "COLES 1234", null, 0, Observation.POSTED, "ing-csv", Provenance.BANK, null, "ing-csv/1", AT)));
        }
        Path evidenceDir = dir.resolve("evidence");
        new EvidenceStore(evidenceDir).put("the statement bytes".getBytes(StandardCharsets.UTF_8));

        Path index = dir.resolve("trex.sqlite");
        try (HubService hub = HubService.start(new HubConfig(journal, index, configDir, "127.0.0.1", 0, 50,
                null, evidenceDir))) {
            await(() -> hub.status().counts().getOrDefault("evidence", 0L) == 1L);

            assertTrue(hub.cursors().cursors().isEmpty());
            hub.putCursors(new CursorRequest(Map.of("cdr-feed", "cursor-1")));
            assertEquals("cursor-1", hub.cursors().cursors().get("cdr-feed"));

            // A second tick advances the cursor.
            hub.putCursors(new CursorRequest(Map.of("cdr-feed", "cursor-2")));
            assertEquals("cursor-2", hub.cursors().cursors().get("cdr-feed"));
        }
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
