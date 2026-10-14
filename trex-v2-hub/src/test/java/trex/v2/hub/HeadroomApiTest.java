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
            assertEquals(0, headroom.get("unrecordedCash").asLong(),
                "no wallet movement: " + headroom.toPrettyString());
            assertEquals(50000, headroom.get("movedOut").asLong(), "the savings deposit is moved, not spent");
            assertEquals(247000, headroom.get("left").asLong(), headroom.toPrettyString());
            assertEquals(List.of("card", "cash-ron", "everyday"),
                Json.mapper().convertValue(headroom.get("accounts"), List.class));
            assertEquals(today.toString(), headroom.get("through").asText());

            // The status strip carries the same date, so an "all clear" says what it holds to.
            HttpResponse<String> status = client.send(HttpRequest.newBuilder(base.resolve("/api/status")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(today.toString(), Json.mapper().readTree(status.body()).get("through").asText(), status.body());
        }
    }

    /**
     * An ATM recorded as a manual spend: the withdrawal is a transfer between two budget accounts
     * (skipped) and the recorded spend counts like any other, so the month nets to zero unrecorded
     * cash and {@code left} is the spend alone.
     */
    @Test
    void anAtmRecordedAsSpendNetsToZeroUnrecorded(@TempDir Path dir) throws Exception {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Path configDir = dir.resolve("config");
        config(configDir);
        Path journal = dir.resolve("trex.jsonl");
        try (JsonlJournal j = new JsonlJournal(journal)) {
            j.appendBatch(List.of(
                // Cash out of the ATM: shape-matched to the wallet, so it is a transfer, not spend.
                fact(1, "atm", "everyday", -10000, "ATM WDL 123", today),
                // The same cash, recorded as spent from the wallet: the recorded spend counts.
                fact(2, "cash-spend", "cash-ron", -10000, "MANUAL LUNCH", today)));
        }

        try (HubService hub = HubService.start(new HubConfig(journal, dir.resolve("trex.sqlite"), configDir,
                "127.0.0.1", 0, 50))) {
            awaitCash(hub);
            JsonNode headroom = headroom(hub, today);

            assertEquals(0, headroom.get("unrecordedCash").asLong(),
                "the withdrawal went in and was recorded out: " + headroom.toPrettyString());
            assertEquals(10000, headroom.get("uncommittedSpend").asLong(),
                "the recorded spend sits in uncommittedSpend: " + headroom.toPrettyString());
            assertEquals(-10000, headroom.get("left").asLong(),
                "the spend alone; unrecordedCash adds nothing: " + headroom.toPrettyString());
        }
    }

    /**
     * An ATM nobody recorded: the withdrawal is still a transfer between budget accounts (skipped),
     * but the wallet's month is unrecorded cash, and {@code left} carries it as spend.
     */
    @Test
    void anUnrecordedAtmMovesLeftByTheUnrecordedFigure(@TempDir Path dir) throws Exception {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Path configDir = dir.resolve("config");
        config(configDir);
        Path journal = dir.resolve("trex.jsonl");
        try (JsonlJournal j = new JsonlJournal(journal)) {
            j.appendBatch(List.of(fact(1, "atm", "everyday", -10000, "ATM WDL 123", today)));
        }

        try (HubService hub = HubService.start(new HubConfig(journal, dir.resolve("trex.sqlite"), configDir,
                "127.0.0.1", 0, 50))) {
            awaitCash(hub);
            JsonNode headroom = headroom(hub, today);

            assertEquals(10000, headroom.get("unrecordedCash").asLong(),
                "cash left the wallet unrecorded: " + headroom.toPrettyString());
            assertEquals(0, headroom.get("uncommittedSpend").asLong(),
                "the transfer is skipped, so it is not spend: " + headroom.toPrettyString());
            assertEquals(-10000, headroom.get("left").asLong(),
                "left moves by the unrecorded figure: " + headroom.toPrettyString());
        }
    }

    /**
     * Cash that appeared unrecorded: the wallet count is higher than the movement explains, so the
     * month is negative and it moves {@code left} the other way (it reduces the spend).
     */
    @Test
    void cashThatAppearedUnrecordedMovesLeftTheOtherWay(@TempDir Path dir) throws Exception {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate first = today.withDayOfMonth(1);
        Path configDir = dir.resolve("config");
        config(configDir);
        Path journal = dir.resolve("trex.jsonl");
        try (JsonlJournal j = new JsonlJournal(journal)) {
            j.appendBatch(List.of(
                // The month's opening baseline: the latest count before it.
                count(1, "cash-ron", first.minusMonths(1).withDayOfMonth(15), 10000),
                count(2, "cash-ron", today, 15000),
                fact(3, "cash-spend", "cash-ron", -1000, "MANUAL LUNCH", today)));
        }

        try (HubService hub = HubService.start(new HubConfig(journal, dir.resolve("trex.sqlite"), configDir,
                "127.0.0.1", 0, 50))) {
            awaitCash(hub);
            JsonNode headroom = headroom(hub, today);

            assertEquals(-6000, headroom.get("unrecordedCash").asLong(),
                "1000 recorded out, 5000 appeared unrecorded: " + headroom.toPrettyString());
            assertEquals(1000, headroom.get("uncommittedSpend").asLong(),
                "the manual spend still counts: " + headroom.toPrettyString());
            assertEquals(5000, headroom.get("left").asLong(),
                "-1000 spend plus 6000 of cash that appeared: " + headroom.toPrettyString());
        }
    }

    private static JsonNode headroom(HubService hub, LocalDate today) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        URI base = URI.create("http://127.0.0.1:" + hub.port());
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(
            base.resolve("/api/expected?window=today&asOf=" + today)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return Json.mapper().readTree(response.body()).get("headroom");
    }

    /** The wallet's derived months are written in the same transaction as the rest of the derive. */
    private static void awaitCash(HubService hub) throws InterruptedException {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline && hub.cashMonths("cash-ron").isEmpty()) {
            Thread.sleep(50);
        }
        assertTrue(!hub.cashMonths("cash-ron").isEmpty(), "the cash month never appeared");
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

    /** A wallet count: a $0 fact whose balance is what the person counted. */
    private static Fact count(long n, String account, LocalDate date, long balance) {
        return new Fact(n, "count-" + n, account, date, 0, balance, "Wallet count", null, 0,
            Observation.POSTED, "ing-csv", Provenance.BANK, null, "ing-csv/1", AT);
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
                # An ATM withdrawal is a transfer to the declared wallet (V2-CASH-PLAN.md §4.1).
                - { match: 'ATM', rail: BANK_TRANSFER, clearing: cash-ron }
            """);
    }
}
