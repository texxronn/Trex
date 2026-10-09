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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The statement-age nudge (QOL_Improvements.md §2): {@code /api/status} lists the accounts past
 * their {@code fetchEveryDays} cadence, oldest first, and stays silent for a fresh account, one
 * with the nudge off, one without a cadence at all, and one that has no frontier yet.
 */
class HubStatusApiTest {

    private static final Instant AT = Instant.parse("2026-10-01T00:00:00Z");

    @Test
    void staleListsAccountsPastTheirCadence(@TempDir Path dir) throws Exception {
        LocalDate today = LocalDate.now();
        Path configDir = dir.resolve("config");
        config(configDir);
        Path journal = dir.resolve("trex.jsonl");
        try (JsonlJournal j = new JsonlJournal(journal)) {
            j.appendBatch(List.of(
                // Ages: inside 30 <= its default 31; older 40 > 5 and past 32 > 10 are stale;
                // off is 100 days old but fetchEveryDays: 0 silences it; cash is 200 days old
                // but a declared account has no cadence; never has no frontier at all.
                fact(1, "a", "inside", today.minusDays(30)),
                fact(2, "b", "past", today.minusDays(32)),
                fact(3, "c", "older", today.minusDays(40)),
                fact(4, "d", "off", today.minusDays(100)),
                fact(5, "e", "cash", today.minusDays(200))));
        }
        try (HubService hub = HubService.start(new HubConfig(journal, dir.resolve("trex.sqlite"), configDir,
                "127.0.0.1", 0, 50))) {
            await(hub);
            HttpClient client = HttpClient.newHttpClient();
            URI base = URI.create("http://127.0.0.1:" + hub.port());
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                base.resolve("/api/status")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), response.body());
            JsonNode body = Json.mapper().readTree(response.body());

            JsonNode stale = body.get("stale");
            assertTrue(stale.isArray(), body.toPrettyString());
            assertEquals(2, stale.size(), body.toPrettyString());
            assertEquals("older", stale.get(0).get("account").asText(), "oldest first");
            assertEquals(today.minusDays(40).toString(), stale.get(0).get("frontier").asText());
            assertEquals(40, stale.get(0).get("days").asInt());
            assertEquals(5, stale.get(0).get("fetchEveryDays").asInt());
            assertEquals("past", stale.get(1).get("account").asText());
            assertEquals(today.minusDays(32).toString(), stale.get(1).get("frontier").asText());
            assertEquals(32, stale.get(1).get("days").asInt());
            assertEquals(10, stale.get(1).get("fetchEveryDays").asInt());

            // The all-clear date is untouched: still the oldest frontier of the budget accounts.
            assertEquals(today.minusDays(100).toString(), body.get("through").asText(), body.toPrettyString());
        }
    }

    private static void await(HubService hub) throws InterruptedException {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline
                && hub.status().counts().getOrDefault("txn_current", 0L) < 5L) {
            Thread.sleep(50);
        }
        assertTrue(hub.status().counts().getOrDefault("txn_current", 0L) >= 5L, "the index never caught up");
    }

    private static Fact fact(long n, String id, String account, LocalDate date) {
        return new Fact(n, id, account, date, -1000, 0, "COLES " + id, null, 0,
            Observation.POSTED, "ing-csv", Provenance.BANK, null, "ing-csv/1", AT);
    }

    private static void config(Path configDir) throws Exception {
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("accounts.yaml"), """
            accounts:
              - ref: "inside"
                currency: "AUD"
                balanceSource: statement
              - ref: "past"
                currency: "AUD"
                balanceSource: statement
                fetchEveryDays: 10
              - ref: "older"
                currency: "AUD"
                balanceSource: statement
                fetchEveryDays: 5
              - ref: "off"
                currency: "AUD"
                balanceSource: statement
                fetchEveryDays: 0
              - ref: "cash"
                currency: "AUD"
                balanceSource: declared
              - ref: "never"
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
