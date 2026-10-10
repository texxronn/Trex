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
import trex.v2.hub.api.BriefResponse;
import trex.v2.hub.api.ExpectedResponse;
import trex.v2.hub.api.ReconcileResponse;
import trex.v2.hub.api.ReviewRow;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The compact assistant brief (V2-ASSISTANT-PLAN.md §1 A4, §3 Stage B): {@code GET /api/brief} and
 * {@code trex_brief}. It is a <b>view</b> over the existing reads — the head, the review counts by
 * kind, the reconciliation gaps, the current month's committed totals and arrears count, and the
 * newest ingest — much smaller than the four reads it summarises, and it stores nothing.
 */
class BriefTest {

    /** The fixture's dated duplicate pairs: enough review rows that the brief is plainly smaller. */
    private static final int DUP_PAIRS = 8;

    @Test
    void briefSummarisesTheHeadAndTheMonth(@TempDir Path dir) throws Exception {
        Fixture f = fixture(dir);
        try (HubService hub = start(dir, f)) {
            await(() -> hub.status().reviewByKind().getOrDefault("POTENTIAL_DUP", 0L) >= DUP_PAIRS);
            BriefResponse brief = hub.brief();

            // The head: the same n the hub reports.
            assertEquals(hub.head().n(), brief.asOfN());

            // The review counts: exactly the queue grouped by kind, never its rows.
            Map<String, Long> byKind = new TreeMap<>();
            for (ReviewRow row : hub.review(null, null)) {
                byKind.merge(row.kind(), 1L, Long::sum);
            }
            Map<String, Long> briefByKind = new TreeMap<>();
            for (BriefResponse.ReviewCount count : brief.review()) {
                briefByKind.put(count.kind(), count.count());
            }
            assertEquals(byKind, briefByKind);
            assertFalse(brief.review().isEmpty(), "the fixture has review items");

            // The reconciliation: the verdict and only the accounts with a non-zero gap.
            ReconcileResponse reconciliation = hub.reconcile();
            List<String> gaps = reconciliation.accounts().stream()
                .filter(account -> account.gap() != 0)
                .map(ReconcileResponse.AccountJson::accountRef)
                .toList();
            assertEquals(reconciliation.ok(), brief.reconcile().ok());
            assertEquals(gaps, brief.reconcile().gaps().stream()
                .map(BriefResponse.Gap::accountRef).toList());
            assertEquals(reconciliation.accounts().stream()
                    .filter(account -> account.gap() != 0)
                    .map(ReconcileResponse.AccountJson::gap).toList(),
                brief.reconcile().gaps().stream().map(BriefResponse.Gap::gap).toList());
            assertFalse(brief.reconcile().gaps().isEmpty(), "the fixture leaves a declared gap");

            // The month: the committed in/out and the arrears count, matching the Expected view.
            ExpectedResponse expected = hub.expected("month", null);
            assertEquals(YearMonth.from(expected.from()).toString(), brief.expected().month());
            assertEquals(expected.totals().out(), brief.expected().committedOut());
            assertEquals(expected.totals().in(), brief.expected().committedIn());
            assertEquals(expected.arrears().size(), brief.expected().arrears());

            // The newest ingest: batch, when, and the row counts.
            assertFalse(hub.ingests(null).rows().isEmpty());
            var newest = hub.ingests(null).rows().getFirst();
            assertTrue(brief.lastIngest() != null);
            assertEquals(newest.batch(), brief.lastIngest().batch());
            assertEquals(newest.completedMs(), brief.lastIngest().completedMs());
            assertEquals(newest.appended(), brief.lastIngest().appended());
        }
    }

    @Test
    void briefIsSmallerThanItsParts(@TempDir Path dir) throws Exception {
        Fixture f = fixture(dir);
        try (HubService hub = start(dir, f)) {
            await(() -> hub.status().reviewByKind().getOrDefault("POTENTIAL_DUP", 0L) >= DUP_PAIRS);
            long brief = jsonLength(hub.brief());
            long parts = jsonLength(hub.review(null, null))
                + jsonLength(hub.reconcile())
                + jsonLength(hub.expected("month", null))
                + jsonLength(hub.ingests(null));
            assertTrue(brief < parts,
                "the brief (" + brief + " bytes) must be smaller than its parts (" + parts + ")");
        }
    }

