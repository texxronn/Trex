package trex.gateway.grid;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trex.core.EventState;
import trex.journal.Json;
import trex.sequencer.journal.JsonlJournal;
import trex.gateway.CombinedFold;
import trex.gateway.JournalView;
import trex.gateway.JournalWatcher;
import trex.gateway.GatewayServer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowseRoutesTest {

    @TempDir
    Path dir;

    private final HttpClient http = HttpClient.newHttpClient();
    private Path journalPath;
    private JournalWatcher<JournalView> watcher;
    private GatewayServer server;

    @BeforeEach
    void start() {
        journalPath = dir.resolve("journal.jsonl");
        try (JsonlJournal j = new JsonlJournal(journalPath)) {
            j.appendBatch(List.of(
                Lines.line(1, "a", "ing", "2026-06-02", -500, "Fast Transfer <b>x</b>", EventState.HELD),
                Lines.line(2, "b", "cba", "2026-06-01", 1200, "Salary", EventState.EXTERNAL)));
        }
        watcher = new JournalWatcher<>(journalPath, Clock.fixed(Instant.parse("2026-07-01T00:00:00Z"), ZoneOffset.UTC), CombinedFold::new);
        watcher.poll();
        server = new GatewayServer(watcher, null, Lines.categorizer(), "127.0.0.1", 0).start();
    }

    @AfterEach
    void stop() {
        server.close();
        watcher.close();
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void snapshotAndHead() throws Exception {
        HttpResponse<String> rows = get("/api/snapshot?sort=amount:asc&size=1");
        assertEquals(200, rows.statusCode());
        JsonNode body = Json.mapper().readTree(rows.body());
        assertEquals(2, body.get("total").asInt());
        assertEquals(2, body.get("asOfN").asLong());
        assertEquals("transactions", body.get("view").asText());
        assertEquals("a", body.get("rows").get(0).get("externalId").asText());
        assertEquals("Fast Transfer <b>x</b>", body.get("rows").get(0).get("rawDescription").asText());
        assertEquals("AUD", body.get("totals").get(0).get("currency").asText());
        assertEquals(700, body.get("totals").get(0).get("amount").asLong());

        JsonNode head = Json.mapper().readTree(get("/api/head").body());
        assertEquals(2, head.get("n").asLong());
        assertEquals(2, head.get("transactions").asInt());
        assertEquals("[\"cba\",\"ing\"]", head.get("accounts").toString());

        // No pages here: this service answers questions, trex-web draws them (§5.7).
        assertEquals(404, get("/").statusCode());
        assertEquals(404, get("/app.js").statusCode());
    }

    @Test
    void invalidQueryIs400AndWritesAre405() throws Exception {
        HttpResponse<String> bad = get("/api/snapshot?sort=balance:asc");
        assertEquals(400, bad.statusCode());
        assertTrue(bad.body().contains("invalid sort"));
        HttpResponse<String> post = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/api/snapshot"))
            .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(405, post.statusCode());
    }

    @Test
    void eventStreamSendsHeadOnJournalChange() throws Exception {
        watcher.start(60_000);
        HttpResponse<InputStream> res = http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/api/events")).GET().build(),
            HttpResponse.BodyHandlers.ofInputStream());
        assertTrue(res.headers().firstValue("Content-Type").orElseThrow().startsWith("text/event-stream"));
        BlockingQueue<JsonNode> heads = new LinkedBlockingQueue<>();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (BufferedReader lines = new BufferedReader(new InputStreamReader(res.body(), StandardCharsets.UTF_8))) {
                String l;
                String event = null;
                while ((l = lines.readLine()) != null) {
                    if (l.startsWith("event: ")) {
                        event = l.substring(7);
                    } else if (l.startsWith("data: ") && "head".equals(event)) {
                        heads.add(Json.mapper().readTree(l.substring(6)));
                    }
                }
            } catch (IOException _) {
                // closed at test end
            }
        });
        assertEquals(2, heads.poll(5, TimeUnit.SECONDS).get("n").asLong());
        try (JsonlJournal j = new JsonlJournal(journalPath)) {
            j.appendBatch(List.of(Lines.line(3, "c", "ing", "2026-06-03", -1, "Fee", EventState.EXTERNAL)));
        }
        JsonNode next = heads.poll(5, TimeUnit.SECONDS);
        while (next != null && next.get("n").asLong() < 3) {
            next = heads.poll(5, TimeUnit.SECONDS);
        }
        assertEquals(3, next.get("n").asLong());
        assertTrue(next.get("rows") == null, "head events never carry rows");
        reader.interrupt();
    }
}
