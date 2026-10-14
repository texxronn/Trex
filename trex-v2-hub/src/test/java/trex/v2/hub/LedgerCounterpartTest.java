package trex.v2.hub;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trex.v2.core.Fact;
import trex.v2.core.Observation;
import trex.v2.core.Provenance;
import trex.v2.log.Json;
import trex.v2.log.JsonlJournal;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ledger row carries the other side of a transfer (§10.2), so the Blotter/Eyeball {@code
 * TRANSFER} chip can name it: {@code to:<account>} on the leg the money left, and the same account
 * on the wallet's synthetic side. An unpaired row carries null.
 */
class LedgerCounterpartTest {

    private static final Instant AT = Instant.parse("2026-10-01T00:00:00Z");

    @Test
    void aTransferLegNamesTheOtherAccountAndAnUnpairedRowNamesNone(@TempDir Path dir) throws Exception {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Path configDir = dir.resolve("config");
        Files.createDirectories(configDir);
        config(configDir);
        Path journal = dir.resolve("trex.jsonl");
        try (JsonlJournal j = new JsonlJournal(journal)) {
            j.appendBatch(List.of(
                fact(1, "atm", "everyday", -10000, "ATM WDL 123", today),
                fact(2, "shop", "everyday", -3000, "COLES 1234", today)));
        }

        try (HubService hub = HubService.start(new HubConfig(journal, dir.resolve("trex.sqlite"), configDir,
                "127.0.0.1", 0, 50))) {
            await(hub);
            HttpClient client = HttpClient.newHttpClient();
            URI api = URI.create("http://127.0.0.1:" + hub.port());

            // The bank leg points at the wallet it paid.
            JsonNode bank = row(client, api, "everyday", "atm");
            assertEquals("cash-ron", bank.get("counterpartAccount").asText(), bank.toString());
            assertEquals("TRANSFER", bank.get("category").asText());

            // The wallet's synthetic leg points back at the bank.
            JsonNode wallet = firstRow(client, api, "cash-ron");
            assertEquals("cash-ron", wallet.get("accountRef").asText());
            assertEquals("everyday", wallet.get("counterpartAccount").asText(), wallet.toString());

            // An unpaired row names no counterpart.
            JsonNode shop = row(client, api, "everyday", "shop");
            JsonNode counterpart = shop.get("counterpartAccount");
            assertTrue(counterpart == null || counterpart.isNull(), shop.toString());
        }
    }

    private static JsonNode row(HttpClient client, URI api, String account, String externalId)
            throws Exception {
        for (JsonNode row : ledger(client, api, account)) {
            if (row.path("externalId").asText().equals(externalId)) {
                return row;
            }
        }
        throw new AssertionError("no row " + externalId + " on " + account);
    }

    private static JsonNode firstRow(HttpClient client, URI api, String account) throws Exception {
        for (JsonNode row : ledger(client, api, account)) {
            if (row.path("synthetic").asBoolean()) {
                return row;
            }
        }
        throw new AssertionError("no synthetic row on " + account);
    }

    private static Iterable<JsonNode> ledger(HttpClient client, URI api, String account) throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(
            api.resolve("/api/ledger?account=" + account)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return Json.mapper().readTree(response.body()).get("rows");
    }

    private static Fact fact(long n, String id, String account, long amount, String raw, LocalDate date) {
        return new Fact(n, id, account, date, amount, 0, raw, null, 0,
            Observation.POSTED, "ing-csv", Provenance.BANK, null, "ing-csv/1", AT);
    }

    private static void config(Path configDir) throws Exception {
        Files.writeString(configDir.resolve("accounts.yaml"), """
            accounts:
              - ref: "everyday"
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
            transferPatterns:
              everyday:
                - { match: 'ATM', rail: BANK_TRANSFER, clearing: cash-ron }
            """);
    }

    private static void await(HubService hub) throws InterruptedException {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline && hub.transfers().isEmpty()) {
            Thread.sleep(50);
        }
        assertEquals(1, hub.transfers().size(), "the ATM never paired");
    }
}
