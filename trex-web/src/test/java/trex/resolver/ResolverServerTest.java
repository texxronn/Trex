package trex.resolver;

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
import trex.sequencer.ingest.Account;
import trex.sequencer.ingest.AccountRegistry;
import trex.sequencer.ingest.CandidateInput;
import trex.sequencer.ingest.Sequencer;
import trex.sequencer.ingest.TransferRules;
import trex.sequencer.journal.JsonlJournal;
import trex.sequencer.journal.Recovery;
import trex.sequencer.state.Fold;
import trex.web.CombinedFold;
import trex.web.JournalView;
import trex.web.JournalWatcher;
import trex.web.WebServer;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Resolver API contract, CSRF guard and the decision round trip through an in-process sequencer. */
class ResolverServerTest {

    @TempDir
    Path dir;

    private final HttpClient http = HttpClient.newHttpClient();
    private JsonlJournal journal;
    private Sequencer sequencer;
    private HttpApi sequencerApi;
    private JournalWatcher<JournalView> watcher;
    private WebServer resolver;

    @BeforeEach
    void start() {
        Path path = dir.resolve("journal.jsonl");
        Recovery.recover(path, path);
        journal = new JsonlJournal(path);
        Clock clock = Clock.fixed(Instant.parse("2026-07-01T00:00:00Z"), ZoneOffset.UTC);
        sequencer = new Sequencer(journal, Fold.fold(journal),
            new AccountRegistry(List.of(new Account("ing-savings", "AUD", "1"), new Account("cba-everyday", "AUD", "2"))),
            new TransferRules(List.of("Fast Transfer", "Transfer from", "Osko"), 3), clock);
        sequencerApi = new HttpApi(sequencer, 0, HttpApi.DEFAULT_MAX_BODY_BYTES).start();
        watcher = new JournalWatcher<>(path, clock, CombinedFold::new);
        resolver = new WebServer(watcher,
            new SequencerClient(URI.create("http://127.0.0.1:" + sequencerApi.port())), categorizer(), "127.0.0.1", 0).start();
    }

    /** Small rules file for these tests; see src/test/resources. */
    private static trex.category.Categorizer categorizer() {
        return trex.category.CategoryRules.load(java.nio.file.Path.of("src", "test", "resources", "resolver-categories.yaml"));
    }

    @AfterEach
    void stop() {
        resolver.close();
        sequencerApi.close();
        journal.close();
    }

