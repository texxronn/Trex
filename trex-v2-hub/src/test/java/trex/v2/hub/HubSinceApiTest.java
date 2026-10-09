package trex.v2.hub;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trex.v2.core.Actor;
import trex.v2.core.Decision;
import trex.v2.core.Envelope;
import trex.v2.core.Fact;
import trex.v2.core.IngestEvent;
import trex.v2.core.LogLine;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code GET /api/since?n=} (V2-QOL-IMPROVEMENTS-PLAN.md §5): the browser's "since you last
 * cleared" line. A journal is built with a marker n at its middle, then more facts, a completed
 * batch, a derived review item and an occurrence that turned {@code occurred}; the marker line's
 * time anchors the item comparison, the marker's UTC date anchors {@code missed}.
 */
class HubSinceApiTest {

    /** The fixture's marker: line 5 is {@code sal0}, a pre-marker fact at {@link Fixture#pre}. */
    private static final long MARKER = 5;

    /** The fixture's head: line 15 is the acting user's own note, at {@link Fixture#now}. */
    private static final long HEAD = 15;

    @Test
    void countsWhatHappenedSinceTheMarker(@TempDir Path dir) throws Exception {
        Fixture f = fixture(dir);
        try (HubService hub = start(dir, f)) {
            HttpClient client = awaitAndClient(hub);
            URI base = URI.create("http://127.0.0.1:" + hub.port());

            JsonNode body = json(get(client, base, "/api/since?n=" + MARKER + "&user=ron"));
            // The marker's time is the appended time of line 5 (a fact): the anchor for "Since …".
            assertEquals(f.pre().toString(), body.get("at").asText(), body.toPrettyString());

            // Five facts after the marker, split by account (ordered by ref).
            assertEquals(5, body.get("rows").asLong(), body.toPrettyString());
            JsonNode accounts = body.get("accounts");
            assertEquals(2, accounts.size(), body.toPrettyString());
            assertEquals("bw-card", accounts.get(0).get("account").asText());
            assertEquals(1, accounts.get(0).get("rows").asLong());
            assertEquals("ing-savings", accounts.get(1).get("account").asText());
            assertEquals(4, accounts.get(1).get("rows").asLong());

            // One completed batch after the marker (b2; b1 completed before it).
            assertEquals(1, body.get("batches").asLong(), body.toPrettyString());

            // Two items opened after the marker's time: the GYM duplicate pair and Netflix's
            // arrears item (anchored at the fact it matched). The ALDI pair opened before it.
            assertEquals(2, body.get("items").asLong(), body.toPrettyString());

            // The Netflix occurrence that turned occurred (its fact is after the marker) and the
            // one that turned missed (its window closed after the marker's date); the older missed
            // window closed before the marker and is not news.
            JsonNode occurrences = body.get("occurrences");
            assertEquals(2, occurrences.size(), body.toPrettyString());
            assertEquals("netflix", occurrences.get(0).get("commitmentId").asText());
            assertEquals("Netflix", occurrences.get(0).get("commitmentName").asText());
            assertEquals("missed", occurrences.get(0).get("status").asText());
            assertEquals(f.today().minusDays(7).toString(), occurrences.get(0).get("dueDate").asText());
            assertEquals("occurred", occurrences.get(1).get("status").asText());
            assertEquals(f.today().toString(), occurrences.get(1).get("dueDate").asText());

            // Anna's note after the marker is news; ron's own is not.
            JsonNode decisions = body.get("decisions");
            assertEquals(1, decisions.size(), body.toPrettyString());
            assertEquals("anna", decisions.get(0).get("user").asText());
            assertEquals(1, decisions.get(0).get("count").asLong());

            // The month's headroom rides along, so the line needs no second fetch.
            assertFalse(body.get("headroom").isNull(), body.toPrettyString());
            assertFalse(body.get("headroom").get("month").isNull());
            assertTrue(body.get("headroom").get("left").isNumber());
        }
    }

