package trex.v2.hub;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trex.v2.core.Observation;
import trex.v2.core.Provenance;
import trex.v2.log.Json;
import trex.v2.sequencer.SequencerService;
import trex.v2.sequencer.api.FactBatch;
import trex.v2.sequencer.api.FactDraft;
import trex.v2.hub.api.AckDiff;
import trex.v2.hub.api.AckJson;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §15.7: a moved reviewed period flips red for the user who acked it, and only for them, and the
 * diff names the rows that moved. Runs a real sequencer and hub end to end.
 */
class HubAckTest {

    @Test
    void movingAPeriodInvalidatesItForThatUserAndNamesTheMovedRows(@TempDir Path dir) throws Exception {
        Path configDir = dir.resolve("config");
        Files.createDirectories(configDir);
        config(configDir);
        Path journal = dir.resolve("trex.jsonl");
        Path index = dir.resolve("trex.sqlite");

        try (SequencerService sequencer = SequencerService.start(journal, configDir, "127.0.0.1", 0, Clock.systemUTC())) {
            HttpClient client = HttpClient.newHttpClient();
            URI seq = URI.create("http://127.0.0.1:" + sequencer.port());
            postFact(client, seq, "2026-09-01", -1000, "COLES 1234");

            try (HubService hub = HubService.start(new HubConfig(journal, index, configDir, "127.0.0.1", 0, 50,
                    seq.toString()))) {
                URI api = URI.create("http://127.0.0.1:" + hub.port());
                await(() -> hub.status().counts().getOrDefault("txn_current", 0L) == 1L);

                assertEquals(200, postJson(client, api.resolve("/api/acks"),
                    "{\"user\":\"ron\",\"period\":\"2026-09\"}").statusCode());
                await(() -> {
                    var acks = acks(hub);
                    return acks.size() == 1 && !acks.getFirst().stale();
                });
                AckJson green = acks(hub).getFirst();
                assertEquals("ron", green.user());
                assertFalse(green.stale());

                // A new fact in the same period moves its content.
                postFact(client, seq, "2026-09-02", -2000, "COLES 5678");
                await(() -> hub.status().counts().getOrDefault("txn_current", 0L) == 2L);
                await(() -> acks(hub).getFirst().stale());

                HttpResponse<String> diff = get(client, api.resolve("/api/acks/diff?user=ron&period=2026-09"));
                assertEquals(200, diff.statusCode());
                AckDiff moved = Json.mapper().readValue(diff.body(), AckDiff.class);
                assertTrue(moved.stale());
                assertEquals(1, moved.moved().size(), "exactly the new row moved");

                // Only ron acked; the period is not red for anyone else.
                assertTrue(get(client, api.resolve("/api/acks/diff?user=mel&period=2026-09")).statusCode() == 404);
            }
        }
    }

    private static List<AckJson> acks(HubService hub) {
        return hub.acks();
    }

    private static void postFact(HttpClient client, URI seq, String date, long amount, String raw) throws Exception {
        FactDraft draft = new FactDraft("ing-savings", LocalDate.parse(date), amount, 0L, raw, null,
            Observation.POSTED, "ing-csv", Provenance.BANK, null, "ing-csv/1", null);
        HttpResponse<String> response = postJson(client, seq.resolve("/facts"),
            Json.mapper().writeValueAsString(new FactBatch(false, List.of(draft))));
        assertEquals(200, response.statusCode(), response.body());
    }

    private static HttpResponse<String> postJson(HttpClient client, URI uri, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(uri).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(HttpClient client, URI uri) throws Exception {
        return client.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
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
              - id: "mel"
                name: "Mel"
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
