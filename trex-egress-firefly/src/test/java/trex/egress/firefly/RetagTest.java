package trex.egress.firefly;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trex.core.CanonicalEvent;
import trex.core.Confidence;
import trex.core.EventState;
import trex.core.Provenance;
import trex.core.TypeHint;
import trex.journal.Json;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The re-tag path (SPEC §5.8), which runs every time a rule is tuned.
 * <p>
 * It went to production untested and the first live execution found a bug that looked like
 * success: a GET returns {@code category_id} beside {@code category_name}, Firefly resolves the id
 * first, and echoing the split back with a stale id left the tag updated while the category stayed
 * put. Nothing errored. These tests exist so that cannot recur.
 */
class RetagTest {

    @TempDir
    Path dir;

    private HttpServer firefly;
    private HttpServer gateway;
    private final Map<String, String> groups = new ConcurrentHashMap<>();
    private final AtomicReference<JsonNode> lastPut = new AtomicReference<>();

    private static final AccountMap ACCOUNTS = new AccountMap(Map.of(
        "ing-salary", new AccountMap.Entry("ING Salary", AccountMap.Kind.ASSET, "3")));

    @BeforeEach
    void start() throws IOException {
        firefly = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        firefly.createContext("/api/v1/transactions/", ex -> {
            String id = ex.getRequestURI().getPath().substring("/api/v1/transactions/".length());
            if (ex.getRequestMethod().equals("PUT")) {
                JsonNode body = Json.mapper().readTree(ex.getRequestBody().readAllBytes());
                lastPut.set(body);
                groups.put(id, applied(id, body));
            }
            respond(ex, 200, groups.getOrDefault(id, "{}"));
        });
        firefly.start();

        gateway = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        gateway.createContext("/api/snapshot", ex -> respond(ex, 200, snapshot()));
        gateway.start();
    }

    @AfterEach
    void stop() {
        firefly.stop(0);
        gateway.stop(0);
    }

    /** The stub applies a PUT the way Firefly does: the category id wins over the name. */
    private String applied(String id, JsonNode body) {
        StringBuilder splits = new StringBuilder();
        for (JsonNode s : body.path("transactions")) {
            // This is the behaviour that broke the first live run, reproduced deliberately.
            String categoryName = s.hasNonNull("category_id")
                ? categoryOfId(id, s.get("category_id").asText())
                : s.path("category_name").asText(null);
            String categoryId = s.hasNonNull("category_id") ? s.get("category_id").asText() : "99";
            if (!splits.isEmpty()) {
                splits.append(",");
            }
            splits.append("""
                {"transaction_journal_id":"1","type":"withdrawal","amount":"%s","external_id":"%s",
                 "category_id":"%s","category_name":%s,"tags":%s,"notes":"trex n=1 rules=r1",
                 "source_id":"3","destination_name":"BUNNINGS","date":"2026-01-01","description":"x"}
                """.formatted(s.path("amount").asText("1.00"), s.path("external_id").asText("e1"),
                categoryId, quote(categoryName), s.path("tags").toString()));
        }
        String title = body.hasNonNull("group_title")
            ? "\"" + body.get("group_title").asText() + "\"" : "null";
        return """
            {"data":{"id":"%s","attributes":{"group_title":%s,"transactions":[%s]}}}
            """.formatted(id, title, splits);
    }

    /** The stale id keeps naming whatever it named before. */
    private String categoryOfId(String group, String categoryId) {
        JsonNode held = read(groups.get(group));
        for (JsonNode s : held.path("data").path("attributes").path("transactions")) {
            if (categoryId.equals(s.path("category_id").asText(null))) {
                return s.path("category_name").asText(null);
            }
        }
        return null;
    }

    private static String quote(String v) {
        return v == null ? "null" : "\"" + v + "\"";
    }

