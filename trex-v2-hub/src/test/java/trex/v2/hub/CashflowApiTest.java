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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The month's cash-flow sheet (V2-SPEC.md §9.2, V2-CASHFLOW-PLAN.md C-2…C-6): Money In and Money Out
 * of the household's <b>liquid</b> accounts, with a move between two liquid accounts ignored, a
 * liquid → loan move a repayment, a liquid → other non-liquid move a set-aside, and two views of the
 * mortgage — Cost (the loan charges in their groups, the principal and set-aside in a bridge) and
 * Cash (one {@code Mortgage} slice equal to the repayments, a {@code Set aside} slice). A paid
 * occurrence counts in its payment month, and the Cash view of a past month reconciles to the signed
 * sum of the liquid accounts' rows.
 *
 * <p>The fixture month is two months back, so it is all actuals; a row on each commitment's account
 * in the month after closes the windows, so no occurrence of the fixture month is still awaited.
 */
class CashflowApiTest {

    private static final Instant AT = Instant.parse("2026-10-01T00:00:00Z");

    /** The fixture month's measured figures (V2-CASHFLOW-PLAN.md §0: an August repayment). */
    private static final long REPAYMENT = 270814;
    private static final long INTEREST = 174450;
    private static final long PRINCIPAL = REPAYMENT - INTEREST; // 963.64
    private static final long KIDS = 600000;
    private static final long SALARY = 1200000;

