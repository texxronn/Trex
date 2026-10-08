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
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The commitment read API (V2-COMMITMENTS-PLAN.md §2.8, Stage 5): the registry with its note
 * thread and arrears, the Expected windows with their statuses and totals, the ledger chip and the
 * status strip. Fixtures are relative to the day the test runs, because the hub derives against
 * the wall clock; the endpoint's {@code asOf} pins the window it is read at.
 */
class CommitmentsApiTest {

    private static final Instant AT = Instant.parse("2026-10-01T00:00:00Z");

    @Test
    void servesTheRegistryExpectedAndStatusCounts(@TempDir Path dir) throws Exception {
        // The fixture day is the derive's own day: the hub measures occurrences against
        // Instant.now() in UTC, so a local-midnight run must not shift the calendar out from
        // under the facts (a fact after the derive's day is not yet observed).
        LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
        Path configDir = dir.resolve("config");
        config(configDir);
        Path journal = dir.resolve("trex.jsonl");
        try (JsonlJournal j = new JsonlJournal(journal)) {
            j.appendBatch(List.of(
                fact(1, "nb1", -1000, "NETFLIX SUB", today.minusDays(14)),
                fact(2, "sal1", 200000, "SALARY", today.withDayOfMonth(1)),
                fact(3, "gym1", -999, "GYM MEMBERSHIP", today.minusDays(60)),
                fact(4, "gym2", -999, "GYM MEMBERSHIP", today.minusDays(30)),
                fact(5, "gym3", -999, "GYM MEMBERSHIP", today),
                fact(6, "yt1", -1399, "YOUTUBE PREMIUM", today.minusDays(90)),
                declare(7, "netflix", "Netflix", "out", Cadence.WEEKLY, "NETFLIX", 1000L,
                    today.minusDays(14)),
                declare(8, "salary", "Salary", "in", Cadence.MONTHLY, "SALARY", 200000L,
                    today.withDayOfMonth(1)),
                declare(9, "youtube", "YouTube Premium", "out", Cadence.MONTHLY, "YOUTUBE", 1399L,
                    today.minusDays(90)),
                new Decision.NoteCommitment(10, "netflix", "family plan since 2024", Actor.USER,
                    "ron", AT)));
        }

        Path index = dir.resolve("trex.sqlite");
        try (HubService hub = HubService.start(new HubConfig(journal, index, configDir,
                "127.0.0.1", 0, 50))) {
            await(() -> hub.status().counts().getOrDefault("commitment", 0L) == 4L);
            HttpClient client = HttpClient.newHttpClient();
            URI base = URI.create("http://127.0.0.1:" + hub.port());

            // ---- the registry ----------------------------------------------------------------
            JsonNode registry = json(get(client, base, "/api/commitments"));
            JsonNode netflix = find(registry, "commitmentId", "netflix");
            assertNotNull(netflix);
            assertEquals("Netflix", netflix.get("name").asText());
            assertEquals("declared", netflix.get("origin").asText());
            assertEquals("out", netflix.get("direction").asText());
            assertEquals("weekly", netflix.get("cadence").asText());
            assertEquals("fixed", netflix.get("amountKind").asText());
            assertEquals("subscription", netflix.get("kind").asText());
            // Two weeks without a charge while the frontier advanced: dormant, with arrears.
            assertEquals("dormant", netflix.get("status").asText());
            assertEquals(today.minusDays(14).toString(), netflix.get("firstDate").asText());
            assertEquals(1, netflix.get("arrearsCount").asInt());
            assertEquals(-1000, netflix.get("arrearsAmount").asLong());
            assertTrue(netflix.get("declaredN").asLong() > 0);
            assertTrue(netflix.get("retiredN").isNull());
            assertEquals(1, netflix.get("notes").size());
            assertEquals("family plan since 2024", netflix.get("notes").get(0).get("text").asText());
            assertEquals("ron", netflix.get("notes").get(0).get("user").asText());
            // The registry carries the effective rules and the next due occurrence, so the lint
            // panel and the registry can render without a second fetch.
            assertEquals(1, netflix.get("rules").size());
            assertEquals("NETFLIX", netflix.get("rules").get(0).get("match").asText());
            assertTrue(netflix.get("rules").get(0).get("account").isNull());
            assertEquals(today.toString(), netflix.get("nextDue").asText());

            JsonNode youtube = find(registry, "commitmentId", "youtube");
            assertEquals("dormant", youtube.get("status").asText());

            JsonNode candidate = null;
            for (JsonNode row : registry) {
                if ("detected".equals(row.get("origin").asText())) {
                    candidate = row;
                }
            }
            assertNotNull(candidate, "the gym series is a candidate");
            // A detected row's status is its coverage; candidate-ness is origin=detected (§2.1).
            assertEquals("active", candidate.get("status").asText());
            assertTrue(candidate.get("name").isNull());
            // The grouping stem rides on the registry so a candidate is recognisable without
            // Review; a declared row's name replaces it (§2.7; V2-EXPECTED-UX-PLAN.md §7 Stage 2).
            assertEquals("GYM MEMBERSHIP", candidate.get("stem").asText());
            assertTrue(netflix.get("stem").isNull());
            assertEquals("monthly", candidate.get("cadence").asText());
            assertEquals(3, candidate.get("occurrenceCount").asInt());
            assertEquals(-999, candidate.get("currentAmount").asLong());
            assertTrue(candidate.get("rules").isEmpty());
            assertTrue(candidate.get("nextDue").isNull());

            // ---- the candidate's activity (§7 Stage 2): the series behind the id ---------------
            String candidateId = candidate.get("commitmentId").asText();
            JsonNode facts = json(get(client, base,
                "/api/commitments/activity?id=" + java.net.URLEncoder.encode(candidateId,
                    java.nio.charset.StandardCharsets.UTF_8)));
            assertEquals(3, facts.size(), facts.toPrettyString());
            assertEquals(today.minusDays(60).toString(), facts.get(0).get("date").asText());
            assertTrue(facts.get(0).get("status").isNull());
            assertEquals("ing-savings", facts.get(0).get("accountRef").asText());
            assertEquals("gym1", facts.get(0).get("externalId").asText());
            assertEquals(-999, facts.get(0).get("amount").asLong());
            assertEquals("GYM MEMBERSHIP", facts.get(0).get("rawDescription").asText());
            assertEquals(today.toString(), facts.get(2).get("date").asText());

            // A declared commitment's activity is its occurrences with the fact each carries.
            JsonNode activity = json(get(client, base, "/api/commitments/activity?id=netflix"));
            JsonNode first = activity.get(0);
            assertEquals(today.minusDays(14).toString(), first.get("date").asText());
            assertEquals("occurred", first.get("status").asText());
            assertEquals(-1000, first.get("amount").asLong());
            assertEquals("NETFLIX SUB", first.get("rawDescription").asText());
            assertEquals("rule", first.get("matchedBy").asText());
            JsonNode missed = null;
            for (JsonNode row : activity) {
                if ("missed".equals(row.get("status").asText())) {
                    missed = row;
                }
            }
            assertNotNull(missed, activity.toPrettyString());
            assertTrue(missed.get("amount").isNull());
            assertTrue(missed.get("rawDescription").isNull());
            assertEquals(422, get(client, base, "/api/commitments/activity").statusCode());

            // ---- Review: the candidate renders from its enrichment, no second fetch ----------
            JsonNode suspected = json(get(client, base, "/api/review?kind=SUSPECTED_RECURRING"));
            assertEquals(1, suspected.size(), suspected.toPrettyString());
            assertEquals("GYM MEMBERSHIP", suspected.get(0).get("subject").asText());
            JsonNode enrichment = suspected.get(0).get("enrichment");
            assertNotNull(enrichment, suspected.toString());
            assertEquals("monthly", enrichment.get("cadence").asText());
            assertEquals(today.minusDays(60).toString(), enrichment.get("firstDate").asText());
            assertEquals(today.toString(), enrichment.get("lastDate").asText());
            assertEquals(3, enrichment.get("occurrenceCount").asInt());
            assertEquals(-999, enrichment.get("currentAmount").asLong());
            assertEquals(1.0, enrichment.get("regularity").asDouble(), 1e-9);
            assertTrue(enrichment.get("changePct").isNull());
            // The other commitment kinds need nothing beyond subject, detail and stake.
            for (String kind : List.of("DORMANT_COMMITMENT", "COMMITMENT_ARREARS")) {
                JsonNode rows = json(get(client, base, "/api/review?kind=" + kind));
                assertTrue(rows.size() >= 1, kind);
                assertTrue(rows.get(0).get("enrichment").isNull(), kind);
            }

            // ---- the ledger chip joins the matched occurrence ---------------------------------
            JsonNode ledger = json(get(client, base, "/api/ledger?q=netflix"));
            assertEquals(1, ledger.get("total").asLong());
            JsonNode chip = ledger.get("rows").get(0);
            assertEquals("netflix", chip.get("commitmentId").asText());
            assertEquals("Netflix", chip.get("commitmentName").asText());
            JsonNode unmatched = json(get(client, base, "/api/ledger?q=gym")).get("rows").get(0);
            assertTrue(unmatched.get("commitmentId").isNull(),
                "a candidate's facts carry no chip");

            // ---- Expected: the month, its arrears and the direction totals --------------------
            LocalDate monthStart = today.withDayOfMonth(1);
            LocalDate monthEnd = today.withDayOfMonth(today.lengthOfMonth());
            JsonNode month = json(get(client, base, "/api/expected?window=month&asOf=" + today));
            assertEquals("month", month.get("window").asText());
            assertEquals(monthStart.toString(), month.get("from").asText());
            assertEquals(monthEnd.toString(), month.get("to").asText());

            JsonNode occurrences = month.get("occurrences");
            assertTrue(occurrences.size() >= 2, month.toPrettyString());
            boolean netflixDue = false;
            long expectedOut = 0;
            long expectedIn = 0;
            for (JsonNode occurrence : occurrences) {
                LocalDate due = LocalDate.parse(occurrence.get("dueDate").asText());
                assertFalse(due.isBefore(monthStart) || due.isAfter(monthEnd), occurrence.toString());
                if ("netflix".equals(occurrence.get("commitmentId").asText())
                    && due.equals(today)) {
                    assertEquals("due", occurrence.get("status").asText());
                    assertEquals("Netflix", occurrence.get("commitmentName").asText());
                    assertEquals("out", occurrence.get("direction").asText());
                    assertEquals("weekly", occurrence.get("cadence").asText());
                    netflixDue = true;
                }
                long committed = occurrence.get("amount").isNull()
                    ? Math.abs(registryCurrent(registry, occurrence.get("commitmentId").asText()))
                    : Math.abs(occurrence.get("amount").asLong());
                if ("out".equals(occurrence.get("direction").asText())) {
                    expectedOut += committed;
                } else {
                    expectedIn += committed;
                }
            }
            assertTrue(netflixDue, "the weekly charge due today is in the month");
            assertEquals(expectedOut, month.get("totals").get("out").asLong());
            assertEquals(expectedIn, month.get("totals").get("in").asLong());
            assertTrue(expectedOut > 0 && expectedIn > 0, "both directions are represented");

            JsonNode arrears = month.get("arrears");
            assertTrue(arrears.size() >= 1, month.toPrettyString());
            long running = 0;
            boolean netflixMissed = false;
            for (JsonNode arrear : arrears) {
                String status = arrear.get("status").asText();
                assertTrue("missed".equals(status) || "partial".equals(status), arrear.toString());
                running += arrear.get("shortfall").asLong();
                assertEquals(running, arrear.get("runningTotal").asLong(), "the running total adds up");
                if ("netflix".equals(arrear.get("commitmentId").asText())
                    && "missed".equals(status)) {
                    assertEquals(today.minusDays(7).toString(), arrear.get("dueDate").asText());
                    assertEquals(1000, arrear.get("expected").asLong());
                    assertEquals(1000, arrear.get("shortfall").asLong());
                    netflixMissed = true;
                }
            }
            assertTrue(netflixMissed, "the skipped week is in the backlog");

            // ---- Expected: today and week are calendar bounds, the default is month ----------
            JsonNode todayView = json(get(client, base, "/api/expected?window=today&asOf=" + today));
            assertEquals(today.toString(), todayView.get("from").asText());
            assertEquals(today.toString(), todayView.get("to").asText());
            boolean todayDue = false;
            for (JsonNode occurrence : todayView.get("occurrences")) {
                assertEquals(today.toString(), occurrence.get("dueDate").asText());
                todayDue |= "netflix".equals(occurrence.get("commitmentId").asText());
            }
            assertTrue(todayDue);
            assertEquals(arrears.size(), todayView.get("arrears").size(),
                "the backlog is not windowed");

            LocalDate monday = today.minusDays(today.getDayOfWeek().getValue() - 1L);
            JsonNode week = json(get(client, base, "/api/expected?window=week&asOf=" + today));
            assertEquals(monday.toString(), week.get("from").asText());
            assertEquals(monday.plusDays(6).toString(), week.get("to").asText());
            for (JsonNode occurrence : week.get("occurrences")) {
                LocalDate due = LocalDate.parse(occurrence.get("dueDate").asText());
                assertFalse(due.isBefore(monday) || due.isAfter(monday.plusDays(6)),
                    occurrence.toString());
            }

            assertEquals("month", json(get(client, base, "/api/expected?asOf=" + today))
                .get("window").asText());
            assertEquals(422, get(client, base, "/api/expected?window=quarter").statusCode());

            // ---- the status strip counts the commitment tables -------------------------------
            JsonNode counts = json(get(client, base, "/api/status")).get("counts");
            assertEquals(4, counts.get("commitment").asLong());
            assertEquals(3, counts.get("commitment_rule").asLong());
            assertTrue(counts.get("commitment_occurrence").asLong() > 0);
            assertEquals(1, counts.get("commitment_note").asLong());
        }
    }

    // ---- fixtures ---------------------------------------------------------------------------

    private static Decision.DeclareCommitment declare(long n, String id, String name, String direction,
                                                       Cadence cadence, String match, Long amount,
                                                       LocalDate anchor) {
        return new Decision.DeclareCommitment(n, id, name, direction, cadence, AmountKind.FIXED,
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

    private static JsonNode find(JsonNode array, String field, String value) {
        for (JsonNode node : array) {
            if (value.equals(node.get(field).asText())) {
                return node;
            }
        }
        return null;
    }

    /** The registry row's signed current amount, for the total's price of a due/missed row. */
    private static long registryCurrent(JsonNode registry, String commitmentId) {
        JsonNode row = find(registry, "commitmentId", commitmentId);
        assertNotNull(row, commitmentId);
        return row.get("currentAmount").asLong();
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