    private static JsonNode read(String json) {
        try {
            return Json.mapper().readTree(json == null ? "{}" : json);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** One transaction, which trex now says is DISCRETIONARY. */
    private String snapshot() {
        CanonicalEvent line = new CanonicalEvent(1, "e1", "ing-salary", null, "AUD",
            LocalDate.of(2026, 1, 1), -170, 0, "BUNNINGS 339000", "BUNNINGS 339000",
            TypeHint.WITHDRAWAL, null, null, null, EventState.EXTERNAL, Confidence.HIGH, List.of(),
            Provenance.BANK, "ing-csv", null, null, null, null, null, null, Instant.EPOCH);
        try {
            return """
                {"asOfN":1,"total":1,"rulesRevision":"r2","rows":[%s],
                 "categories":{"1":{"category":"DISCRETIONARY","origin":"RULE","why":"rule #1"}}}
                """.formatted(Json.mapper().writeValueAsString(line));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
        ex.close();
    }

    /** Seed Firefly's side: what the group holds before the re-tag. */
    private void given(String groupId, String groupTitle, String... categoryThenTag) {
        StringBuilder splits = new StringBuilder();
        for (int i = 0; i < categoryThenTag.length; i += 2) {
            if (!splits.isEmpty()) {
                splits.append(",");
            }
            splits.append("""
                {"transaction_journal_id":"%d","type":"withdrawal","amount":"1.70","external_id":"e1",
                 "category_id":"%d","category_name":"%s","tags":["trex","trex-category:%s"],
                 "notes":"trex n=1 rules=r1","source_id":"3","destination_name":"BUNNINGS",
                 "date":"2026-01-01","description":"x"}
                """.formatted(i, i + 10, categoryThenTag[i], categoryThenTag[i + 1]));
        }
        groups.put(groupId, """
            {"data":{"id":"%s","attributes":{"group_title":%s,"transactions":[%s]}}}
            """.formatted(groupId, groupTitle == null ? "null" : "\"" + groupTitle + "\"", splits));
    }

    private FireflyEgress.Outcome runWith(String projectedCategory) throws Exception {
        try (ProjectionCache cache = new ProjectionCache(null)) {
            cache.record(new ProjectionCache.Row("e1", 1, "500", projectedCategory, "r1"));
            cache.rulesRevision("r1");
            FireflyEgress egress = new FireflyEgress(
                new GatewayClient(URI.create("http://127.0.0.1:" + gateway.getAddress().getPort())),
                new FireflyClient(URI.create("http://127.0.0.1:" + firefly.getAddress().getPort()), "t"),
                cache, ACCOUNTS, false);
            return egress.run(new PrintStream(new ByteArrayOutputStream()));
        }
    }

    private JsonNode putSplit(int index) {
        JsonNode body = lastPut.get();
        assertNotNull(body, "nothing was written");
        return body.path("transactions").get(index);
    }

    // ---------------------------------------------------------------- the regression

    /**
     * The bug the first live run found. Updating {@code category_name} while echoing back the
     * {@code category_id} that came with the GET leaves Firefly resolving the old id — the tag
     * moves, the category does not, and nothing reports a failure.
     */
    @Test
    void changingTheCategoryClearsTheStaleId() throws Exception {
        given("500", null, "HOME_IMPROVEMENT", "HOME_IMPROVEMENT");
        FireflyEgress.Outcome outcome = runWith("HOME_IMPROVEMENT");

        assertEquals(1, outcome.retagged());
        assertFalse(putSplit(0).has("category_id"),
            "a stale category_id makes Firefly ignore the new name");
        assertEquals("DISCRETIONARY", putSplit(0).path("category_name").asText());
        assertEquals(List.of("trex", "trex-category:DISCRETIONARY"),
            Json.mapper().convertValue(putSplit(0).path("tags"), List.class));
    }

    // ---------------------------------------------------------------- compare and swap

    /** Category still matches the tag, so nothing of yours is at stake: both move. */
    @Test
    void anUntouchedTransactionFollowsTheRule() throws Exception {
        given("500", null, "HOME_IMPROVEMENT", "HOME_IMPROVEMENT");
        FireflyEgress.Outcome outcome = runWith("HOME_IMPROVEMENT");
        assertEquals(1, outcome.retagged());
        assertEquals(0, outcome.preserved());
        assertEquals("DISCRETIONARY", putSplit(0).path("category_name").asText());
    }

    /** Category no longer matches the tag, so you changed it: the tag moves, the category stays. */
    @Test
    void aHandEditedCategorySurvives() throws Exception {
        given("500", null, "VEHICLE", "HOME_IMPROVEMENT");
        FireflyEgress.Outcome outcome = runWith("HOME_IMPROVEMENT");
        assertEquals(1, outcome.preserved());
        assertEquals("VEHICLE", putSplit(0).path("category_name").asText(),
            "an edit made in Firefly is not the egress's to overwrite");
        assertEquals(List.of("trex", "trex-category:DISCRETIONARY"),
            Json.mapper().convertValue(putSplit(0).path("tags"), List.class),
            "the tag still records what trex last said");
    }

    /** No readable tag means we cannot know what we last said, so we must not overwrite. */
    @Test
    void anUnreadableTagPreservesRatherThanGuesses() throws Exception {
        groups.put("500", """
            {"data":{"id":"500","attributes":{"group_title":null,"transactions":[
              {"transaction_journal_id":"1","type":"withdrawal","amount":"1.70","external_id":"e1",
               "category_id":"10","category_name":"VEHICLE","tags":["holiday"],
               "notes":"trex n=1 rules=r1","source_id":"3","destination_name":"BUNNINGS",
               "date":"2026-01-01","description":"x"}]}}}
            """);
        FireflyEgress.Outcome outcome = runWith("HOME_IMPROVEMENT");
        assertEquals(1, outcome.preserved());
        assertEquals("VEHICLE", putSplit(0).path("category_name").asText());
    }

    // ---------------------------------------------------------------- splits

    /**
     * A split group is the case a PUT built from scratch destroys silently. Both splits must come
     * back, the group title with them, and the comparison happens per split — so one half can
     * follow the rule while the other keeps an edit.
     */
    @Test
    void splitsSurviveAndAreJudgedIndividually() throws Exception {
        given("500", "Bunnings, split by hand",
            "HOME_IMPROVEMENT", "HOME_IMPROVEMENT",     // untouched
            "FOOD", "HOME_IMPROVEMENT");                // edited by hand

        FireflyEgress.Outcome outcome = runWith("HOME_IMPROVEMENT");
        assertEquals(1, outcome.preserved());

        assertEquals(2, lastPut.get().path("transactions").size(), "a collapsed split is data loss");
        assertEquals("Bunnings, split by hand", lastPut.get().path("group_title").asText(),
            "Firefly rejects a multi-split group with no title");
        assertEquals("DISCRETIONARY", putSplit(0).path("category_name").asText(), "untouched: follows");
        assertEquals("FOOD", putSplit(1).path("category_name").asText(), "edited: preserved");
        for (int i = 0; i < 2; i++) {
            assertTrue(putSplit(i).path("tags").toString().contains("DISCRETIONARY"),
                "every split carries what trex last said");
        }
    }
}
