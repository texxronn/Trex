package trex.v2.hub;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trex.v2.core.Fact;
import trex.v2.core.Observation;
import trex.v2.core.Provenance;
import trex.v2.log.JsonlJournal;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SSE (V2-PROPOSAL.md §7.4): a snapshot on connect, then a delta when the index moves. */
class HubSseTest {

    private static final Instant AT = Instant.parse("2026-09-30T00:00:00Z");

    @Test
    void changeFeedDeliversAndCloses() throws Exception {
        HubEvents events = new HubEvents();
        HubEvents.Subscription sub = events.subscribe();
        events.publish(new HubEvents.Change(7, 100, "cfg", "derive/1", "statehash/1"));
        HubEvents.Change change = sub.poll(Duration.ofSeconds(1)).orElseThrow();
        assertEquals(7, change.n());
        assertEquals(100, change.offset());
        sub.close();
        events.close();
        assertFalse(events.isOpen());
    }

    @Test
    void eventsEndpointSendsSnapshotThenDelta(@TempDir Path dir) throws Exception {
        Path configDir = dir.resolve("config");
        Files.createDirectories(configDir);
        config(configDir);
        Path journal = dir.resolve("trex.jsonl");
        try (JsonlJournal j = new JsonlJournal(journal)) {
            j.appendBatch(List.of(fact(1, "a")));
        }

        Path index = dir.resolve("trex.sqlite");
        try (HubService hub = HubService.start(new HubConfig(journal, index, configDir, "127.0.0.1", 0, 50))) {
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<InputStream> response = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + hub.port() + "/api/events")).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, response.statusCode());
            assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("text/event-stream"));

            BlockingQueue<String> lines = new LinkedBlockingQueue<>();
            Thread reader = Thread.ofVirtual().start(() -> {
                try (BufferedReader in = new BufferedReader(new InputStreamReader(response.body()))) {
                    String line;
                    while ((line = in.readLine()) != null) {
                        lines.add(line);
                    }
                } catch (Exception ignored) {
                    // client disconnected
                }
            });

            assertTrue(awaitLine(lines, "event: snapshot"), "snapshot on connect");

            try (JsonlJournal j = new JsonlJournal(journal)) {
                j.appendBatch(List.of(fact(2, "b")));
            }
            assertTrue(awaitLine(lines, "event: delta"), "delta after the journal moved");

            response.body().close();
            reader.join(2000);
        }
    }

    private static boolean awaitLine(BlockingQueue<String> lines, String expected) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            String line = lines.poll(200, TimeUnit.MILLISECONDS);
            if (line != null && line.equals(expected)) {
                return true;
            }
        }
        return false;
    }

    private static Fact fact(long n, String id) {
        return new Fact(n, id, "ing-savings", LocalDate.of(2026, 9, 1), -1000, 0, "COLES 1234", null, 0,
            Observation.POSTED, "ing-csv", Provenance.BANK, null, "ing-csv/1", AT);
    }

    private static void config(Path configDir) throws Exception {
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
