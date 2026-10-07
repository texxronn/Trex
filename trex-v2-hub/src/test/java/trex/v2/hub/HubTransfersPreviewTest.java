package trex.v2.hub;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trex.v2.core.Fact;
import trex.v2.core.Observation;
import trex.v2.core.Provenance;
import trex.v2.hub.api.TransferPreview;
import trex.v2.log.JsonlJournal;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** §9.3/§9.9.C: a candidate {@code transfers.yaml} previews the legs potted and the pairs made. */
class HubTransfersPreviewTest {

    private static final Instant AT = Instant.parse("2026-09-30T00:00:00Z");

    @Test
    void previewsTheLegsPottedAndPairsMade(@TempDir Path dir) throws Exception {
        Path configDir = dir.resolve("config");
        config(configDir);
        Path journal = dir.resolve("trex.jsonl");
        try (JsonlJournal j = new JsonlJournal(journal)) {
            j.appendBatch(List.of(
                fact(1, "a", "ing-savings", -1000, "Transfer to Savings 4321"),
                fact(2, "b", "ing-orange", 1000, "Transfer from Savings 4321")));
        }

        Path index = dir.resolve("trex.sqlite");
        try (HubService hub = HubService.start(new HubConfig(journal, index, configDir, "127.0.0.1", 0, 50))) {
            await(() -> hub.status().counts().getOrDefault("txn_current", 0L) == 2L);
            assertEquals(0, hub.transfers().size(), "no patterns shape anything yet");

            String candidate = """
                windowDays: 4
                transferPatterns:
                  default:
                    - { match: 'Transfer', rail: BANK_TRANSFER }
                """;
            DecisionOutcome outcome = hub.transfersPreview(candidate);
            assertEquals(200, outcome.status(), String.valueOf(outcome.body()));
            TransferPreview preview = (TransferPreview) outcome.body();
            assertEquals(1, preview.pairsAdded());
            assertEquals(0, preview.pairsRemoved());
            assertEquals(2, preview.moved().size());
            assertTrue(preview.moved().stream().allMatch(m -> "EXTERNAL".equals(m.from())
                && "MATCHED".equals(m.to())), preview.moved().toString());

            // A bad candidate is a 422, and nothing is written.
            assertEquals(422, hub.transfersPreview("windowDays: 4\ntransferPatterns:\n  nope:\n    - { match: x }")
                .status());
        }
    }

    private static Fact fact(long n, String id, String account, long amount, String raw) {
        return new Fact(n, id, account, LocalDate.of(2026, 9, 1), amount, 0, raw, null, 0,
            Observation.POSTED, "ing-csv", Provenance.BANK, null, "ing-csv/1", AT);
    }

    private static void config(Path configDir) throws Exception {
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("accounts.yaml"), """
            accounts:
              - ref: "ing-savings"
                currency: "AUD"
                balanceSource: statement
              - ref: "ing-orange"
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
            rules: []
            """);
        Files.writeString(configDir.resolve("transfers.yaml"), """
            windowDays: 4
            transferPatterns:
              default: []
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
