package trex.v2.hub;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trex.v2.core.Actor;
import trex.v2.core.Decision;
import trex.v2.core.Fact;
import trex.v2.core.LogLine;
import trex.v2.core.Observation;
import trex.v2.core.Provenance;
import trex.v2.hub.auth.PasswordHash;
import trex.v2.log.Json;
import trex.v2.log.JsonlJournal;
import trex.v2.log.LogCodec;
import trex.v2.sequencer.SequencerService;
import trex.v2.sequencer.api.DecisionDraft;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Attribution: with sign-in on the session stamps the write and a mismatch is refused; with sign-in
 * off the body's user still rules (V2-SPEC.md §9.5, V2-AUTH-PLAN.md §4.6 and §7 tests 4, 5, 9, 10).
 * Every assertion about the signature reads it back from the log, not the response, because the log
 * is the record that matters. A real sequencer and hub run end to end, as in {@link HubCashTest}.
 */
class HubAttributionTest {

    private static final String RON = "hunter2";
    private static final String MEL = "letmein";
    private static final String SECRET = "hZ8vQ0m3nJ2wX5tR7kL9pB4cD6fG1sA0eY3uI6oP8qZ=";
    private static final Instant AT = Instant.parse("2026-09-30T00:00:00Z");
    private static final Instant PRE = Instant.parse("2026-09-01T00:00:00Z");

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Test
    void aDraftNamingAnotherUserIsRefusedAndNothingIsAppended(@TempDir Path dir) throws Exception {
        Path configDir = config(dir);
        Path journal = journalWithFact(dir);
        Path auth = writeAuth(dir);
        try (SequencerService sequencer = sequencer(dir, configDir);
             HubService hub = hubWithWriter(dir, configDir, journal, auth, sequencer)) {
            URI base = base(hub);
            String cookie = signIn(base, "ron", RON);
            await(() -> hub.status().counts().getOrDefault("txn_current", 0L) == 1L);

            long before = Files.size(journal);
            HttpResponse<String> refused = post(base, "/api/decisions",
                decisionsJson(draft("MARK_EXTERNAL", "user", "mel", "a", null, null, List.of())), cookie);
            assertEquals(403, refused.statusCode(), refused.body());
            assertTrue(refused.body().contains("mel"), refused.body());
            // The honest answer to a mismatch is a loud failure, never a silent rewrite (§9.5):
            // nothing reached the writer, so the head has not moved.
            assertEquals(before, Files.size(journal), "a 403 appends nothing");
        }
    }

    @Test
    void anOrdinaryDecisionIsSignedByTheSessionUser(@TempDir Path dir) throws Exception {
        Path configDir = config(dir);
        Path journal = journalWithFact(dir);
        Path auth = writeAuth(dir);
        try (SequencerService sequencer = sequencer(dir, configDir);
             HubService hub = hubWithWriter(dir, configDir, journal, auth, sequencer)) {
            URI base = base(hub);
            String cookie = signIn(base, "ron", RON);
            await(() -> hub.status().counts().getOrDefault("txn_current", 0L) == 1L);

            // The draft names no user: the hub stamps the session's (§9.5).
            HttpResponse<String> ok = post(base, "/api/decisions",
                decisionsJson(draft("MARK_EXTERNAL", "user", null, "a", null, null, List.of())), cookie);
            assertEquals(200, ok.statusCode(), ok.body());

            Decision appended = lastDecision(journal);
            assertNotNull(appended, "a decision was appended");
            assertEquals("MARK_EXTERNAL", appended.action().name());
            assertEquals(Actor.USER, appended.actor(), "the person, not a machine");
            assertEquals("ron", appended.user(), "the session signed it, from the log");
        }
    }

