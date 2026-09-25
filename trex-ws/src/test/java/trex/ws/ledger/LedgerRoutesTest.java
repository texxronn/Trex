package trex.ws.ledger;

import trex.core.BalanceSource;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trex.core.Candidate;
import trex.core.CandidateResult;
import trex.core.Provenance;
import trex.core.state.LedgerView;
import trex.journal.Json;
import trex.sequencer.http.HttpApi;
import trex.core.account.Account;
import trex.core.account.AccountRegistry;
import trex.sequencer.ingest.CandidateInput;
import trex.sequencer.ingest.Sequencer;
import trex.sequencer.ingest.TransferRules;
import trex.sequencer.journal.JsonlJournal;
import trex.sequencer.journal.Recovery;
import trex.sequencer.state.Fold;
import trex.ws.CombinedFold;
import trex.ws.JournalView;
import trex.ws.JournalWatcher;
import trex.ws.GatewayServer;

import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Resolver API contract, CSRF guard and the decision round trip through an in-process sequencer. */
class LedgerRoutesTest {

    @TempDir
    Path dir;

    private final HttpClient http = HttpClient.newHttpClient();
    private JsonlJournal journal;
    private Sequencer sequencer;
    private HttpApi sequencerApi;
    private JournalWatcher<JournalView> watcher;
    private GatewayServer gateway;

    @BeforeEach
    void start() {
        Path path = dir.resolve("journal.jsonl");
        Recovery.recover(path, path);
        journal = new JsonlJournal(path);
        Clock clock = Clock.fixed(Instant.parse("2026-07-01T00:00:00Z"), ZoneOffset.UTC);
        sequencer = new Sequencer(journal, Fold.fold(journal),
            new AccountRegistry(List.of(new Account("ing-savings", "AUD", BalanceSource.STATEMENT), new Account("cba-everyday", "AUD", BalanceSource.STATEMENT))),
            new TransferRules(List.of("Fast Transfer", "Transfer from", "Osko"), 3), clock);
        sequencerApi = new HttpApi(sequencer, 0, HttpApi.DEFAULT_MAX_BODY_BYTES).start();
        watcher = new JournalWatcher<>(path, clock, CombinedFold::new);
        gateway = new GatewayServer(watcher,
            new SequencerClient(URI.create("http://127.0.0.1:" + sequencerApi.port())), categorizer(), "127.0.0.1", 0).start();
    }

    /** Small rules file for these tests; see src/test/resources. Not watched: these are read-only. */
    private static trex.ws.Rules categorizer() {
        java.nio.file.Path file = java.nio.file.Path.of("src", "test", "resources", "gateway-categories.yaml");
        trex.ws.RuleStore store = new trex.ws.RuleStore(file, null);
        return new trex.ws.Rules(store, store.load(), store.revision());
    }

    @AfterEach
    void stop() {
        gateway.close();
        sequencerApi.close();
        journal.close();
    }

    private String base() {
        return "http://127.0.0.1:" + gateway.port();
    }

