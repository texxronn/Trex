package trex.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trex.category.RuleStore;
import trex.core.CanonicalEvent;
import trex.core.Confidence;
import trex.core.EventState;
import trex.core.Provenance;
import trex.core.TypeHint;
import trex.journal.Json;
import trex.sequencer.journal.JsonlJournal;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SPEC §7 tests 19-21: the write path, its rails, and the single owner. */
class RuleRoutesTest {

    @TempDir
    Path dir;

    private static final String RULES = """
        categories: [SALARY, GROCERIES, FOOD]

        rules:
          # The two big chains.
          - category: GROCERIES
            comment: "supermarkets"
            when:
              match: "woolworths|coles"
        """;

    private final HttpClient http = HttpClient.newHttpClient();
    private JournalWatcher<JournalView> watcher;
    private GatewayServer server;
    private Rules rules;

    @BeforeEach
    void start() throws IOException {
        Files.writeString(dir.resolve("categories.yaml"), RULES);
        Files.writeString(dir.resolve("pins.yaml"), "pins: []\n");

        Path journal = dir.resolve("journal.jsonl");
        try (JsonlJournal j = new JsonlJournal(journal)) {
            j.appendBatch(List.of(
                line(1, "g1", -8500, "WOOLWORTHS 1234"),
                line(2, "u1", -520, "PICCOLO ME"),
                line(3, "u2", -640, "PICCOLO ME - Visa Purchase")));
        }
        rules = Rules.load(dir).watch();
        watcher = new JournalWatcher<>(journal, Clock.systemUTC(), CombinedFold::new);
        watcher.poll();
        server = new GatewayServer(watcher, null, rules, "127.0.0.1", 0).start();
    }

    @AfterEach
    void stop() {
        server.close();
        watcher.close();
        rules.close();
    }