    @Test
    void aDraftClaimingAMachineActorIsForcedToThePerson(@TempDir Path dir) throws Exception {
        Path configDir = config(dir);
        Path journal = journalWithFact(dir);
        Path auth = writeAuth(dir);
        try (SequencerService sequencer = sequencer(dir, configDir);
             HubService hub = hubWithWriter(dir, configDir, journal, auth, sequencer)) {
            URI base = base(hub);
            String cookie = signIn(base, "ron", RON);
            await(() -> hub.status().counts().getOrDefault("txn_current", 0L) == 1L);

            // A body that claims `actor: "system"` and names no user would otherwise be logged as a
            // machine with no person (the sequencer nulls `user` for a non-user actor). Stamping the
            // session forces both, so the person survives (V2-SPEC.md §9.5).
            HttpResponse<String> ok = post(base, "/api/decisions",
                decisionsJson(draft("MARK_EXTERNAL", "system", null, "a", null, null, List.of())), cookie);
            assertEquals(200, ok.statusCode(), ok.body());

            // The actor and the user are read back from the journal, not the response — the log is
            // the record that matters.
            Decision appended = lastDecision(journal);
            assertNotNull(appended, "a decision was appended");
            assertEquals("MARK_EXTERNAL", appended.action().name());
            assertEquals(Actor.USER, appended.actor(), "the actor is forced to the person");
            assertEquals("ron", appended.user(), "the session signed it, from the log");
        }
    }

    @Test
    void aCashBodyNamingAnotherUserIsRefusedAndNothingIsAppended(@TempDir Path dir) throws Exception {
        Path configDir = config(dir);
        Path journal = journalWithFact(dir);
        Path auth = writeAuth(dir);
        try (SequencerService sequencer = sequencer(dir, configDir);
             HubService hub = hubWithWriter(dir, configDir, journal, auth, sequencer)) {
            URI base = base(hub);
            String cookie = signIn(base, "ron", RON);

            long before = Files.size(journal);
            // The body claims mel; the category would otherwise make the PIN observable in the log.
            HttpResponse<String> refused = post(base, "/api/cash", """
                {"account":"cash-ron","date":"2026-09-05","amount":-1234,"description":"MANUAL LUNCH",
                 "category":"GROCERIES","user":"mel"}""", cookie);
            assertEquals(403, refused.statusCode(), refused.body());
            assertTrue(refused.body().contains("mel"), refused.body());
            assertEquals(before, Files.size(journal), "a 403 appends nothing");
        }
    }

    @Test
    void anAckBodyNamingAnotherUserIsRefusedAndNothingIsAppended(@TempDir Path dir) throws Exception {
        Path configDir = config(dir);
        Path journal = journalWithFact(dir);
        Path auth = writeAuth(dir);
        try (SequencerService sequencer = sequencer(dir, configDir);
             HubService hub = hubWithWriter(dir, configDir, journal, auth, sequencer)) {
            URI base = base(hub);
            String cookie = signIn(base, "ron", RON);
            await(() -> hub.status().counts().getOrDefault("txn_current", 0L) == 1L);

            long before = Files.size(journal);
            HttpResponse<String> refused = post(base, "/api/acks",
                "{\"user\":\"mel\",\"externalId\":\"a\",\"action\":\"ACK\"}", cookie);
            assertEquals(403, refused.statusCode(), refused.body());
            assertTrue(refused.body().contains("mel"), refused.body());
            assertEquals(before, Files.size(journal), "a 403 appends nothing");
        }
    }

    @Test
    void ordinaryCashAndAcksAreSignedByTheSessionUser(@TempDir Path dir) throws Exception {
        Path configDir = config(dir);
        Path journal = journalWithFact(dir);
        Path auth = writeAuth(dir);
        try (SequencerService sequencer = sequencer(dir, configDir);
             HubService hub = hubWithWriter(dir, configDir, journal, auth, sequencer)) {
            URI base = base(hub);
            String cookie = signIn(base, "ron", RON);
            await(() -> hub.status().counts().getOrDefault("txn_current", 0L) == 1L);

            // No body user at all: the session signs the PIN.
            HttpResponse<String> cash = post(base, "/api/cash", """
                {"account":"cash-ron","date":"2026-09-05","amount":-1234,"description":"MANUAL LUNCH",
                 "category":"GROCERIES"}""", cookie);
            assertEquals(200, cash.statusCode(), cash.body());
            Decision pin = lastDecision(journal);
            assertNotNull(pin, "the PIN was appended");
            assertEquals("PIN", pin.action().name());
            assertEquals("ron", pin.user(), "the session signs the PIN");

            HttpResponse<String> ack = post(base, "/api/acks",
                "{\"externalId\":\"a\",\"action\":\"ACK\"}", cookie);
            assertEquals(200, ack.statusCode(), ack.body());
            Decision userAck = lastDecision(journal);
            assertNotNull(userAck, "the ACK was appended");
            assertEquals("USER_ACK", userAck.action().name());
            assertEquals("ron", userAck.user(), "the session signs the ACK");
        }
    }

