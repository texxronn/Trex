package trex.v2.hub;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trex.v2.core.Actor;
import trex.v2.core.Decision;
import trex.v2.core.Fact;
import trex.v2.core.Observation;
import trex.v2.core.Provenance;
import trex.v2.core.derive.AmountKind;
import trex.v2.core.derive.Cadence;
import trex.v2.core.derive.CommitmentKind;
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
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Expected month stepper (V2-SPEC.md §9, {@code GET /api/expected?window=month&month=YYYY-MM}):
 * a read-side range filter over the occurrence rows already in the index. The month moves only the
 * occurrence range and its totals; toPay, arrears and headroom stay measured at the day.
 */
class ExpectedMonthTest {

    private static final Instant AT = Instant.parse("2026-10-01T00:00:00Z");

    @Test
    void stepsMonthsWithinTheGeneratedBounds(@TempDir Path dir) throws Exception {
        // The hub derives against the wall clock in UTC, so the fixture is relative to that day.
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate start = today.minusMonths(3).withDayOfMonth(1);
        Path configDir = dir.resolve("config");
        config(configDir);
        Path journal = dir.resolve("trex.jsonl");
        try (JsonlJournal j = new JsonlJournal(journal)) {
            j.appendBatch(List.of(
                // Due on `start`; the fact lands two days later, inside the occurrence's window.
                fact(1, "rent1", -1000, "RENT PAYMENT", start.plusDays(2)),
                declare(2, "rent", "Rent", "RENT", 1000L, start)));
        }
        Path index = dir.resolve("trex.sqlite");
        try (HubService hub = HubService.start(new HubConfig(journal, index, configDir,
                "127.0.0.1", 0, 50))) {
            await(() -> hub.status().counts().getOrDefault("commitment", 0L) == 1L
                && hub.expected("month", null, today).occurrences().size() > 0);
            HttpClient client = HttpClient.newHttpClient();
            URI base = URI.create("http://127.0.0.1:" + hub.port());
            String at = "&asOf=" + today;

            // ---- 5: no month -> the current month, with the bounds -----------------------------
            JsonNode current = json(get(client, base, "/api/expected?window=month" + at));
            assertEquals(YearMonth.from(today).toString(), current.get("month").asText());
            LocalDate past = today.minusMonths(12);
            YearMonth min = past.getDayOfMonth() == 1 ? YearMonth.from(past) : YearMonth.from(past).plusMonths(1);
            YearMonth max = YearMonth.from(today.plusDays(92));
            assertEquals(min.toString(), current.get("minMonth").asText());
            assertEquals(max.toString(), current.get("maxMonth").asText());
            for (String window : List.of("today", "week")) {
                JsonNode other = json(get(client, base, "/api/expected?window=" + window + at));
                assertTrue(other.get("month").isNull(), window);
                assertTrue(other.get("minMonth").isNull(), window);
                assertTrue(other.get("maxMonth").isNull(), window);
            }

            // ---- 1: a past month reads only its own occurrences, with their current statuses ----
            String pastMonth = YearMonth.from(start).toString();
            JsonNode stepped = json(get(client, base, "/api/expected?window=month&month=" + pastMonth + at));
            assertEquals(pastMonth, stepped.get("month").asText());
            assertEquals(start.toString(), stepped.get("from").asText());
            assertEquals(YearMonth.from(start).atEndOfMonth().toString(), stepped.get("to").asText());
            assertEquals(1, stepped.get("occurrences").size(), stepped.toPrettyString());
            JsonNode rent = stepped.get("occurrences").get(0);
            assertEquals(start.toString(), rent.get("dueDate").asText());
            assertEquals("occurred", rent.get("status").asText(),
                "the fact sits after the occurrence's due date, in its window: " + rent);
            assertEquals(1000, stepped.get("totals").get("out").asLong());

            // ---- 2: the day-measured parts do not move with the month --------------------------
            assertEquals(current.get("toPay"), stepped.get("toPay"));
            assertEquals(current.get("arrears"), stepped.get("arrears"));
            assertEquals(current.get("headroom"), stepped.get("headroom"));

            // ---- 3: one month outside either bound is refused ----------------------------------
            assertEquals(200, get(client, base, "/api/expected?window=month&month=" + min + at).statusCode());
            assertEquals(200, get(client, base, "/api/expected?window=month&month=" + max + at).statusCode());
            assertEquals(422, get(client, base,
                "/api/expected?window=month&month=" + min.minusMonths(1) + at).statusCode());
            assertEquals(422, get(client, base,
                "/api/expected?window=month&month=" + max.plusMonths(1) + at).statusCode());

            // ---- 4: month needs window=month, and must be YYYY-MM ------------------------------
            for (String window : List.of("today", "week")) {
                assertEquals(422, get(client, base,
                    "/api/expected?window=" + window + "&month=" + pastMonth + at).statusCode(), window);
            }
            assertEquals(422, get(client, base, "/api/expected?window=month&month=2026-13" + at).statusCode());
            assertEquals(422, get(client, base, "/api/expected?window=month&month=2026-1" + at).statusCode());

            // ---- 6: the MCP tool returns the HTTP body ------------------------------------------
            JsonNode result = new McpApi(hub).dispatch(Json.readTree(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"trex_expected\","
                    + "\"arguments\":{\"window\":\"month\",\"month\":\"" + pastMonth + "\",\"asOf\":\""
                    + today + "\"}}}"), new McpConfig(false)).get("result");
            assertFalse(result.get("isError").asBoolean(), result.toString());
            assertNotNull(result.get("structuredContent"), result.toString());
            // Compared as text: the tool serialises longs where the HTTP read parses ints.
            assertEquals(stepped.toString(), result.get("structuredContent").toString());
        }
    }

