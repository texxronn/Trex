package trex.v2.hub;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trex.v2.hub.api.CashResponse;
import trex.v2.hub.api.LedgerRow;
import trex.v2.log.Json;
import trex.v2.sequencer.SequencerService;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The manual cash entry (V2-CASH-PLAN.md §4.3, V2-SPEC.md §9.4): a spend, an income or a wallet
 * count is a fact on a declared wallet, forwarded to the sequencer; an optional category is a PIN
 * on that same fact. Runs a real sequencer and hub end to end.
 */
class HubCashTest {

    @Test
    void aSpendLandsOnTheWalletAndAPinApplies(@TempDir Path dir) throws Exception {
        Path configDir = dir.resolve("config");
        Files.createDirectories(configDir);
        config(configDir);

        try (SequencerService sequencer = SequencerService.start(dir.resolve("trex.jsonl"), configDir,
                "127.0.0.1", 0, Clock.systemUTC())) {
            HttpClient client = HttpClient.newHttpClient();
            Path index = dir.resolve("trex.sqlite");
            try (HubService hub = HubService.start(new HubConfig(dir.resolve("trex.jsonl"), index, configDir,
                    "127.0.0.1", 0, 50, "http://127.0.0.1:" + sequencer.port()))) {
                URI api = URI.create("http://127.0.0.1:" + hub.port());

                HttpResponse<String> response = postJson(client, api.resolve("/api/cash"),
                    "{\"account\":\"cash-ron\",\"date\":\"2026-09-05\",\"amount\":-1234,"
                        + "\"description\":\"MANUAL LUNCH\",\"category\":\"GROCERIES\",\"user\":\"ron\"}");
                assertEquals(200, response.statusCode(), response.body());
                CashResponse cash = Json.mapper().readValue(response.body(), CashResponse.class);
                assertEquals("Appended", cash.outcome(), response.body());
                assertEquals("GROCERIES", cash.category(), response.body());
                assertNull(cash.pinError(), response.body());

                await(() -> hub.status().counts().getOrDefault("txn_current", 0L) == 1L);
                await(() -> "PIN".equals(row(hub, "cash-ron").categoryOrigin()));

                LedgerRow spend = row(hub, "cash-ron");
                assertEquals(cash.externalId(), spend.externalId());
                assertFalse(spend.synthetic());
                assertEquals(-1234, spend.amount());
                assertEquals("GROCERIES", spend.category());
                assertEquals("PIN", spend.categoryOrigin());

                // The row is on the ledger read the UI uses.
                JsonNode ledger = Json.mapper().readTree(get(client, api.resolve("/api/ledger?account=cash-ron")));
                assertEquals(cash.externalId(), ledger.path("rows").get(0).path("externalId").asText());
            }
        }
    }

    @Test
    void aCountLandsAsAnAttestationNotAUnit(@TempDir Path dir) throws Exception {
        Path configDir = dir.resolve("config");
        Files.createDirectories(configDir);
        config(configDir);

        try (SequencerService sequencer = SequencerService.start(dir.resolve("trex.jsonl"), configDir,
                "127.0.0.1", 0, Clock.systemUTC())) {
            HttpClient client = HttpClient.newHttpClient();
            Path index = dir.resolve("trex.sqlite");
            try (HubService hub = HubService.start(new HubConfig(dir.resolve("trex.jsonl"), index, configDir,
                    "127.0.0.1", 0, 50, "http://127.0.0.1:" + sequencer.port()))) {
                URI api = URI.create("http://127.0.0.1:" + hub.port());

                // amount absent is a count; balance is the counted value.
                HttpResponse<String> response = postJson(client, api.resolve("/api/cash"),
                    "{\"account\":\"cash-ron\",\"date\":\"2026-09-06\",\"balance\":5000,"
                        + "\"description\":\"Wallet count\",\"user\":\"ron\"}");
                assertEquals(200, response.statusCode(), response.body());
                CashResponse cash = Json.mapper().readValue(response.body(), CashResponse.class);
                assertEquals("Appended", cash.outcome(), response.body());

                await(() -> hub.status().counts().getOrDefault("txn_current", 0L) == 1L);
                LedgerRow count = row(hub, "cash-ron");
                assertEquals(cash.externalId(), count.externalId());
                assertEquals(0, count.amount(), "a count is a $0 fact");
                assertEquals(5000, count.balance(), "its balance is the counted value");
                assertFalse(count.synthetic());

                // A $0 fact on a declared account derives as an ATTESTATION: never a unit.
                assertEquals(0, hub.units().units().size(), "attestations are withheld: " + hub.units());
                assertTrue(hub.units().units().stream().noneMatch(u -> u.unitId().equals(cash.externalId())));
            }
        }
    }