    @Test
    void sinceReadsTheSessionUserNotTheQueryParameter(@TempDir Path dir) throws Exception {
        Path configDir = config(dir);
        Path journal = dir.resolve("trex.jsonl");
        try (JsonlJournal j = new JsonlJournal(journal)) {
            j.appendBatch(List.of(
                fact(1, "a", "ing-savings", -1000, "COLES 1234", PRE),
                new Decision.Note(2, "a", "ron's note", Actor.USER, "ron", PRE.plusSeconds(3600)),
                new Decision.Note(3, "a", "mel's note", Actor.USER, "mel", PRE.plusSeconds(3600))));
        }
        Path auth = writeAuth(dir);
        try (HubService hub = readOnly(dir, configDir, journal, auth)) {
            URI base = base(hub);
            String cookie = signIn(base, "ron", RON);
            await(() -> hub.status().counts().getOrDefault("txn_current", 0L) == 1L);

            // The session is ron, so ron's own note is excluded and mel's is "news" — even though
            // the query asks for mel. Honouring the parameter would show the opposite pair (§9.5).
            JsonNode body = Json.mapper().readTree(get(base, "/api/since?n=1&user=mel", cookie).body());
            JsonNode decisions = body.get("decisions");
            assertEquals(1, decisions.size(), body.toPrettyString());
            assertEquals("mel", decisions.get(0).get("user").asText(), body.toPrettyString());
        }
    }

    @Test
    void withSignInOffTheBodyUserStillRules(@TempDir Path dir) throws Exception {
        Path configDir = config(dir);
        Path journal = dir.resolve("trex.jsonl");
        try (JsonlJournal j = new JsonlJournal(journal)) {
            j.appendBatch(List.of(
                fact(1, "a", "ing-savings", -1000, "COLES 1234", PRE),
                new Decision.Note(2, "a", "ron's note", Actor.USER, "ron", PRE.plusSeconds(3600)),
                new Decision.Note(3, "a", "mel's note", Actor.USER, "mel", PRE.plusSeconds(3600))));
        }
        try (SequencerService sequencer = sequencer(dir, configDir);
             HubService hub = HubService.start(new HubConfig(journal, dir.resolve("trex.sqlite"), configDir,
                 "127.0.0.1", 0, 50, "http://127.0.0.1:" + sequencer.port()))) {
            URI base = base(hub);
            await(() -> hub.status().counts().getOrDefault("txn_current", 0L) == 1L);

            // A-7: with sign-in off the request's own user is still the identity, everywhere.
            JsonNode since = Json.mapper().readTree(get(base, "/api/since?n=1&user=mel", null).body());
            assertEquals(1, since.get("decisions").size(), since.toPrettyString());
            assertEquals("ron", since.get("decisions").get(0).get("user").asText(), since.toPrettyString());

            assertEquals(200, post(base, "/api/decisions",
                decisionsJson(draft("MARK_EXTERNAL", "user", "mel", "a", null, null, List.of())), null)
                .statusCode());
            assertEquals("mel", lastDecision(journal).user(), "the body's user still rules");

            assertEquals(200, post(base, "/api/cash", """
                {"account":"cash-ron","date":"2026-09-05","amount":-500,"description":"x",
                 "category":"GROCERIES","user":"mel"}""", null).statusCode());
            assertEquals("mel", lastDecision(journal).user(), "the body signs the PIN");

            assertEquals(200, post(base, "/api/acks",
                "{\"user\":\"mel\",\"externalId\":\"a\",\"action\":\"ACK\"}", null).statusCode());
            assertEquals("mel", lastDecision(journal).user(), "the body signs the ACK");
        }
    }

    // ---- fixtures ---------------------------------------------------------------------------

    private static HubService hubWithWriter(Path dir, Path configDir, Path journal, Path auth,
                                            SequencerService sequencer) {
        return HubService.start(new HubConfig(journal, dir.resolve("trex.sqlite"), configDir,
            "127.0.0.1", 0, 50, "http://127.0.0.1:" + sequencer.port(), null, null, null,
            HubConfig.DEFAULT_ASOF_REFRESH_MS, auth));
    }