    @Test
    void boundsFollowTheExplicitAsOf(@TempDir Path dir) throws Exception {
        Path configDir = dir.resolve("config");
        config(configDir);
        Path journal = dir.resolve("trex.jsonl");
        try (JsonlJournal j = new JsonlJournal(journal)) {
            j.appendBatch(List.of(fact(1, "x", -1, "X", LocalDate.of(2026, 1, 1))));
        }
        try (HubService hub = HubService.start(new HubConfig(journal, dir.resolve("trex.sqlite"), configDir,
                "127.0.0.1", 0, 50))) {
            // 2026-11-05: generated from 2025-11-05, so 2025-12 is the first whole month; to 2027-02-05.
            var nov5 = hub.expected("month", null, LocalDate.of(2026, 11, 5));
            assertEquals("2025-12", nov5.minMonth());
            assertEquals("2027-02", nov5.maxMonth());
            assertEquals("2026-11", nov5.month());
            assertEquals(LocalDate.of(2027, 2, 5), nov5.generatedTo());
            assertNull(hub.expected("week", null, LocalDate.of(2026, 11, 5)).generatedTo());
            // A past that lands on the 1st keeps its own month whole.
            var first = hub.expected("month", null, LocalDate.of(2026, 11, 1));
            assertEquals("2025-11", first.minMonth());
            assertEquals("2027-02", first.maxMonth());
        }
    }

    // ---- fixtures ---------------------------------------------------------------------------

    private static Decision.DeclareCommitment declare(long n, String id, String name, String match,
                                                       Long amount, LocalDate anchor) {
        return new Decision.DeclareCommitment(n, id, name, "out", Cadence.MONTHLY, AmountKind.FIXED,
            CommitmentKind.SUBSCRIPTION, List.of(new Decision.Match(match, null)), amount, anchor,
            null, null, Actor.USER, "ron", AT);
    }

    private static Fact fact(long n, String id, long amount, String raw, LocalDate date) {
        return new Fact(n, id, "ing-savings", date, amount, 0, raw, null, 0, Observation.POSTED,
            "ing-csv", Provenance.BANK, null, "ing-csv/1", AT);
    }

    private static JsonNode json(HttpResponse<String> response) throws Exception {
        assertEquals(200, response.statusCode(), response.body());
        return Json.mapper().readTree(response.body());
    }

    private static HttpResponse<String> get(HttpClient client, URI base, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(base.resolve(path)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private static void config(Path configDir) throws Exception {
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