    @Test
    void aMarkerAtTheHeadCountsNothing(@TempDir Path dir) throws Exception {
        Fixture f = fixture(dir);
        try (HubService hub = start(dir, f)) {
            HttpClient client = awaitAndClient(hub);
            URI base = URI.create("http://127.0.0.1:" + hub.port());

            JsonNode body = json(get(client, base, "/api/since?n=" + HEAD + "&user=ron"));
            // Line 15 is ron's own note, appended at fixture time; nothing follows it. The
            // writer stamps the envelope in milliseconds, so the recorded line time is the
            // fixture instant truncated to millis.
            assertEquals(Instant.ofEpochMilli(f.now().toEpochMilli()).toString(),
                body.get("at").asText(), body.toPrettyString());
            assertEquals(0, body.get("rows").asLong(), body.toPrettyString());
            assertTrue(body.get("accounts").isEmpty(), body.toPrettyString());
            assertEquals(0, body.get("batches").asLong(), body.toPrettyString());
            assertEquals(0, body.get("items").asLong(), body.toPrettyString());
            assertTrue(body.get("occurrences").isEmpty(), body.toPrettyString());
            assertTrue(body.get("decisions").isEmpty(), body.toPrettyString());
        }
    }

    @Test
    void aMissingMarkerIsNothingAndMalformedInputIsAnError(@TempDir Path dir) throws Exception {
        Fixture f = fixture(dir);
        try (HubService hub = start(dir, f)) {
            HttpClient client = awaitAndClient(hub);
            URI base = URI.create("http://127.0.0.1:" + hub.port());

            // A rebuilt journal with fewer lines: the marker refers to a line that is gone. Calm
            // zeros, a null anchor, and the headroom shape stays intact for the client.
            JsonNode gone = json(get(client, base, "/api/since?n=99&user=ron"));
            assertTrue(gone.get("at").isNull(), gone.toPrettyString());
            assertEquals(0, gone.get("rows").asLong(), gone.toPrettyString());
            assertFalse(gone.get("headroom").isNull(), gone.toPrettyString());

            // The existing query-param convention: an absent or non-numeric n is the caller's.
            assertEquals(422, get(client, base, "/api/since").statusCode());
            assertEquals(422, get(client, base, "/api/since?n=abc").statusCode());
        }
    }

    // ---- fixtures ---------------------------------------------------------------------------

    /** The times and paths a fixture test needs: marker, posts, and the acting user's note. */
    private record Fixture(LocalDate today, Instant pre, Instant post, Instant now, Path journal,
                           Path configDir) {}

    /**
     * The journal: pre-marker lines 1-5 (a duplicate pair, a completed batch, the marker fact),
     * then post-marker a second batch, five facts, a declared Netflix commitment, a fact that
     * matches it, and two notes — the acting user's own last, at the head.
     */
    private static Fixture fixture(Path dir) throws Exception {
        LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
        Instant pre = today.minusDays(8).atTime(12, 0).toInstant(java.time.ZoneOffset.UTC);
        Instant post = pre.plusSeconds(3600);
        Instant now = Instant.now();
        Path configDir = dir.resolve("config");
        config(configDir);
        Path journal = dir.resolve("trex.jsonl");
        List<LogLine> lines = List.of(
            fact(1, "aldi1", "ing-savings", -500, "ALDI STORES", today.minusDays(14), pre),
            fact(2, "aldi2", "ing-savings", -500, "ALDI STORES", today.minusDays(14), pre),
            ingest(3, IngestEvent.START, "b1", "ing-savings", "old.csv", pre),
            ingest(4, IngestEvent.COMPLETE, "b1", "ing-savings", "old.csv", pre),
            fact(5, "sal0", "ing-savings", 100000, "SALARY OLD", today.minusDays(30), pre),
            ingest(6, IngestEvent.START, "b2", "bw-card", "new.csv", post),
            ingest(7, IngestEvent.COMPLETE, "b2", "bw-card", "new.csv", post),
            fact(8, "sal1", "ing-savings", 200000, "SALARY", today, post),
            fact(9, "cc1", "bw-card", -1234, "COFFEE", today.minusDays(1), post),
            fact(10, "gym1", "ing-savings", -999, "GYM MEMBERSHIP", today.minusDays(10), post),
            fact(11, "gym2", "ing-savings", -999, "GYM MEMBERSHIP", today.minusDays(10), post),
            declare(12, "netflix", "Netflix", "out", Cadence.WEEKLY, "NETFLIX", 1000L,
                today.minusDays(14), post),
            fact(13, "nf1", "ing-savings", -1000, "NETFLIX SUB", today, post),
            new Decision.Note(14, "sal1", "seen", Actor.USER, "anna", post),
            new Decision.Note(15, "cc1", "mine", Actor.USER, "ron", now));
        try (JsonlJournal j = new JsonlJournal(journal)) {
            j.appendBatch(lines);
        }
        return new Fixture(today, pre, post, now, journal, configDir);
    }

