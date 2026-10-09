package trex.v2.hub;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trex.v2.core.Envelope;
import trex.v2.core.IngestEvent;
import trex.v2.core.LogLine;
import trex.v2.log.Json;
import trex.v2.log.JsonlJournal;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code GET /api/ingests?sinceN=} (QOL_Improvements.md §1): the filter the ingest toast sends on an
 * SSE delta, so the UI pulls only the batches that completed after the n it last saw.
 */
class HubIngestsApiTest {

    private static final Instant AT = Instant.parse("2026-10-01T00:00:00Z");

    @Test
    void sinceNReturnsOnlyLaterBatches(@TempDir Path dir) throws Exception {
        Path configDir = dir.resolve("config");
        config(configDir);
        Path journal = dir.resolve("trex.jsonl");
        try (JsonlJournal j = new JsonlJournal(journal)) {
            j.appendBatch(List.of(
                ingest(1, IngestEvent.START, "b1", "old.csv"),
                ingest(2, IngestEvent.COMPLETE, "b1", "old.csv"),
                ingest(3, IngestEvent.START, "b2", "new.csv"),
                ingest(4, IngestEvent.COMPLETE, "b2", "new.csv")));
        }

        try (HubService hub = HubService.start(new HubConfig(journal, dir.resolve("trex.sqlite"), configDir,
                "127.0.0.1", 0, 50))) {
            await(() -> hub.status().counts().getOrDefault("ingest_event", 0L) == 4L);
            HttpClient client = HttpClient.newHttpClient();
            URI base = URI.create("http://127.0.0.1:" + hub.port());

            // No filter: the whole history, newest first — the behaviour before and after sinceN.
            JsonNode all = get(client, base.resolve("/api/ingests"));
            assertEquals(2, all.get("rows").size(), all.toPrettyString());
            assertEquals("new.csv", all.get("rows").get(0).get("file").asText(), all.toPrettyString());
            assertEquals("old.csv", all.get("rows").get(1).get("file").asText(), all.toPrettyString());

            // sinceN = the first batch's n_end leaves only the second batch.
            JsonNode later = get(client, base.resolve("/api/ingests?sinceN=2"));
            assertEquals(1, later.get("rows").size(), later.toPrettyString());
            assertEquals("new.csv", later.get("rows").get(0).get("file").asText(), later.toPrettyString());

            // A batch at n_end = sinceN is not "later"; one at the head leaves nothing.
            JsonNode none = get(client, base.resolve("/api/ingests?sinceN=4"));
            assertEquals(0, none.get("rows").size(), none.toPrettyString());

            // A non-numeric filter is the caller's error, not an empty history.
            HttpResponse<String> malformed = client.send(HttpRequest
                .newBuilder(base.resolve("/api/ingests?sinceN=last")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(422, malformed.statusCode(), malformed.body());
        }
    }

    @Test
    void sinceNReturnsEveryLaterBatchPastTheHistoryLimit(@TempDir Path dir) throws Exception {
        Path configDir = dir.resolve("config");
        config(configDir);
        Path journal = dir.resolve("trex.jsonl");
        try (JsonlJournal j = new JsonlJournal(journal)) {
            List<LogLine> events = new ArrayList<>();
            for (int i = 1; i <= 55; i++) {
                events.add(ingest(2L * i - 1, IngestEvent.START, "b" + i, "file-" + i + ".csv"));
                events.add(ingest(2L * i, IngestEvent.COMPLETE, "b" + i, "file-" + i + ".csv"));
            }
            j.appendBatch(events);
        }

        try (HubService hub = HubService.start(new HubConfig(journal, dir.resolve("trex.sqlite"), configDir,
                "127.0.0.1", 0, 50))) {
            await(() -> hub.status().counts().getOrDefault("ingest_event", 0L) == 110L);
            HttpClient client = HttpClient.newHttpClient();
            URI base = URI.create("http://127.0.0.1:" + hub.port());

            // The Jobs history is a page: the newest 50.
            JsonNode all = get(client, base.resolve("/api/ingests"));
            assertEquals(50, all.get("rows").size(), all.toPrettyString());

            // A delta catch-up is not a page: sinceN=0 is a log that was empty when the page
            // opened, and all 55 batches must be reported or the toast drops the older ones.
            JsonNode every = get(client, base.resolve("/api/ingests?sinceN=0"));
            assertEquals(55, every.get("rows").size(), every.toPrettyString());
            assertEquals("file-55.csv", every.get("rows").get(0).get("file").asText());
            assertEquals("file-1.csv", every.get("rows").get(54).get("file").asText());
        }
    }

    private static JsonNode get(HttpClient client, URI uri) throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(uri).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return Json.mapper().readTree(response.body());
    }

    private static IngestEvent ingest(long n, String phase, String batch, String file) {
        boolean start = IngestEvent.START.equals(phase);
        return new IngestEvent(Envelope.stamped(n, IngestEvent.KIND, AT), phase, batch, "sha256:test",
            file, "ing-savings", "ing-csv", "ing-csv/1",
            start ? null : 3, start ? null : 0, start ? null : 0, start ? null : "ok");
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
}