    @Test
    void theBriefRouteIsServed(@TempDir Path dir) throws Exception {
        Fixture f = fixture(dir);
        try (HubService hub = start(dir, f)) {
            await(() -> hub.status().reviewByKind().getOrDefault("POTENTIAL_DUP", 0L) >= DUP_PAIRS);
            HttpClient client = HttpClient.newHttpClient();
            URI base = URI.create("http://127.0.0.1:" + hub.port());

            HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(base.resolve("/api/brief")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), response.body());
            JsonNode body = Json.mapper().readTree(response.body());
            assertEquals(hub.head().n(), body.get("asOfN").asLong(), body.toPrettyString());
            assertTrue(body.has("review"), body.toPrettyString());
            assertTrue(body.has("reconcile"), body.toPrettyString());
            assertTrue(body.has("expected"), body.toPrettyString());
            assertTrue(body.has("lastIngest"), body.toPrettyString());
            assertTrue(body.get("review").isArray(), body.toPrettyString());
        }
    }

    // ---- fixtures ---------------------------------------------------------------------------

    private record Fixture(Path journal, Path configDir) {}

    /**
     * A declared account with dated duplicate pairs (review), a declared gap between two
     * attestations, one completed ingest, and a monthly commitment with a fact that matches its
     * current occurrence — enough for every field of the brief.
     */
    private static Fixture fixture(Path dir) throws Exception {
        LocalDate today = LocalDate.now();
        Instant at = Instant.now().minusSeconds(3600);
        Path configDir = dir.resolve("config");
        config(configDir);
        Path journal = dir.resolve("trex.jsonl");
        List<LogLine> lines = new ArrayList<>();
        long n = 1;
        lines.add(fact(n++, "open", 0, 100000, "OPENING ATT", today.minusDays(20), at));
        for (int i = 0; i < DUP_PAIRS; i++) {
            String raw = "COFFEE " + i;
            LocalDate date = today.minusDays(10 + i);
            lines.add(fact(n++, "dup" + i + "a", -1000, 0, raw, date, at));
            lines.add(fact(n++, "dup" + i + "b", -1000, 0, raw, date, at));
        }
        lines.add(declare(n++, "netflix", today.minusDays(14), at));
        lines.add(fact(n++, "nf1", -1000, 0, "NETFLIX SUB", today, at));
        lines.add(ingest(n++, IngestEvent.START, "b1", "savings", "new.csv", at));
        lines.add(ingest(n++, IngestEvent.COMPLETE, "b1", "savings", "new.csv", at));
        lines.add(fact(n++, "close", 0, 95000, "CLOSING ATT", today.minusDays(1), at));
        try (JsonlJournal j = new JsonlJournal(journal)) {
            j.appendBatch(lines);
        }
        return new Fixture(journal, configDir);
    }

    private static HubService start(Path dir, Fixture f) {
        return HubService.start(new HubConfig(f.journal(), dir.resolve("trex.sqlite"), f.configDir(),
            "127.0.0.1", 0, 50));
    }

    private static Decision.DeclareCommitment declare(long n, String id, LocalDate anchor, Instant at) {
        return new Decision.DeclareCommitment(n, id, "Netflix", "out", Cadence.WEEKLY,
            AmountKind.FIXED, CommitmentKind.SUBSCRIPTION, List.of(new Decision.Match("NETFLIX", null)),
            1000L, anchor, null, null, Actor.USER, "ron", at);
    }

    private static Fact fact(long n, String id, long amount, long balance, String raw,
                             LocalDate date, Instant at) {
        return new Fact(n, id, "savings", date, amount, balance, raw, null, 0, Observation.POSTED,
            "ing-csv", Provenance.BANK, null, "ing-csv/1", at);
    }

    private static IngestEvent ingest(long n, String phase, String batch, String account, String file,
                                      Instant at) {
        boolean start = IngestEvent.START.equals(phase);
        return new IngestEvent(Envelope.stamped(n, IngestEvent.KIND, at), phase, batch, "sha256:test",
            file, account, "ing-csv", "ing-csv/1",
            start ? null : 2, start ? null : 0, start ? null : 0, start ? null : "ok");
    }

    private static long jsonLength(Object value) throws Exception {
        return Json.mapper().writeValueAsBytes(value).length;
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

    private static void config(Path configDir) throws Exception {
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("accounts.yaml"), """
            accounts:
              - ref: "savings"
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
                  match: "COFFEE"
            """);
        Files.writeString(configDir.resolve("transfers.yaml"), """
            windowDays: 4
            allowlist:
              - 'Transfer'
            """);
    }
}