    private String ingest(String ref, String account, String date, long amount, String raw) {
        Candidate c = new Candidate(ref, account, LocalDate.parse(date), amount, raw, 1000, null, null, null, "test", Provenance.BANK);
        CandidateResult r = sequencer.submitCandidates(false, Stream.of(c).map(CandidateInput::bound).toList()).results().getFirst();
        return ((CandidateResult.Held) r).externalId();
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base() + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String body, String contentType, boolean adminHeader, String origin) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base() + "/api/decisions"))
            .POST(HttpRequest.BodyPublishers.ofString(body));
        if (contentType != null) {
            b.header("Content-Type", contentType);
        }
        if (adminHeader) {
            b.header("X-Trex-Admin", "1");
        }
        if (origin != null) {
            b.setHeader("Origin", origin);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> decide(String body) throws Exception {
        return post(body, "application/json", true, null);
    }

    @Test
    void theApiIsAllThereIs() throws Exception {
        // The pages came back when trex-web merged in (§5.4): one process serves both, so the
        // resolve page can post the decision it exists to collect.
        assertEquals(200, get("/").statusCode());
        assertEquals(200, get("/resolve").statusCode());
        assertEquals(405, get("/api/decisions").statusCode());
        assertEquals(200, get("/api/ledger").statusCode());
    }

    @Test
    void decisionRoundTripUpdatesStateFromTheJournal() throws Exception {
        String dave = ingest("r1", "ing-savings", "2026-06-01", -700, "Osko to Dave");
        String out = ingest("r2", "ing-savings", "2026-06-02", -500, "Fast Transfer");
        String in = ingest("r3", "cba-everyday", "2026-06-20", 500, "Transfer from ING");
        watcher.poll();

        JsonNode state = Json.mapper().readTree(get("/api/ledger").body());
        assertEquals(3, state.get("held").size());
        assertEquals(3, state.get("n").asLong());
        assertTrue(state.get("error").isNull());

        HttpResponse<String> external = decide("{\"action\":\"MARK_EXTERNAL\",\"externalId\":\"" + dave + "\",\"comment\":\"Dave\"}");
        assertEquals(200, external.statusCode(), external.body());
        JsonNode result = Json.mapper().readTree(external.body()).get("results").get(0);
        assertEquals("Resolved", result.get("type").asText());
        assertTrue(result.get("candidateRef").asText().startsWith("ui-"));

        // the page state does not change until the journal line is tailed
        assertEquals(3, Json.mapper().readTree(get("/api/ledger").body()).get("held").size());
        watcher.poll();
        assertEquals(2, Json.mapper().readTree(get("/api/ledger").body()).get("held").size());

        HttpResponse<String> pair = decide("{\"action\":\"CONFIRM_TRANSFER\",\"legA\":\"" + out + "\",\"legB\":\"" + in + "\",\"comment\":null}");
        JsonNode pairResult = Json.mapper().readTree(pair.body()).get("results").get(0);
        assertEquals("Resolved", pairResult.get("type").asText());
        assertTrue(pairResult.get("externalId").asText().startsWith("TRF-"));

        HttpResponse<String> again = decide("{\"action\":\"MARK_EXTERNAL\",\"externalId\":\"" + dave + "\"}");
        assertEquals("Rejected", Json.mapper().readTree(again.body()).get("results").get(0).get("type").asText());

        watcher.poll();
        JsonNode after = Json.mapper().readTree(get("/api/ledger").body());
        assertEquals(0, after.get("held").size());
        assertEquals(0, after.get("review").size());
    }

    @Test
    void csrfGuardAndValidation() throws Exception {
        String body = "{\"action\":\"MARK_EXTERNAL\",\"externalId\":\"x\"}";
        assertEquals(403, post(body, "application/json", false, null).statusCode());
        assertEquals(415, post(body, "text/plain", true, null).statusCode());
        assertEquals(403, post(body, "application/json", true, "http://evil.example").statusCode());
        assertEquals(200, post(body, "application/json", true, base()).statusCode());
        assertEquals(400, decide("{\"action\":\"DELETE_EVERYTHING\"}").statusCode());
        assertEquals(400, decide("{\"action\":\"MARK_EXTERNAL\",\"n\":\"1\"}").statusCode());
        assertEquals(400, decide("{\"action\":\"MARK_EXTERNAL\",\"externalId\":5}").statusCode());
        assertEquals(400, decide("[1,2]").statusCode());
        assertEquals(400, decide("{nope").statusCode());
        String huge = "{\"action\":\"MARK_EXTERNAL\",\"comment\":\"" + "x".repeat(70_000) + "\"}";
        assertEquals(413, decide(huge).statusCode());
    }

    @Test
    void eventStreamAnnouncesChangeWithoutRows() throws Exception {
        watcher.start(60_000);   // event-driven; fallback far beyond the test's wait
        try {
            HttpResponse<java.io.InputStream> res = http.send(
                HttpRequest.newBuilder(URI.create(base() + "/api/events")).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, res.statusCode());
            assertTrue(res.headers().firstValue("Content-Type").orElseThrow().startsWith("text/event-stream"));
            java.util.concurrent.BlockingQueue<JsonNode> states = new java.util.concurrent.LinkedBlockingQueue<>();
            Thread reader = Thread.ofVirtual().start(() -> {
                try (var lines = new java.io.BufferedReader(new java.io.InputStreamReader(res.body(), StandardCharsets.UTF_8))) {
                    String l;
                    while ((l = lines.readLine()) != null) {
                        if (l.startsWith("data: ")) {
                            states.add(Json.mapper().readTree(l.substring(6)));
                        }
                    }
                } catch (IOException _) {
                    // stream closed at test end
                }
            });

            // SPEC §7 test 21: a frame says what moved and carries no rows. A rule change can
            // touch every row, so shipping them here would cost ~1.2 MiB per edit per client.
            JsonNode first = states.poll(5, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(0, first.get("n").asLong());
            assertNull(first.get("held"), "the stream must not carry ledger rows");
            assertNull(first.get("rows"), "the stream must not carry table rows");

            ingest("r1", "ing-savings", "2026-06-01", -700, "Osko to Dave");
            JsonNode next = states.poll(5, java.util.concurrent.TimeUnit.SECONDS);
            while (next != null && next.get("n").asLong() < 1) {
                next = states.poll(5, java.util.concurrent.TimeUnit.SECONDS);
            }
            assertEquals(1, next.get("n").asLong());
            assertNull(next.get("held"));
            // The rows themselves are one fetch away, and that fetch reflects the same instant.
            assertEquals(1, Json.mapper().readTree(get("/api/ledger").body()).get("held").size());
            assertEquals(1, gateway.streamCount());
            reader.interrupt();
        } finally {
            watcher.close();
        }
    }

    @Test
    void heartbeatKeepsStreamAliveWithoutChanges() throws Exception {
        try (GatewayServer fast = new GatewayServer(watcher,
            new SequencerClient(URI.create("http://127.0.0.1:" + sequencerApi.port())), categorizer(), "127.0.0.1", 0, 100).start()) {
            HttpResponse<java.io.InputStream> res = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + fast.port() + "/api/events")).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
            var lines = new java.io.BufferedReader(new java.io.InputStreamReader(res.body(), StandardCharsets.UTF_8));
            boolean sawPing = false;
            for (int i = 0; i < 20 && !sawPing; i++) {
                sawPing = ": ping".equals(lines.readLine());
            }
            assertTrue(sawPing);
            res.body().close();
        }
    }

    @Test
    void unreachableSequencerIs502() throws Exception {
        int deadPort;
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            deadPort = s.getLocalPort();
        }
        try (GatewayServer lonely = new GatewayServer(watcher,
            new SequencerClient(URI.create("http://127.0.0.1:" + deadPort)), categorizer(), "127.0.0.1", 0).start()) {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + lonely.port() + "/api/decisions"))
                .header("Content-Type", "application/json").header("X-Trex-Admin", "1")
                .POST(HttpRequest.BodyPublishers.ofString("{\"action\":\"MARK_EXTERNAL\",\"externalId\":\"x\"}")).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertEquals(502, r.statusCode());
        }
    }
}