    @Test
    void aPastMonthInBothViews(@TempDir Path dir) throws Exception {
        YearMonth month = YearMonth.now(ZoneOffset.UTC).minusMonths(2);
        YearMonth prev = month.minusMonths(1);
        YearMonth next = month.plusMonths(1);
        Path configDir = dir.resolve("config");
        config(configDir, "liquid: true");
        Path journal = dir.resolve("trex.jsonl");
        try (JsonlJournal j = new JsonlJournal(journal)) {
            j.appendBatch(List.of(
                // ---- the month before: a repayment, its interest, and a redraw (test 4) ----------
                fact(1, "p-rep-a", "offset", -REPAYMENT, "TO LOAN", prev.atDay(15)),
                fact(2, "p-rep-b", "loan", REPAYMENT, "FROM OFFSET", prev.atDay(15)),
                fact(3, "p-int", "loan", -INTEREST, "INTEREST CHARGE", prev.atDay(15)),
                fact(4, "p-redraw-a", "loan", -50000, "REDRAW", prev.atDay(20)),
                fact(5, "p-redraw-b", "offset", 50000, "FROM LOAN", prev.atDay(20)),
                // ---- the fixture month ---------------------------------------------------------
                // (1) A salary into savings, then swept to everyday: income once, the sweep noise.
                fact(6, "sal", "savings", SALARY, "SALARY ACME", month.atDay(3)),
                fact(7, "sweep-a", "savings", -100000, "TO EVERYDAY", month.atDay(4)),
                fact(8, "sweep-b", "everyday", 100000, "FROM SAVINGS", month.atDay(4)),
                // (2) everyday -> savings and a card repayment: liquid <-> liquid, nothing.
                fact(9, "dep-a", "everyday", -20000, "TO SAVINGS", month.atDay(5)),
                fact(10, "dep-b", "savings", 20000, "FROM EVERYDAY", month.atDay(5)),
                fact(11, "card-a", "everyday", -15000, "PAY CARD", month.atDay(6)),
                fact(12, "card-b", "card", 15000, "PAYMENT RECEIVED", month.atDay(6)),
                fact(13, "shop", "card", -15000, "COLES 1234", month.atDay(6)),
                fact(14, "fee", "everyday", -500, "ACCOUNT FEE", month.atDay(8)),
                // (5) A transfer to the kids' clearing account: set aside.
                fact(15, "kids", "everyday", -KIDS, "KIDS SAVINGS", month.atDay(10)),
                // (3) The offset -> loan repayment and the loan's interest charge.
                fact(16, "rep-a", "offset", -REPAYMENT, "TO LOAN", month.atDay(15)),
                fact(17, "rep-b", "loan", REPAYMENT, "FROM OFFSET", month.atDay(15)),
                fact(18, "int", "loan", -INTEREST, "INTEREST CHARGE", month.atDay(15)),
                // (6) A bill due on the last day of the month before, paid on the 1st.
                fact(19, "water", "everyday", -10000, "SYDNEY WATER", month.atDay(1)),
                // The month after: each commitment's account has moved past the fixture month's
                // windows, so a window with nothing in it is missed, never awaited (no Due).
                fact(20, "f-every", "everyday", -100, "FRONTIER", next.atDay(20)),
                fact(21, "f-off", "offset", -100, "FRONTIER", next.atDay(20)),
                fact(22, "f-loan", "loan", -100, "FRONTIER", next.atDay(20)),
                declare(23, "loan-repay", "Loan repayment", "out", "TO LOAN", REPAYMENT, prev.atDay(15)),
                declare(24, "loan-interest", "Loan interest", "out", "INTEREST CHARGE", INTEREST,
                    prev.atDay(15)),
                declare(25, "water", "Sydney Water", "out", "SYDNEY WATER", 10000L, prev.atEndOfMonth()),
                pair(26, "p-rep-a", "p-rep-b"),
                pair(27, "p-redraw-a", "p-redraw-b"),
                pair(28, "sweep-a", "sweep-b"),
                pair(29, "dep-a", "dep-b"),
                pair(30, "card-a", "card-b"),
                pair(31, "rep-a", "rep-b")));
        }

        try (HubService hub = HubService.start(new HubConfig(journal, dir.resolve("trex.sqlite"),
                configDir, "127.0.0.1", 0, 50))) {
            await(hub, 3);
            HttpClient client = HttpClient.newHttpClient();
            URI base = URI.create("http://127.0.0.1:" + hub.port());
            JsonNode cost = json(client, base, "/api/cashflow?month=" + month);
            JsonNode cash = json(client, base, "/api/cashflow?month=" + month + "&view=cash");

            assertEquals(month.toString(), cost.get("month").asText());
            assertEquals("cost", cost.get("view").asText());
            assertEquals("cash", cash.get("view").asText());
            assertEquals("cost", json(client, base, "/api/cashflow?month=" + month + "&view=nonsense")
                .get("view").asText(), "anything but cash is cost");
            // C-2: a clearing account flagged liquid and a loan account left at the default are
            // never liquid.
            assertEquals(List.of("card", "everyday", "offset", "savings"),
                Json.mapper().convertValue(cost.get("accounts"), List.class), cost.toPrettyString());
            assertEquals(0, cost.get("spend").get("due").asLong(),
                "a past month is actuals: " + cost.toPrettyString());
            assertEquals(10000, cost.get("missed").asLong(),
                "the water's own in-month occurrence closed empty: " + cost.toPrettyString());

            // ---- (1) the salary counts once; (2) the moves change nothing ------------------------
            JsonNode income = cost.get("income");
            assertEquals(SALARY, income.get("posted").asLong(), income.toPrettyString());
            assertEquals(SALARY, income.get("total").asLong(), income.toPrettyString());
            assertEquals(1, income.get("lines").size(), "the salary alone, not the sweep: " + income);
            assertEquals("SALARY", income.get("lines").get(0).get("category").asText());

            // ---- the Cost view ----------------------------------------------------------------
            JsonNode spend = cost.get("spend");
            Map<String, Long> costSlices = slices(spend.get("slices"));
            assertEquals(Map.of("Finance", INTEREST + 500, "Living", 15000L, "Bills", 10000L), costSlices,
                "the loan interest in its group, no move anywhere: " + spend.toPrettyString());
            assertEquals(List.of("Finance", "Living", "Bills"), groupsInOrder(spend.get("slices")));
            assertEquals(List.of("INTEREST_PAID", "BANK_FEES"),
                partNames(sliceFor(spend.get("slices"), "Finance").get("parts")));
            assertEquals(INTEREST + 500 + 15000 + 10000, spend.get("posted").asLong());
            assertEquals(spend.get("posted").asLong(), spend.get("total").asLong());
            assertEquals(SALARY - spend.get("total").asLong(), cost.get("left").asLong(),
                "left excludes the principal and the set-aside: " + cost.toPrettyString());

            // (3) the loan, reported in both views
            JsonNode loans = cost.get("loans");
            assertEquals(REPAYMENT, loans.get("repayments").asLong(), loans.toPrettyString());
            assertEquals(INTEREST, loans.get("charges").asLong(), loans.toPrettyString());
            assertEquals(PRINCIPAL, loans.get("principal").asLong(), loans.toPrettyString());
            assertEquals(96364, loans.get("principal").asLong());
            assertEquals(1, loans.get("parts").size(), loans.toPrettyString());
            JsonNode loan = loans.get("parts").get(0);
            assertEquals("loan", loan.get("account").asText());
            assertEquals(REPAYMENT, loan.get("repayments").asLong());
            assertEquals(INTEREST, loan.get("charges").asLong());
            assertEquals(PRINCIPAL, loan.get("principal").asLong());
            assertEquals(loans, cash.get("loans"), "the loans read the same in both views");

            // (5) the set-aside: only reported in Cost
            JsonNode setAside = cost.get("setAside");
            assertEquals(KIDS, setAside.get("total").asLong(), setAside.toPrettyString());
            assertEquals(List.of("kids"), partNames(setAside.get("parts")));
            assertFalse(costSlices.containsKey("Set aside"), spend.toPrettyString());
            assertFalse(costSlices.containsKey("Mortgage"), spend.toPrettyString());

            // ---- the Cash view ----------------------------------------------------------------
            JsonNode cashSpend = cash.get("spend");
            Map<String, Long> cashSlices = slices(cashSpend.get("slices"));
            assertEquals(Map.of("Finance", 500L, "Living", 15000L, "Bills", 10000L,
                    "Mortgage", REPAYMENT, "Set aside", KIDS), cashSlices,
                "the interest left its group for the Mortgage slice: " + cashSpend.toPrettyString());
            assertEquals(List.of("Living", "Bills", "Finance", "Mortgage", "Set aside"),
                groupsInOrder(cashSpend.get("slices")));
            assertEquals(List.of("BANK_FEES"),
                partNames(sliceFor(cashSpend.get("slices"), "Finance").get("parts")),
                "the interest is not also in its group");
            JsonNode mortgage = sliceFor(cashSpend.get("slices"), "Mortgage");
            assertEquals(List.of("Interest & fees", "Principal"), partNames(mortgage.get("parts")));
            assertEquals(INTEREST, mortgage.get("parts").get(0).get("amount").asLong());
            assertEquals(PRINCIPAL, mortgage.get("parts").get(1).get("amount").asLong());
            assertEquals(List.of("kids"), partNames(sliceFor(cashSpend.get("slices"), "Set aside").get("parts")));
            assertEquals(cashSpend.get("total").asLong(),
                cashSlices.values().stream().mapToLong(Long::longValue).sum());
            assertEquals(cost.get("left").asLong() - cash.get("left").asLong(), PRINCIPAL + KIDS,
                "Cost left - Cash left = principal + set-aside: " + cash.toPrettyString());

            // ---- (7) the Cash view of a past month reconciles to the liquid rows -----------------
            JsonNode reconcile = cash.get("reconcile");
            long liquidNet = SALARY - 500 - KIDS - REPAYMENT - 15000 - 10000;
            assertEquals(liquidNet, reconcile.get("liquidNet").asLong(), reconcile.toPrettyString());
            assertEquals(0, reconcile.get("gap").asLong(), cash.toPrettyString());
            assertEquals(reconcile.get("liquidNet").asLong(), cash.get("left").asLong());
            assertEquals(reconcile, cost.get("reconcile"), "reconcile is computed whatever the view");

            // ---- (4) a redraw reduces the repayments; (6) the payment month ------------------------
            JsonNode before = json(client, base, "/api/cashflow?month=" + prev + "&view=cash");
            assertEquals(REPAYMENT - 50000, before.get("loans").get("repayments").asLong(),
                before.toPrettyString());
            assertEquals(INTEREST, before.get("loans").get("charges").asLong());
            assertEquals(REPAYMENT - 50000 - INTEREST, before.get("loans").get("principal").asLong());
            assertFalse(slices(before.get("spend").get("slices")).containsKey("Bills"),
                "the water was due in the month before but paid in this one: " + before.toPrettyString());
            assertEquals(0, before.get("reconcile").get("gap").asLong(), before.toPrettyString());
            assertEquals(-(REPAYMENT - 50000), before.get("left").asLong(), before.toPrettyString());
            assertEquals(10000, costSlices.get("Bills"), "the water counts in its payment month");

            // ---- (9) headroom is the Cost view's left ------------------------------------------
            JsonNode headroom = json(client, base,
                "/api/expected?window=month&asOf=" + month.atDay(28)).get("headroom");
            assertEquals(cost.get("left").asLong(), headroom.get("left").asLong(), headroom.toPrettyString());
            assertFalse(headroom.has("movedOut"), headroom.toPrettyString());

            // ---- the route --------------------------------------------------------------------
            HttpResponse<String> bad = client.send(HttpRequest.newBuilder(
                base.resolve("/api/cashflow?month=2026-13")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(400, bad.statusCode(), bad.body());
            HttpResponse<String> gone = client.send(HttpRequest.newBuilder(
                base.resolve("/api/budget?month=" + month)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            assertNotEquals(200, gone.statusCode(), "/api/budget is removed: " + gone.body());
        }
    }

    /**
     * (8) The current month's projection: in the Cash view the declared repayment's due occurrence is
     * in {@code Due} and the declared interest's is not (it sits inside the repayment); in the Cost
     * view the reverse. (9) The status strip's headroom is the Cost view's {@code left}.
     */
    @Test
    void theCurrentMonthProjectsTheMortgageByView(@TempDir Path dir) throws Exception {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate first = today.withDayOfMonth(1);
        Path configDir = dir.resolve("config");
        config(configDir, "liquid: false");
        Path journal = dir.resolve("trex.jsonl");
        try (JsonlJournal j = new JsonlJournal(journal)) {
            j.appendBatch(List.of(
                fact(1, "sal", "everyday", 500000, "SALARY ACME", first),
                fact(2, "shop", "card", -3000, "COLES 1234", today),
                declare(3, "loan-repay", "Loan repayment", "out", "TO LOAN", REPAYMENT, today),
                declare(4, "loan-interest", "Loan interest", "out", "INTEREST CHARGE", INTEREST, today),
                declare(5, "insurance", "Insurance", "out", "INSURANCE", 20000L, today)));
        }

        try (HubService hub = HubService.start(new HubConfig(journal, dir.resolve("trex.sqlite"),
                configDir, "127.0.0.1", 0, 50))) {
            await(hub, 3);
            HttpClient client = HttpClient.newHttpClient();
            URI base = URI.create("http://127.0.0.1:" + hub.port());
            YearMonth month = YearMonth.from(today);
            JsonNode cost = json(client, base, "/api/cashflow?month=" + month);
            JsonNode cash = json(client, base, "/api/cashflow?month=" + month + "&view=cash");

            assertEquals(20000 + INTEREST, cost.get("spend").get("due").asLong(),
                "Cost: the interest is due, the repayment is a move: " + cost.toPrettyString());
            assertEquals(20000 + REPAYMENT, cash.get("spend").get("due").asLong(),
                "Cash: the repayment is due, the interest sits inside it: " + cash.toPrettyString());
            assertEquals(20000 + INTEREST, slices(cost.get("spend").get("slices")).get("Due"));
            assertEquals(20000 + REPAYMENT, slices(cash.get("spend").get("slices")).get("Due"));
            assertEquals(500000 - 3000 - 20000 - INTEREST, cost.get("left").asLong(), cost.toPrettyString());
            assertEquals(500000 - 3000 - 20000 - REPAYMENT, cash.get("left").asLong(), cash.toPrettyString());
            assertEquals(500000 - 3000, cash.get("reconcile").get("postedLeft").asLong(),
                "postedLeft carries no due projection: " + cash.toPrettyString());

            JsonNode headroom = json(client, base, "/api/expected?window=month&asOf=" + today)
                .get("headroom");
            assertEquals(cost.get("left").asLong(), headroom.get("left").asLong(),
                "headroom is the Cost view's left: " + headroom.toPrettyString());
            assertEquals(20000 + INTEREST, headroom.get("committedDue").asLong(), headroom.toPrettyString());
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

    private static JsonNode json(HttpClient client, URI base, String path) throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(base.resolve(path)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return Json.mapper().readTree(response.body());
    }

    private static void await(HubService hub, long commitments) throws InterruptedException {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline
            && hub.status().counts().getOrDefault("commitment", 0L) < commitments) {
            Thread.sleep(50);
        }
        assertTrue(hub.status().counts().getOrDefault("commitment", 0L) >= commitments,
            "the index never caught up");
    }

    private static Decision.Pair pair(long n, String a, String b) {
        return new Decision.Pair(n, a, b, null, Actor.USER, "ron", AT);
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

    /**
     * The fixture's accounts: four liquid ones, the declared loan (left at the default flag, which
     * C-2 overrides) and the kids' clearing account with {@code kidsLiquid} as its flag — C-2 again.
     */
    private static void config(Path configDir, String kidsLiquid) throws Exception {
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
                balanceSource: statement
              - ref: "offset"
                currency: "AUD"
                balanceSource: statement
              - ref: "loan"
                currency: "AUD"
                balanceSource: statement
              - ref: "kids"
                currency: "AUD"
                balanceSource: clearing
                closingBalance: 0
                %s
            """.formatted(kidsLiquid));
        Files.writeString(configDir.resolve("users.yaml"), """
            users:
              - id: "ron"
                name: "Ron"
                active: true
            """);
        Files.writeString(configDir.resolve("categories.yaml"), """
            categories: [GROCERIES, INTEREST_PAID, BANK_FEES, WATER, SALARY]
            rules:
              - category: GROCERIES
                when:
                  match: "COLES"
              - category: INTEREST_PAID
                when:
                  match: "INTEREST CHARGE"
              - category: BANK_FEES
                when:
                  match: "ACCOUNT FEE"
              - category: WATER
                when:
                  match: "SYDNEY WATER"
              - category: SALARY
                when:
                  match: "SALARY"
            """);
        Files.writeString(configDir.resolve("transfers.yaml"), """
            windowDays: 4
            transferPatterns:
              everyday:
                - { match: 'KIDS', rail: BANK_TRANSFER, clearing: kids }
            """);
        Files.writeString(configDir.resolve("ezb.yaml"), """
            zone: "Australia/Sydney"
            categories:
              GROCERIES: Living
              INTEREST_PAID: Finance
              BANK_FEES: Finance
              WATER: Bills
            """);
        Files.writeString(configDir.resolve("mortgages.yaml"), """
            mortgages:
              - name: "Fixture"
                offset: "offset"
                loans:
                  - account: "loan"
                    label: "Loan"
                    repaymentCommitment: "loan-repay"
                    interestCommitment: "loan-interest"
            """);
    }
}
