package trex.v2.hub;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trex.v2.core.Actor;
import trex.v2.core.Decision;
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
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The blotter read API (V2-PROPOSAL.md §10.2): SQL-backed filters, review, transfers and refdata. */
class HubApiTest {

    private static final Instant AT = Instant.parse("2026-09-30T00:00:00Z");

    @Test
    void servesLedgerReviewTransfersAndRefdata(@TempDir Path dir) throws Exception {
        Path configDir = dir.resolve("config");
        config(configDir);
        Path journal = dir.resolve("trex.jsonl");
        try (JsonlJournal j = new JsonlJournal(journal)) {
            j.appendBatch(List.of(
                fact(1, "a", "ing-savings", -1000, "COLES 1234"),
                fact(2, "t1", "ing-savings", -500, "Transfer to Savings 1111"),
                fact(3, "t2", "ing-orange", 500, "Transfer from Savings 1111"),
                fact(4, "d", "ing-savings", -1000, "COLES 1234"),
                fact(5, "u", "ing-savings", -2000, "MYSTERY MERCHANT")));
        }

        Path index = dir.resolve("trex.sqlite");
        try (HubService hub = HubService.start(new HubConfig(journal, index, configDir, "127.0.0.1", 0, 50))) {
            await(() -> hub.status().counts().getOrDefault("txn_current", 0L) == 5L);
            HttpClient client = HttpClient.newHttpClient();
            URI base = URI.create("http://127.0.0.1:" + hub.port());

            assertTrue(get(client, base, "/api/refdata").body().contains("\"ref\":\"ing-savings\""));
            assertTrue(get(client, base, "/api/refdata").body().contains("GROCERIES"));

            assertEquals(2, total(client, base, "/api/ledger?category=GROCERIES"));
            assertEquals(2, total(client, base, "/api/ledger?q=coles"));
            assertEquals(4, total(client, base, "/api/ledger?direction=out"));
            assertEquals(1, total(client, base, "/api/ledger?account=ing-orange"));
            assertEquals(5, total(client, base, "/api/ledger?limit=1"));

            String review = get(client, base, "/api/review").body();
            assertTrue(review.contains("POTENTIAL_DUP"), review);
            assertTrue(review.contains("RESTATEMENT"), review);
            assertTrue(get(client, base, "/api/review?kind=POTENTIAL_DUP").body().contains("POTENTIAL_DUP"));

            assertTrue(get(client, base, "/api/transfers").body().contains("\"confidence\":\"HIGH\""));
            assertTrue(get(client, base, "/api/reconcile").body().contains("\"accountRef\":\"ing-savings\""));

            HttpResponse<String> root = get(client, base, "/");
            assertEquals(200, root.statusCode());
            assertTrue(root.body().contains("id=\"main\""));
            HttpResponse<String> appJs = get(client, base, "/ui/js/app.js");
            assertEquals(200, appJs.statusCode());
            assertTrue(appJs.headers().firstValue("Content-Type").orElse("").contains("javascript"));
            assertEquals(404, get(client, base, "/ui/does-not-exist.js").statusCode());
            assertTrue(get(client, base, "/api/workbook").body().contains("coverage"));

            HttpResponse<String> walk = get(client, base, "/api/eyeball?period=2026-09&user=ron");
            assertEquals(200, walk.statusCode(), walk.body());
            assertTrue(walk.body().contains("\"anomalies\""), walk.body());
            assertTrue(walk.body().contains("\"buckets\""), walk.body());
            assertTrue(walk.body().contains("NEW_MERCHANT_STEM"), walk.body());
            assertTrue(get(client, base, "/api/eyeball?period=2026-09&user=ron&bucket=week")
                .body().contains("\"granularity\":\"week\""));
            assertEquals(400, get(client, base,
                "/api/eyeball?period=2026-09&user=ron&bucket=fortnight").statusCode());
            assertEquals(400, get(client, base, "/api/eyeball?period=2026-13&user=ron").statusCode());

            HttpResponse<String> bad = get(client, base, "/api/ledger?sort=bogus");
            assertEquals(400, bad.statusCode());
        }
    }

    private static long total(HttpClient client, URI base, String path) throws Exception {
        var node = Json.mapper().readTree(get(client, base, path).body());
        return node.get("total").asLong();
    }

    private static HttpResponse<String> get(HttpClient client, URI base, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(base.resolve(path)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
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
