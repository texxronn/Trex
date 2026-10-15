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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The month's budget sheet (V2-SPEC.md §9.2, V2-BUDGET-VIEW-PLAN.md §4): Money In/Out/Left over the
 * budget accounts, sharing headroom's row-walk so the two agree; the out rows rolled into their
 * primary groups; the move to a non-budget account as the Moved out slice with its destinations; the
 * loan repayments parked outside the budget, aggregated per pair; and the outside accounts' own
 * rows, which never touch In/Out/Left.
 */
class BudgetApiTest {

    private static final Instant AT = Instant.parse("2026-10-01T00:00:00Z");

    @Test
    void theSheetReconcilesWithHeadroomAndRollsUpTheSpend(@TempDir Path dir) throws Exception {
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
                // A card repayment: both sides in the budget, so it moves nothing and appears nowhere.
                fact(4, "rep-a", "everyday", -10000, "PAY CARD", today),
                fact(5, "rep-b", "card", 10000, "PAYMENT RECEIVED", today),
                // A deposit to savings (outside the budget): money moved out, the Moved out slice.
                fact(6, "sav-a", "everyday", -50000, "TO SAVINGS", today),
                fact(7, "sav-b", "savings", 50000, "FROM EVERYDAY", today),
                // A loan repayment between two accounts both outside the budget (D-3).
                fact(8, "off-a", "offset", -30000, "TO LOAN", today),
                fact(9, "off-b", "loan", 30000, "FROM OFFSET", today),
                declare(10, "salary", "Salary", "in", "SALARY", 500000L, first),
                declare(11, "rent", "Rent", "out", "RENT PAYMENT", 200000L, first),
                // An out commitment due this month with nothing seen: the Due slice.
                declare(12, "insurance", "Insurance", "out", "INSURANCE", 20000L, today),
                new Decision.Pair(13, "rep-a", "rep-b", null, Actor.USER, "ron", AT),
                new Decision.Pair(14, "sav-a", "sav-b", null, Actor.USER, "ron", AT),
                new Decision.Pair(15, "off-a", "off-b", null, Actor.USER, "ron", AT),
                // Two more offset → loan repayments, identical: the three fortnightly payments the
                // sheet aggregates into one pair line with a count (D-3).
                fact(16, "off2-a", "offset", -30000, "TO LOAN", today),
                fact(17, "off2-b", "loan", 30000, "FROM OFFSET", today),
                fact(18, "off3-a", "offset", -30000, "TO LOAN", today),
                fact(19, "off3-b", "loan", 30000, "FROM OFFSET", today),
                new Decision.Pair(20, "off2-a", "off2-b", null, Actor.USER, "ron", AT),
                new Decision.Pair(21, "off3-a", "off3-b", null, Actor.USER, "ron", AT),
                // The outside accounts' own rows, which the budget cannot see: the loan interest and
                // the savings interest earned (D-3). Signed, and never in In/Out/Left.
                fact(22, "loan-int", "loan", -168592, "INTEREST CHARGE", today),
                fact(23, "sav-int", "savings", 2161, "INTEREST EARNED", today),
                // Money moved from the offset (outside) into the everyday account: a move in, with
                // its own source-account parts (D-2).
                fact(24, "mid-a", "offset", -40000, "TO EVERYDAY", today),
                fact(25, "mid-b", "everyday", 40000, "FROM OFFSET", today),
                new Decision.Pair(26, "mid-a", "mid-b", null, Actor.USER, "ron", AT)));
        }

        try (HubService hub = HubService.start(new HubConfig(journal, dir.resolve("trex.sqlite"),
                configDir, "127.0.0.1", 0, 50))) {
            await(hub);
            HttpClient client = HttpClient.newHttpClient();
            URI base = URI.create("http://127.0.0.1:" + hub.port());

            JsonNode budget = json(client, base, "/api/budget?month=" + YearMonth.from(today));
            // The same month through the Expected view's headroom: the invariant this read exists for.
            JsonNode headroom = json(client, base, "/api/expected?window=month&asOf=" + today)
                .get("headroom");

            assertEquals(YearMonth.from(today).toString(), budget.get("month").asText());
            assertEquals(headroom.get("left").asLong(), budget.get("left").asLong(),
                "the sheet's left must equal headroom's: " + budget.toPrettyString());
            assertEquals(today.toString(), budget.get("through").asText());
            assertEquals(List.of("card", "cash-ron", "everyday"),
                Json.mapper().convertValue(budget.get("accounts"), List.class));
            assertEquals(0, budget.get("missed").asLong());
            assertEquals(headroom.get("missed").asLong(), budget.get("missed").asLong());

            // ---- Money In: the salary, posted; its category is the one it was derived with ------
            JsonNode income = budget.get("income");
            assertEquals(500000, income.get("posted").asLong(), income.toPrettyString());
            assertEquals(0, income.get("due").asLong());
            assertEquals(40000, income.get("movedIn").asLong(), "a move in is never income");
            assertEquals(540000, income.get("total").asLong());
            assertEquals(1, income.get("lines").size(), income.toPrettyString());
            assertEquals("UNCATEGORIZED", income.get("lines").get(0).get("category").asText());
            long linesSum = 0;
            for (JsonNode line : income.get("lines")) {
                linesSum += line.get("amount").asLong();
            }
            assertEquals(income.get("total").asLong() - income.get("movedIn").asLong(), linesSum,
                "the income lines reconcile to the total less the moves: " + income.toPrettyString());
            // The move in carries its own parts: the account it came from (D-2).
            assertEquals(List.of("offset"), partNames(income.get("movedInParts")),
                "the move in names its source account: " + income.toPrettyString());
            assertEquals(40000, income.get("movedInParts").get(0).get("amount").asLong());

            // ---- Money Out: the group roll-up, then the synthetic slices -------------------------
            JsonNode spend = budget.get("spend");
            assertEquals(203000, spend.get("posted").asLong(), "the rent paid plus the Coles shop");
            assertEquals(20000, spend.get("due").asLong(), "the due out-commitment");
            assertEquals(0, spend.get("unrecorded").asLong());
            assertEquals(50000, spend.get("movedOut").asLong(), "the savings deposit");
            assertEquals(273000, spend.get("total").asLong());

            Map<String, Long> slices = slices(spend.get("slices"));
            assertEquals(200000, slices.get("Home"),
                "the declared rent's paid amount, grouped by its fact's category");
            assertEquals(3000, slices.get("Living"), "the Coles shop, via GROCERIES -> Living");
            assertEquals(20000, slices.get("Due"), "a due occurrence carries no category");
            assertEquals(50000, slices.get("Moved out"), "the savings deposit is a move, not spending");
            assertEquals(4, slices.size(), spend.toPrettyString());
            assertFalse(slices.containsKey("Unrecorded"), "no wallet movement: " + spend.toPrettyString());
            // A group carries its parts — the trex categories inside it — so the view can open it
            // into the distribution that made it up; Moved out carries the accounts it went to, and
            // Due/Unrecorded have none.
            JsonNode living = sliceFor(spend.get("slices"), "Living");
            assertEquals(List.of("GROCERIES"), partNames(living.get("parts")),
                "the group's parts are its categories: " + living);
            assertEquals(3000, living.get("parts").get(0).get("amount").asLong(), living.toString());
            JsonNode home = sliceFor(spend.get("slices"), "Home");
            assertEquals(List.of("RENT"), partNames(home.get("parts")),
                "the rent's part is the category its fact carried: " + home);
            assertEquals(200000, home.get("parts").get(0).get("amount").asLong(), home.toString());
            JsonNode movedOut = sliceFor(spend.get("slices"), "Moved out");
            assertEquals(List.of("savings"), partNames(movedOut.get("parts")),
                "Moved out names its destination account: " + movedOut);
            assertEquals(movedOut.get("amount").asLong(), sumParts(movedOut.get("parts")),
                "the Moved out parts sum to the slice: " + movedOut);
            assertTrue(sliceFor(spend.get("slices"), "Due").get("parts").isEmpty(),
                "a Due slice has no distribution");
            // The groups are the posted spend; the synthetic slices are the rest, so the donut sums
            // to Money Out.
            assertEquals(spend.get("posted").asLong(),
                slices.getOrDefault("Home", 0L) + slices.getOrDefault("Living", 0L),
                "the group slices sum to posted: " + spend.toPrettyString());
            assertEquals(spend.get("total").asLong(),
                slices.values().stream().mapToLong(Long::longValue).sum(),
                "every slice sums to the Money Out total: " + spend.toPrettyString());
            assertEquals(List.of("Home", "Living", "Due", "Moved out"),
                groupsInOrder(spend.get("slices")),
                "groups amount-descending, then the synthetic slices Due, Moved out");
            // The card repayment is internal to the budget: neither leg is spending.
            assertFalse(slices.containsValue(10000L), "the card repayment appears nowhere: " + spend);

            // ---- outside the budget (D-3) -------------------------------------------------------
            // Three identical offset -> loan repayments aggregate into one line with a count.
            assertEquals(1, budget.get("outside").size(), budget.toPrettyString());
            JsonNode outside = budget.get("outside").get(0);
            assertEquals("offset", outside.get("from").asText());
            assertEquals("loan", outside.get("to").asText());
            assertEquals(90000, outside.get("amount").asLong(), "three 300.00 repayments");
            assertEquals(3, outside.get("count").asInt());
            // The outside accounts' own rows ride here, signed, and are nowhere in In/Out/Left.
            JsonNode externals = budget.get("outsideExternal");
            assertEquals(2, externals.size(), budget.toPrettyString());
            assertEquals("loan", externals.get(0).get("account").asText());
            assertEquals("INTEREST_PAID", externals.get(0).get("category").asText());
            assertEquals(-168592, externals.get(0).get("amount").asLong(),
                "the loan interest is money out, signed, and the biggest outflow first");
            assertEquals("savings", externals.get(1).get("account").asText());
            assertEquals("INTEREST_EARNED", externals.get(1).get("category").asText());
            assertEquals(2161, externals.get(1).get("amount").asLong());
            // The interest is not folded into any figure above: left is In - Out exactly, and equals
            // headroom's, so the external rows changed nothing.
            assertEquals(540000 - 273000, budget.get("left").asLong(),
                "the outside rows change nothing in In/Out/Left: " + budget.toPrettyString());
            assertEquals(headroom.get("left").asLong(), budget.get("left").asLong());
            assertFalse(slices.containsValue(168592L), "the loan interest is not a Money Out slice");
            assertEquals(500000, income.get("posted").asLong(),
                "the savings interest is not Money In income");

            // ---- a malformed month is refused -----------------------------------------------
            HttpResponse<String> bad = client.send(HttpRequest.newBuilder(
                base.resolve("/api/budget?month=2026-13")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(400, bad.statusCode(), bad.body());
        }
    }

    private static Map<String, Long> slices(JsonNode array) {
        Map<String, Long> out = new TreeMap<>();
        for (JsonNode slice : array) {
            out.put(slice.get("group").asText(), slice.get("amount").asLong());
        }
        return out;
    }

    private static List<String> groupsInOrder(JsonNode array) {
        List<String> out = new ArrayList<>();
        for (JsonNode slice : array) {
            out.add(slice.get("group").asText());
        }
        return out;
    }

    private static JsonNode sliceFor(JsonNode array, String group) {
        for (JsonNode slice : array) {
            if (group.equals(slice.get("group").asText())) {
                return slice;
            }
        }
        throw new AssertionError("no slice '" + group + "' in " + array);
    }

    private static List<String> partNames(JsonNode array) {
        List<String> out = new ArrayList<>();
        for (JsonNode part : array) {
            out.add(part.get("label").asText());
        }
        return out;
    }

    private static long sumParts(JsonNode array) {
        long out = 0;
        for (JsonNode part : array) {
            out += part.get("amount").asLong();
        }
        return out;
    }

    private static JsonNode json(HttpClient client, URI base, String path) throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(base.resolve(path)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return Json.mapper().readTree(response.body());
    }

    private static void await(HubService hub) throws InterruptedException {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline && hub.status().counts().getOrDefault("commitment", 0L) < 3L) {
            Thread.sleep(50);
        }
        assertTrue(hub.status().counts().getOrDefault("commitment", 0L) >= 3L, "the index never caught up");
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
              - ref: "cash-ron"
                currency: "AUD"
                balanceSource: declared
              - ref: "offset"
                currency: "AUD"
                balanceSource: declared
                budget: false
              - ref: "loan"
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
            categories: [GROCERIES, RENT, INTEREST_PAID, INTEREST_EARNED]
            rules:
              - category: GROCERIES
                when:
                  match: "COLES"
              - category: RENT
                when:
                  match: "RENT PAYMENT"
              - category: INTEREST_PAID
                when:
                  match: "INTEREST CHARGE"
              - category: INTEREST_EARNED
                when:
                  match: "INTEREST EARNED"
            """);
        Files.writeString(configDir.resolve("transfers.yaml"), """
            windowDays: 4
            transferPatterns:
              everyday:
                # An ATM withdrawal is a transfer to the declared wallet (V2-CASH-PLAN.md §4.1).
                - { match: 'ATM', rail: BANK_TRANSFER, clearing: cash-ron }
            """);
        // The view-only roll-up the Money Out donut reads (V2-BUDGET-VIEW-PLAN.md D-5).
        Files.writeString(configDir.resolve("ezb.yaml"), """
            zone: "Australia/Sydney"
            categories:
              GROCERIES: Living
              RENT: Home
              BILLS: "Bills & Subscriptions"
            """);
    }
}