    private String base() {
        return "http://127.0.0.1:" + resolver.port();
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
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base() + "/api/resolve/decisions"))
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
    void servesStaticPageWithSecurityHeaders() throws Exception {
        HttpResponse<String> page = get("/");
        assertEquals(200, page.statusCode());
        assertTrue(page.headers().firstValue("Content-Type").orElseThrow().startsWith("text/html"));
        assertTrue(page.headers().firstValue("Content-Security-Policy").orElseThrow().contains("default-src 'self'"));
        assertEquals("nosniff", page.headers().firstValue("X-Content-Type-Options").orElseThrow());
        assertTrue(page.body().contains("/app.js"));
        assertEquals(200, get("/app.js").statusCode());
        assertEquals(200, get("/app.css").statusCode());
        assertEquals(404, get("/secret").statusCode());
        assertEquals(405, get("/api/resolve/decisions").statusCode());
    }

    /**
     * Each page must load its OWN script and stylesheet. Before the merge both pages were
     * separate services rooted at {@code /}, so both pointed at {@code /app.js}; sharing one
     * port made that silently wrong — {@code /resolve} pulled the browsing page's script,
     * which threw on the first element it could not find and left the page blank. A status
     * code cannot catch this, so the asset references themselves are asserted.
     */
    @Test
    void eachPageLoadsItsOwnAssets() throws Exception {
        assertEquals(List.of("/app.css", "/app.js"), assetsOf(get("/").body()));
        assertEquals(List.of("/resolve/app.css", "/resolve/app.js"), assetsOf(get("/resolve").body()));
        for (String asset : List.of("/app.css", "/app.js", "/resolve/app.css", "/resolve/app.js")) {
            assertEquals(200, get(asset).statusCode(), asset);
        }
    }

    /** The script and stylesheet a page pulls in, in the order the document lists them. */
    private static List<String> assetsOf(String html) {
        return Pattern.compile("(?:src|href)=\"([^\"]+\\.(?:js|css))\"")
            .matcher(html)
            .results()
            .map((m) -> m.group(1))
            .sorted()
            .toList();
    }

    @Test
    void pageAdaptsToNarrowScreensAndTouch() throws Exception {
        assertTrue(get("/resolve").body().contains("name=\"viewport\" content=\"width=device-width"));
        String css = get("/resolve/app.css").body();
        assertTrue(css.contains("@media (max-width: 720px)"), "phone: rows become record cards");
        assertTrue(css.contains("@media (max-width: 1024px)"), "tablet: fixed column widths released");
        assertTrue(css.contains("@media (pointer: coarse)"), "touch: larger hit targets");
        assertTrue(css.contains("@media (prefers-reduced-motion: reduce)"));
    }

    @Test
    void decisionRoundTripUpdatesStateFromTheJournal() throws Exception {
        String dave = ingest("r1", "ing-savings", "2026-06-01", -700, "Osko to Dave");
        String out = ingest("r2", "ing-savings", "2026-06-02", -500, "Fast Transfer");
        String in = ingest("r3", "cba-everyday", "2026-06-20", 500, "Transfer from ING");
        watcher.poll();

        JsonNode state = Json.mapper().readTree(get("/api/resolve/state").body());
        assertEquals(3, state.get("held").size());
        assertEquals(3, state.get("n").asLong());
        assertTrue(state.get("error").isNull());

        HttpResponse<String> external = decide("{\"action\":\"MARK_EXTERNAL\",\"externalId\":\"" + dave + "\",\"comment\":\"Dave\"}");
        assertEquals(200, external.statusCode(), external.body());
        JsonNode result = Json.mapper().readTree(external.body()).get("results").get(0);
        assertEquals("Resolved", result.get("type").asText());
        assertTrue(result.get("candidateRef").asText().startsWith("ui-"));

        // the page state does not change until the journal line is tailed
        assertEquals(3, Json.mapper().readTree(get("/api/resolve/state").body()).get("held").size());
        watcher.poll();
        assertEquals(2, Json.mapper().readTree(get("/api/resolve/state").body()).get("held").size());

        HttpResponse<String> pair = decide("{\"action\":\"CONFIRM_TRANSFER\",\"legA\":\"" + out + "\",\"legB\":\"" + in + "\",\"comment\":null}");
        JsonNode pairResult = Json.mapper().readTree(pair.body()).get("results").get(0);
        assertEquals("Resolved", pairResult.get("type").asText());
        assertTrue(pairResult.get("externalId").asText().startsWith("TRF-"));

        HttpResponse<String> again = decide("{\"action\":\"MARK_EXTERNAL\",\"externalId\":\"" + dave + "\"}");
        assertEquals("Rejected", Json.mapper().readTree(again.body()).get("results").get(0).get("type").asText());

        watcher.poll();
        JsonNode after = Json.mapper().readTree(get("/api/resolve/state").body());
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
    void eventStreamPushesStateOnJournalChange() throws Exception {
        watcher.start(60_000);   // event-driven; fallback far beyond the test's wait
        try {
            HttpResponse<java.io.InputStream> res = http.send(
                HttpRequest.newBuilder(URI.create(base() + "/api/resolve/events")).GET().build(),
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

            JsonNode first = states.poll(5, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(0, first.get("held").size());

            ingest("r1", "ing-savings", "2026-06-01", -700, "Osko to Dave");
            JsonNode next = states.poll(5, java.util.concurrent.TimeUnit.SECONDS);
            while (next != null && next.get("n").asLong() < 1) {
                next = states.poll(5, java.util.concurrent.TimeUnit.SECONDS);
            }
            assertEquals(1, next.get("held").size());
            assertEquals(1, resolver.streamCount());
            reader.interrupt();
        } finally {
            watcher.close();
        }
    }

    @Test
    void heartbeatKeepsStreamAliveWithoutChanges() throws Exception {
        try (WebServer fast = new WebServer(watcher,
            new SequencerClient(URI.create("http://127.0.0.1:" + sequencerApi.port())), categorizer(), "127.0.0.1", 0, 100).start()) {
            HttpResponse<java.io.InputStream> res = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + fast.port() + "/api/resolve/events")).GET().build(),
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
        try (WebServer lonely = new WebServer(watcher,
            new SequencerClient(URI.create("http://127.0.0.1:" + deadPort)), categorizer(), "127.0.0.1", 0).start()) {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + lonely.port() + "/api/resolve/decisions"))
                .header("Content-Type", "application/json").header("X-Trex-Admin", "1")
                .POST(HttpRequest.BodyPublishers.ofString("{\"action\":\"MARK_EXTERNAL\",\"externalId\":\"x\"}")).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertEquals(502, r.statusCode());
        }
    }
}