    private String base() {
        return "http://127.0.0.1:" + server.port();
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base() + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base() + path))
            .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body))
            .header("X-Trex-Admin", "1");
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> r) throws IOException {
        return Json.mapper().readTree(r.body());
    }

    private String revision() {
        return rules.revision();
    }

    // ---------------------------------------------------------------- the dry run

    @Test
    void aProposalReportsItsBlastRadiusAndWritesNothing() throws Exception {
        String before = Files.readString(dir.resolve("categories.yaml"));
        JsonNode p = json(get("/api/proposal?category=FOOD&match=piccolo&comment=cafes"));

        assertTrue(p.get("allowed").asBoolean());
        assertEquals(2, p.get("matched").asInt());
        assertEquals(2, p.get("fromUncategorized").asInt());
        assertEquals(-1160, p.get("total").asLong());
        assertTrue(p.get("takes").isEmpty(), "nothing else claims these rows");
        assertTrue(p.get("insertBefore").isNull(), "so it can simply be appended");
        assertTrue(p.get("yaml").asText().contains("category: \"FOOD\""), "the exact text to be written");
        assertEquals(before, Files.readString(dir.resolve("categories.yaml")), "a dry run writes nothing");
    }

    @Test
    void aProposalThatWouldStealRowsSaysWhichRuleLosesThem() throws Exception {
        JsonNode p = json(get("/api/proposal?category=FOOD&match=woolworths"));
        assertEquals(1, p.get("matched").asInt());
        assertEquals(1, p.get("takes").get(0).get("ruleIndex").asInt());
        assertEquals("GROCERIES", p.get("takes").get(0).get("category").asText());
        assertEquals(1, p.get("insertBefore").asInt(), "it must go in front of the rule it takes from");
    }

    @Test
    void aProposalThatChangesNothingIsRefused() throws Exception {
        JsonNode p = json(get("/api/proposal?category=GROCERIES&match=woolworths"));
        assertFalse(p.get("allowed").asBoolean());
        assertTrue(p.get("refusal").asText().contains("already GROCERIES"));
    }

    // ---------------------------------------------------------------- writing

    @Test
    void writingARuleRecategorisesWithoutARestart() throws Exception {
        assertEquals("UNCATEGORIZED", categoryOf("u1"));
        String before = revision();

        HttpResponse<String> r = send("POST", "/api/rules", """
            {"category":"FOOD","comment":"cafes the chains miss","when":{"match":"piccolo"},
             "rulesRevision":"%s"}""".formatted(before));
        assertEquals(200, r.statusCode());
        assertEquals("appended", json(r).get("placement").asText());
        assertNotEquals(before, json(r).get("rulesRevision").asText());

        // No restart, no reload call from the test: the service is already serving the new rules.
        assertEquals("FOOD", categoryOf("u1"));
        assertEquals("GROCERIES", categoryOf("g1"), "and the rows it was not about are unmoved");
        assertTrue(Files.readString(dir.resolve("categories.yaml")).contains("# The two big chains."),
            "the hand-written comment survived the machine write");
    }

    @Test
    void aRuleThatWouldChangeNothingIsRefusedWithoutWriting() throws Exception {
        String before = Files.readString(dir.resolve("categories.yaml"));
        HttpResponse<String> r = send("POST", "/api/rules", """
            {"category":"GROCERIES","when":{"match":"woolworths"},"rulesRevision":"%s"}""".formatted(revision()));
        assertEquals(422, r.statusCode());
        assertTrue(r.body().contains("already GROCERIES"));
        assertEquals(before, Files.readString(dir.resolve("categories.yaml")));
    }

    @Test
    void anUndeclaredCategoryIsRefusedWithoutWriting() throws Exception {
        String before = Files.readString(dir.resolve("categories.yaml"));
        HttpResponse<String> r = send("POST", "/api/rules", """
            {"category":"HOLIDAYS","when":{"match":"qantas"},"rulesRevision":"%s"}""".formatted(revision()));
        assertEquals(400, r.statusCode());
        assertEquals(before, Files.readString(dir.resolve("categories.yaml")));
    }

    @Test
    void aStaleRevisionIsRefusedWithConflict() throws Exception {
        String stale = revision();
        assertEquals(200, send("POST", "/api/rules", """
            {"category":"FOOD","when":{"match":"piccolo"},"rulesRevision":"%s"}""".formatted(stale)).statusCode());

        HttpResponse<String> second = send("POST", "/api/rules", """
            {"category":"FOOD","when":{"match":"gelato"},"rulesRevision":"%s"}""".formatted(stale));
        assertEquals(409, second.statusCode());
        assertTrue(second.body().contains("changed since"));
    }

    @Test
    void writingAPinOverridesARuleForOneTransaction() throws Exception {
        assertEquals("GROCERIES", categoryOf("g1"));
        HttpResponse<String> r = send("POST", "/api/pins", """
            {"category":"FOOD","comment":"the cafe inside the supermarket",
             "externalIds":["g1"],"rulesRevision":"%s"}""".formatted(revision()));
        assertEquals(200, r.statusCode());
        assertEquals("FOOD", categoryOf("g1"));
        assertTrue(Files.readString(dir.resolve("pins.yaml")).contains("the cafe inside the supermarket"));
    }

    @Test
    void entriesCanBeReplacedAndDeleted() throws Exception {
        assertEquals(200, send("PATCH", "/api/rules/1", """
            {"category":"GROCERIES","comment":"now with the warehouse",
             "when":{"match":"woolworths|coles|costco"},"rulesRevision":"%s"}""".formatted(revision())).statusCode());
        assertEquals("GROCERIES", categoryOf("g1"));

        assertEquals(200, send("DELETE", "/api/rules/1?rulesRevision=" + revision(), null).statusCode());
        assertEquals("UNCATEGORIZED", categoryOf("g1"), "the rule is gone, so the row is uncategorised again");
        assertFalse(Files.readString(dir.resolve("categories.yaml")).contains("# The two big chains."),
            "and so is the comment that explained it");
    }

    // ---------------------------------------------------------------- the worklist

    /**
     * The list the Categorize tab works down: uncategorised rows grouped by merchant stem, with
     * the evidence that decides rule-versus-pin. It must agree with what the table reports, or
     * the two views of the same journal would disagree about what still needs a rule.
     */
    @Test
    void theWorklistGroupsUncategorisedRowsByMerchant() throws Exception {
        JsonNode w = json(get("/api/worklist"));
        assertEquals(3, w.get("transactions").asInt());
        assertEquals(2, w.get("uncategorized").asInt(), "the two PICCOLO rows");
        assertEquals(1, w.get("merchants").asInt(), "which are one merchant, not two");

        JsonNode top = w.get("entries").get(0);
        // The stem, not the raw description: one of these rows carries ING's " - Visa Purchase"
        // tail and the other does not, and they are still one merchant.
        assertEquals("PICCOLO ME", top.get("stem").asText());
        assertEquals(2, top.get("count").asInt());
        assertEquals(-1160, top.get("total").asLong());
        assertEquals(2, top.get("sampleIds").size(), "enough to pin from without another fetch");
        assertTrue(w.get("categories").toString().contains("GROCERIES"));
    }

    /** Writing the rule the worklist suggested removes that merchant from it. */
    @Test
    void theWorklistShrinksWhenARuleIsWritten() throws Exception {
        assertEquals(2, json(get("/api/worklist")).get("uncategorized").asInt());
        assertEquals(200, send("POST", "/api/rules", """
            {"category":"FOOD","when":{"match":"piccolo"},"rulesRevision":"%s"}""".formatted(revision())).statusCode());
        JsonNode after = json(get("/api/worklist"));
        assertEquals(0, after.get("uncategorized").asInt());
        assertEquals(0, after.get("merchants").asInt());
    }

    // ---------------------------------------------------------------- the rules API

    /**
     * A client must be able to read a rule, change one field and send it back. Without the
     * {@code when} tree in the response that is impossible — PATCH needs the whole condition,
     * so the only way to edit a rule would be to retype it and hope it matched.
     */
    @Test
    void aRuleCanBeReadEditedAndSentBack() throws Exception {
        JsonNode rule = json(get("/api/rules/1"));
        assertEquals(1, rule.get("index").asInt());
        assertEquals("GROCERIES", rule.get("category").asText());
        assertEquals("supermarkets", rule.get("comment").asText());
        assertEquals("woolworths|coles", rule.get("when").get("match").asText());

        // Round trip: take what was read, widen the pattern, send it back.
        String edited = """
            {"category":"%s","comment":"%s","when":{"match":"%s|costco"},"rulesRevision":"%s"}"""
            .formatted(rule.get("category").asText(), rule.get("comment").asText(),
                rule.get("when").get("match").asText(), revision());
        assertEquals(200, send("PATCH", "/api/rules/1", edited).statusCode());
        assertEquals("woolworths|coles|costco",
            json(get("/api/rules/1")).get("when").get("match").asText());
    }

    @Test
    void anIndexThatDoesNotExistIs404() throws Exception {
        assertEquals(404, get("/api/rules/99").statusCode());
        assertEquals(404, get("/api/pins/1").statusCode());
    }

    /** The listing pairs each rule with what it is actually deciding (§9). */
    @Test
    void theListingCarriesHealthAlongsideTheRules() throws Exception {
        JsonNode listing = json(get("/api/rules"));
        JsonNode groceries = listing.get("rules").get(0);
        assertEquals(1, groceries.get("hits").asInt(), "the one WOOLWORTHS row");
        assertEquals(1, groceries.get("merchants").asInt());
        assertEquals(-8500, groceries.get("total").asLong());
        assertFalse(groceries.get("dead").asBoolean());
        assertEquals(1, listing.get("categorized").asInt());
        assertEquals(2, listing.get("uncategorized").asInt());
    }

    /** A rule added below a broader one decides nothing, and the listing says so. */
    @Test
    void theListingNamesARuleThatCanNeverFire() throws Exception {
        assertEquals(200, send("POST", "/api/rules", """
            {"category":"FOOD","comment":"never fires","when":{"match":"piccolo"},
             "at":1,"rulesRevision":"%s"}""".formatted(revision())).statusCode());
        // Below the first, explicitly. Left to itself the service would put this one in FRONT,
        // because it collides and would otherwise never fire — which is the placement working.
        // Shadowing is what you get when a position is chosen badly, so the test has to choose one.
        assertEquals(200, send("POST", "/api/rules", """
            {"category":"SALARY","comment":"shadowed by the rule above","when":{"match":"piccolo me"},
             "at":9,"rulesRevision":"%s"}""".formatted(revision())).statusCode());

        JsonNode rules = json(get("/api/rules")).get("rules");
        JsonNode last = rules.get(rules.size() - 1);
        assertEquals(0, last.get("hits").asInt());
        assertTrue(last.get("fullyShadowed").asBoolean(), "it matches rows that an earlier rule wins");
        assertEquals(1, last.get("shadowedBy").asInt());
        assertFalse(last.get("dead").asBoolean(), "dead and shadowed need different fixes");
    }

    /**
     * Pins show up in the listing with what they are doing, and two pins for one merchant are
     * not yet a promotion — three is the threshold (RuleHealthTest covers crossing it). Here the
     * point is that pinning is visible and counted at all.
     */
    @Test
    void pinsAreListedWithTheirHits() throws Exception {
        assertEquals(200, send("POST", "/api/pins", """
            {"category":"FOOD","comment":"both cafe runs","externalIds":["u1","u2"],
             "rulesRevision":"%s"}""".formatted(revision())).statusCode());

        JsonNode listing = json(get("/api/rules"));
        JsonNode pin = listing.get("pins").get(0);
        assertEquals("FOOD", pin.get("category").asText());
        assertEquals(2, pin.get("hits").asInt());
        assertEquals(0, pin.get("missingIds").asInt());
        assertFalse(pin.get("stale").asBoolean());
        assertTrue(listing.get("promotions").isEmpty(), "two is a coincidence, three is a pattern");
        assertEquals(2, pin.get("when").get("externalId").size(), "the ids come back for editing");
    }

    // ---------------------------------------------------------------- guards and reload

    @Test
    void writesRequireTheAdminHeaderEvenFromALocalCaller() throws Exception {
        HttpRequest bare = HttpRequest.newBuilder(URI.create(base() + "/api/rules"))
            .POST(HttpRequest.BodyPublishers.ofString("{}"))
            .header("Content-Type", "application/json").build();
        assertEquals(403, http.send(bare, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    /**
     * A file edited by hand must behave exactly like one written through the API — same files,
     * same reload, same revision. Otherwise the two ways of changing rules drift apart and the
     * service's idea of the rule set stops matching the one in git.
     */
    @Test
    void aHandEditIsPickedUpWithoutARestart() throws Exception {
        assertEquals("UNCATEGORIZED", categoryOf("u1"));
        Files.writeString(dir.resolve("categories.yaml"), RULES + """
              - category: FOOD
                comment: "added in an editor"
                when:
                  match: "piccolo"
            """);
        waitForCategory("u1", "FOOD");
        assertEquals("FOOD", categoryOf("u1"));
    }

    /** A broken hand edit must not take the service down: the last good rule set keeps serving. */
    @Test
    void aBrokenHandEditKeepsThePreviousRuleSet() throws Exception {
        assertEquals("GROCERIES", categoryOf("g1"));
        Files.writeString(dir.resolve("categories.yaml"), "categories: [GROCERIES]\nrules:\n  - category: NOPE\n    when: {match: \"x\"}\n");
        Thread.sleep(600);
        assertEquals("GROCERIES", categoryOf("g1"), "the previous rules are still serving");
    }

    // ---------------------------------------------------------------- helpers

    private String categoryOf(String externalId) throws Exception {
        JsonNode body = json(get("/api/snapshot?size=50"));
        for (JsonNode row : body.get("rows")) {
            if (row.get("externalId").asText().equals(externalId)) {
                return body.get("categories").get(row.get("n").asText()).get("category").asText();
            }
        }
        throw new AssertionError("no row " + externalId);
    }

    private void waitForCategory(String externalId, String expected) throws Exception {
        for (int i = 0; i < 40 && !expected.equals(categoryOf(externalId)); i++) {
            Thread.sleep(50);
        }
    }

    private static CanonicalEvent line(long n, String id, long amount, String raw) {
        return new CanonicalEvent(n, id, "ing-savings", null, "AUD", LocalDate.of(2026, 7, 1), amount, 0,
            raw, raw, amount < 0 ? TypeHint.WITHDRAWAL : TypeHint.DEPOSIT,
            null, null, null, EventState.EXTERNAL, Confidence.HIGH, List.of(), Provenance.BANK, "ing-csv",
            null, null, null, null, null, null, Instant.EPOCH);
    }
}
