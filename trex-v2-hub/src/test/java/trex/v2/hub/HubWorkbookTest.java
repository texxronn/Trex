package trex.v2.hub;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trex.v2.core.Actor;
import trex.v2.core.Decision;
import trex.v2.core.Fact;
import trex.v2.core.Observation;
import trex.v2.core.Provenance;
import trex.v2.core.workbook.Workbook;
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
 * The workbook (V2-PROPOSAL.md §10.4): the uncategorised share and pin count are reportable, and a
 * lint finding names the offending rule or pin.
 */
class HubWorkbookTest {

    private static final Instant AT = Instant.parse("2026-09-30T00:00:00Z");

    @Test
    void reportsCoverageLintAndRedundantPins(@TempDir Path dir) throws Exception {
        Path configDir = dir.resolve("config");
        Files.createDirectories(configDir);
        config(configDir);
        Path journal = dir.resolve("trex.jsonl");
        try (JsonlJournal j = new JsonlJournal(journal)) {
            j.appendBatch(List.of(
                fact(1, "a", "COLES 1234"),
                fact(2, "b", "COLES 5678"),
                new Decision.Pin(3, List.of("b"), "GROCERIES", "one-off", Actor.USER, "ron", AT)));
        }
        Path index = dir.resolve("trex.sqlite");
        try (HubService hub = HubService.start(new HubConfig(journal, index, configDir, "127.0.0.1", 0, 50))) {
            await(() -> hub.status().counts().getOrDefault("txn_current", 0L) == 2L);
            Workbook.Report report = hub.workbook();

            assertEquals(2, report.coverage().total());
            assertEquals(2, report.coverage().categorized());
            assertEquals(1, report.coverage().pinned());
            assertEquals(0, report.coverage().uncategorized());
            assertEquals(1, report.pins().size());
            assertTrue(report.pins().getFirst().redundant(), "the rule already assigns GROCERIES");

            assertTrue(report.findings().stream()
                .anyMatch(f -> f.kind() == Workbook.FindingKind.REDUNDANT_PIN), report.findings().toString());
            assertTrue(report.findings().stream()
                .anyMatch(f -> f.kind() == Workbook.FindingKind.NEVER_FIRES), report.findings().toString());
        }
    }

    private static Fact fact(long n, String id, String raw) {
        return new Fact(n, id, "ing-savings", LocalDate.of(2026, 9, 1), -1000, 0, raw, null, 0,
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
            categories: [GROCERIES, FOO]
            rules:
              - category: GROCERIES
                when:
                  match: "COLES"
              - category: FOO
                when:
                  match: "ZZZ"
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
