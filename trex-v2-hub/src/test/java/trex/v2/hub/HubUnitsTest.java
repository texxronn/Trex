package trex.v2.hub;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trex.v2.core.Fact;
import trex.v2.core.Observation;
import trex.v2.core.Provenance;
import trex.v2.hub.api.UnitsResponse;
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
 * §15.18: the Firefly unit set is exact. Legs are never projected, an ATTESTATION is never
 * projected, and a pending observation is excluded.
 */
class HubUnitsTest {

    private static final Instant AT = Instant.parse("2026-09-30T00:00:00Z");

    @Test
    void theUnitSetIsTransfersPlusPostedExternalTransactionsOnly(@TempDir Path dir) throws Exception {
        Path configDir = dir.resolve("config");
        Files.createDirectories(configDir);
        config(configDir);
        Path journal = dir.resolve("trex.jsonl");
        try (JsonlJournal j = new JsonlJournal(journal)) {
            j.appendBatch(List.of(
                fact(1, "a", "ing-savings", -1000, "COLES 1234", Observation.POSTED),
                fact(2, "att", "cash-ron", 0, "Cash attestation", Observation.POSTED),   // never a unit
                fact(3, "pend", "ing-savings", -4995, "AUTH ONLY BP", Observation.PENDING), // excluded
                fact(4, "t1", "ing-savings", -500, "Transfer to Savings 1111", Observation.POSTED),
                fact(5, "t2", "ing-orange", 500, "Transfer from Savings 1111", Observation.POSTED)));
        }
        Path index = dir.resolve("trex.sqlite");
        try (HubService hub = HubService.start(new HubConfig(journal, index, configDir, "127.0.0.1", 0, 50))) {
            await(() -> hub.status().counts().getOrDefault("txn_current", 0L) == 4L
                || hub.units().units().size() == 2);
            UnitsResponse units = hub.units();
            assertEquals(2, units.units().size(), "one transfer + one posted external: " + units.units());
            assertTrue(units.units().stream().anyMatch(u -> u.unitKind().equals("TRANSFER")));
            assertTrue(units.units().stream().anyMatch(u -> u.unitId().equals("a")));
            assertTrue(units.units().stream().noneMatch(u -> u.unitId().equals("att")), "attestation withheld");
            assertTrue(units.units().stream().noneMatch(u -> u.unitId().equals("pend")), "pending withheld");
            assertTrue(units.units().stream().noneMatch(u -> u.unitId().equals("t1")), "legs not units");
            assertTrue(units.units().stream().noneMatch(u -> u.unitId().equals("t2")), "legs not units");
        }
    }

    private static Fact fact(long n, String id, String account, long amount, String raw, Observation observation) {
        return new Fact(n, id, account, LocalDate.of(2026, 9, 1), amount, 0, raw, null, 0, observation,
            "ing-csv", Provenance.BANK, null, "ing-csv/1", AT);
    }

    private static void config(Path configDir) throws Exception {
        Files.writeString(configDir.resolve("accounts.yaml"), """
            accounts:
              - ref: "ing-savings"
                currency: "AUD"
                balanceSource: statement
              - ref: "ing-orange"
                currency: "AUD"
                balanceSource: statement
              - ref: "cash-ron"
                currency: "AUD"
                balanceSource: declared
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