    private static HubService readOnly(Path dir, Path configDir, Path journal, Path auth) {
        return HubService.start(new HubConfig(journal, dir.resolve("trex.sqlite"), configDir,
            "127.0.0.1", 0, 50, null, null, null, null, HubConfig.DEFAULT_ASOF_REFRESH_MS, auth));
    }

    private static SequencerService sequencer(Path dir, Path configDir) {
        return SequencerService.start(dir.resolve("trex.jsonl"), configDir, "127.0.0.1", 0, Clock.systemUTC());
    }

    private static URI base(HubService hub) {
        return URI.create("http://127.0.0.1:" + hub.port());
    }

    /** A journal with one posted fact ("a") the decision prechecks can target. */
    private static Path journalWithFact(Path dir) throws Exception {
        Path journal = dir.resolve("trex.jsonl");
        try (JsonlJournal j = new JsonlJournal(journal)) {
            j.appendBatch(List.of(fact(1, "a", "ing-savings", -1000, "COLES 1234", AT)));
        }
        return journal;
    }

    private static Path writeAuth(Path dir) throws Exception {
        Path auth = dir.resolve("auth.yaml");
        Files.writeString(auth, """
            version: 1
            sessionSecret: "%s"
            users:
              ron:
                pbkdf2: "%s"
              mel:
                pbkdf2: "%s"
            """.formatted(SECRET, PasswordHash.hash(RON), PasswordHash.hash(MEL)));
        return auth;
    }

    private static Path config(Path dir) throws Exception {
        Path configDir = dir.resolve("config");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("accounts.yaml"), """
            accounts:
              - ref: "ing-savings"
                currency: "AUD"
                balanceSource: statement
              - ref: "cash-ron"
                currency: "AUD"
                balanceSource: declared
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
        Files.writeString(configDir.resolve("sources.yaml"), """
            sources: ["HUB_0001"]
            """);
        return configDir;
    }

    // ---- the log: the signature is read back from it, never from the response ------------------

    private static Decision lastDecision(Path journal) throws Exception {
        List<String> lines = Files.readAllLines(journal, StandardCharsets.UTF_8);
        for (int i = lines.size() - 1; i >= 0; i--) {
            if (lines.get(i).isBlank()) {
                continue;
            }
            LogLine line = LogCodec.parse(lines.get(i).getBytes(StandardCharsets.UTF_8));
            if (line instanceof Decision decision) {
                return decision;
            }
        }
        return null;
    }

    private static Fact fact(long n, String id, String account, long amount, String raw, Instant at) {
        return new Fact(n, id, account, LocalDate.of(2026, 9, 1), amount, 0, raw, null, 0,
            Observation.POSTED, "ing-csv", Provenance.BANK, null, "ing-csv/1", at);
    }

    private static String decisionsJson(DecisionDraft... drafts) throws Exception {
        return Json.mapper().writeValueAsString(Map.of("decisions", List.of(drafts)));
    }

    /**
     * One flat decision draft, shaped as the UI sends it. The pre-commitment arity is enough here:
     * none of these tests sets a commitment field.
     */
    private static DecisionDraft draft(String action, String actor, String user, String externalId,
                                       String category, String item, List<String> ids) {
        return new DecisionDraft(action, actor, user, AT, "test", null, null, externalId, null, null,
            item, ids.isEmpty() ? null : ids, category, null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    // ---- HTTP helpers -------------------------------------------------------------------------

    private static String signIn(URI base, String user, String password) throws Exception {
        HttpResponse<String> ok = post(base, "/api/login",
            "{\"user\":\"" + user + "\",\"password\":\"" + password + "\"}", null);
        assertEquals(200, ok.statusCode(), ok.body());
        return cookieValue(ok.headers().firstValue("Set-Cookie").orElseThrow());
    }

    private static HttpResponse<String> get(URI base, String path, String cookie) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(base.resolve(path)).GET();
        if (cookie != null) {
            request.header("Cookie", "trex_session=" + cookie);
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(URI base, String path, String body, String cookie)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(base.resolve(path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
        if (cookie != null) {
            request.header("Cookie", "trex_session=" + cookie);
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String cookieValue(String setCookie) {
        String prefix = "trex_session=";
        int start = setCookie.indexOf(prefix) + prefix.length();
        int end = setCookie.indexOf(';', start);
        return end < 0 ? setCookie.substring(start) : setCookie.substring(start, end);
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