    private static HubService start(Path dir, Fixture f) {
        return HubService.start(new HubConfig(f.journal(), dir.resolve("trex.sqlite"), f.configDir(),
            "127.0.0.1", 0, 50));
    }

    private static HttpClient awaitAndClient(HubService hub) throws InterruptedException {
        await(() -> hub.status().counts().getOrDefault("review_item", 0L) == 3L
            && hub.status().counts().getOrDefault("commitment_occurrence", 0L) > 0L);
        return HttpClient.newHttpClient();
    }

    private static Decision.DeclareCommitment declare(long n, String id, String name, String direction,
                                                      Cadence cadence, String match, Long amount,
                                                      LocalDate anchor, Instant at) {
        return new Decision.DeclareCommitment(n, id, name, direction, cadence, AmountKind.FIXED,
            CommitmentKind.SUBSCRIPTION, List.of(new Decision.Match(match, null)), amount, anchor,
            null, null, Actor.USER, "ron", at);
    }

    private static Fact fact(long n, String id, String account, long amount, String raw,
                             LocalDate date, Instant at) {
        return new Fact(n, id, account, date, amount, 0, raw, null, 0, Observation.POSTED,
            "ing-csv", Provenance.BANK, null, "ing-csv/1", at);
    }

    private static IngestEvent ingest(long n, String phase, String batch, String account, String file,
                                      Instant at) {
        boolean start = IngestEvent.START.equals(phase);
        return new IngestEvent(Envelope.stamped(n, IngestEvent.KIND, at), phase, batch, "sha256:test",
            file, account, "ing-csv", "ing-csv/1",
            start ? null : 2, start ? null : 0, start ? null : 0, start ? null : "ok");
    }

    private static JsonNode json(HttpResponse<String> response) throws Exception {
        assertEquals(200, response.statusCode(), response.body());
        return Json.mapper().readTree(response.body());
    }

    private static HttpResponse<String> get(HttpClient client, URI base, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(base.resolve(path)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private static void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        assertTrue(condition.getAsBoolean(), "condition not met within 10s");
    }

    /**
     * Declared accounts: the fixture is about the since read, not the balance chain, and a
     * declared account never raises BALANCE_BREAK, so the review-item count is exactly the items
     * the test names.
     */
    private static void config(Path configDir) throws Exception {
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("accounts.yaml"), """
            accounts:
              - ref: "ing-savings"
                currency: "AUD"
                balanceSource: declared
              - ref: "bw-card"
                currency: "AUD"
                balanceSource: declared
            """);
        Files.writeString(configDir.resolve("users.yaml"), """
            users:
              - id: "ron"
                name: "Ron"
                active: true
              - id: "anna"
                name: "Anna"
                active: true
            """);
        Files.writeString(configDir.resolve("categories.yaml"), """
            categories: [GROCERIES]
            rules:
              - category: GROCERIES
                when:
                  match: "COFFEE"
            """);
        Files.writeString(configDir.resolve("transfers.yaml"), """
            windowDays: 4
            allowlist:
              - 'Transfer'
            """);
    }
}
