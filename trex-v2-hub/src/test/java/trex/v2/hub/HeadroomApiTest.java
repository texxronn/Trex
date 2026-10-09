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
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "Left this month" on Expected (V2-REVIEW-FIXES-PLAN.md §10): commitments through their
 * occurrences, everything else through the facts, transfers inside the budget skipped and transfers
 * leaving it counted.
 */
class HeadroomApiTest {

    private static final Instant AT = Instant.parse("2026-10-01T00:00:00Z");

    @Test
    void leftThisMonthNetsIncomeCommitmentsAndSpend(@TempDir Path dir) throws Exception {
        // The hub derives at Instant.now(); every fact sits on the month's first day or today.
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate first = today.withDayOfMonth(1);
        Path configDir = dir.resolve("config");
        config(configDir);
        Path journal = dir.resolve("trex.jsonl");
        try (JsonlJournal j = new JsonlJournal(journal)) {
            j.appendBatch(List.of(
                fact(1, "sal", "everyday", 500000, "SALARY ACME", first),
                fact(2, "rent", "everyday", -200000, "RENT PAYMENT", first),
                fact(3, "shop", "card", -3000, "COLES 1234", today),
                // A card repayment: both sides in the budget, so it moves nothing.
                fact(4, "rep-a", "everyday", -10000, "PAY CARD", today),
                fact(5, "rep-b", "card", 10000, "PAYMENT RECEIVED", today),
                // A deposit to savings leaves the budget: it is money out.
                fact(6, "sav-a", "everyday", -50000, "TO SAVINGS", today),
                fact(7, "sav-b", "savings", 50000, "FROM EVERYDAY", today),
                // Savings interest is outside the budget altogether.
                fact(8, "int", "savings", 100, "INTEREST", today),
                declare(9, "salary", "Salary", "in", "SALARY", 500000L, first),
                declare(10, "rent", "Rent", "out", "RENT", 200000L, first),
                new Decision.Pair(11, "rep-a", "rep-b", null, Actor.USER, "ron", AT),
                new Decision.Pair(12, "sav-a", "sav-b", null, Actor.USER, "ron", AT)));
        }

        try (HubService hub = HubService.start(new HubConfig(journal, dir.resolve("trex.sqlite"), configDir,
                "127.0.0.1", 0, 50))) {
            await(hub);
            HttpClient client = HttpClient.newHttpClient();
            URI base = URI.create("http://127.0.0.1:" + hub.port());
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                base.resolve("/api/expected?window=today&asOf=" + today)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), response.body());
            JsonNode headroom = Json.mapper().readTree(response.body()).get("headroom");

            assertEquals(first.toString(), headroom.get("month").asText(), "always the month, whatever the window");
            assertEquals(500000, headroom.get("incomeIn").asLong(), headroom.toPrettyString());
            assertEquals(200000, headroom.get("committedPaid").asLong(), headroom.toPrettyString());
            assertEquals(3000, headroom.get("uncommittedSpend").asLong(),
                "the shop; never the card repayment: " + headroom.toPrettyString());
            assertEquals(50000, headroom.get("movedOut").asLong(), "the savings deposit is moved, not spent");
            assertEquals(247000, headroom.get("left").asLong(), headroom.toPrettyString());
            assertEquals(List.of("card", "everyday"),
                Json.mapper().convertValue(headroom.get("accounts"), List.class));
            assertEquals(today.toString(), headroom.get("through").asText());
        }
    }

    private static void await(HubService hub) throws InterruptedException {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline && hub.status().counts().getOrDefault("commitment", 0L) < 2L) {
            Thread.sleep(50);
        }
        assertTrue(hub.status().counts().getOrDefault("commitment", 0L) >= 2L, "the index never caught up");
    }

    private static Decision.DeclareCommitment declare(long n, String id, String name, String direction,
                                                       String match, Long amount, LocalDate anchor) {
        return new Decision.DeclareCommitment(n, id, name, direction, Cadence.MONTHLY, AmountKind.FIXED,
            CommitmentKind.BILL, List.of(new Decision.Match(match, null)), amount, anchor,
            null, null, Actor.USER, "ron", AT);
    }

    private static Fact fact(long n, String id, String account, long amount, String raw, LocalDate date) {
        return new Fact(n, id, account, date, amount, 0, raw, null, 0, Observation.POSTED,
            "ing-csv", Provenance.BANK, null, "ing-csv/1", AT);
    }

    private static void config(Path configDir) throws Exception {
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("accounts.yaml"), """
            accounts:
              - ref: "everyday"
                currency: "AUD"
                balanceSource: statement
              - ref: "card"
                currency: "AUD"
                balanceSource: statement
              - ref: "savings"
                currency: "AUD"
                balanceSource: declared
                budget: false
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