    @Test
    void refusesAStatementAccountAnUndeclaredCategoryAndACountWithoutBalance(@TempDir Path dir)
            throws Exception {
        Path configDir = dir.resolve("config");
        Files.createDirectories(configDir);
        config(configDir);

        try (SequencerService sequencer = SequencerService.start(dir.resolve("trex.jsonl"), configDir,
                "127.0.0.1", 0, Clock.systemUTC())) {
            HttpClient client = HttpClient.newHttpClient();
            Path index = dir.resolve("trex.sqlite");
            try (HubService hub = HubService.start(new HubConfig(dir.resolve("trex.jsonl"), index, configDir,
                    "127.0.0.1", 0, 50, "http://127.0.0.1:" + sequencer.port()))) {
                URI api = URI.create("http://127.0.0.1:" + hub.port());

                HttpResponse<String> statement = postJson(client, api.resolve("/api/cash"),
                    "{\"account\":\"ing-savings\",\"date\":\"2026-09-05\",\"amount\":-100,"
                        + "\"description\":\"x\",\"user\":\"ron\"}");
                assertEquals(422, statement.statusCode(), statement.body());
                assertTrue(statement.body().contains("declared account"), statement.body());

                HttpResponse<String> unknownAccount = postJson(client, api.resolve("/api/cash"),
                    "{\"account\":\"ghost\",\"date\":\"2026-09-05\",\"amount\":-100,"
                        + "\"description\":\"x\",\"user\":\"ron\"}");
                assertEquals(422, unknownAccount.statusCode(), unknownAccount.body());

                HttpResponse<String> undeclared = postJson(client, api.resolve("/api/cash"),
                    "{\"account\":\"cash-ron\",\"date\":\"2026-09-05\",\"amount\":-100,"
                        + "\"description\":\"x\",\"category\":\"NOPE\",\"user\":\"ron\"}");
                assertEquals(422, undeclared.statusCode(), undeclared.body());
                assertTrue(undeclared.body().contains("not declared"), undeclared.body());

                HttpResponse<String> noBalance = postJson(client, api.resolve("/api/cash"),
                    "{\"account\":\"cash-ron\",\"date\":\"2026-09-05\",\"description\":\"x\",\"user\":\"ron\"}");
                assertEquals(422, noBalance.statusCode(), noBalance.body());
                assertTrue(noBalance.body().contains("count needs its balance"), noBalance.body());

                // None of those wrote a fact.
                assertEquals(0L, hub.status().counts().getOrDefault("txn_current", 0L));
            }
        }
    }

    private static LedgerRow row(HubService hub, String account) {
        var page = hub.ledger(new BlotterQuery(account, null, null, null, null, null, null, null,
            null, null, false, "date", "desc", 100, 0));
        assertEquals(1, page.rows().size(), "one row on " + account + ": " + page.rows());
        return page.rows().getFirst();
    }

    private static HttpResponse<String> postJson(HttpClient client, URI uri, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(uri).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String get(HttpClient client, URI uri) throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(uri).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return response.body();
    }

    private static void config(Path configDir) throws Exception {
        Files.writeString(configDir.resolve("accounts.yaml"), """
            accounts:
              - ref: "ing-savings"
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
              - id: "mel"
                name: "Mel"
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
        Files.writeString(configDir.resolve("sources.yaml"), """
            sources: ["HUB_0001"]
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
