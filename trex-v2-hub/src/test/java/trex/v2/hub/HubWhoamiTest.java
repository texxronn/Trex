package trex.v2.hub;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shell's first call, in both modes (V2-SPEC.md §9.1, §9.5). With sign-in off {@code /api/whoami}
 * names the default acting user and the UI's declared user is what a write is signed with; with
 * sign-in on it names the session. The dev-path test runs a real sequencer and reads the signature
 * back from the log, not the response, because the log is the record that matters.
 */
class HubWhoamiTest {

    private static final String RON = "hunter2";
    private static final String MEL = "letmein";
    private static final String SECRET = "hZ8vQ0m3nJ2wX5tR7kL9pB4cD6fG1sA0eY3uI6oP8qZ=";
    private static final Instant AT = Instant.parse("2026-09-30T00:00:00Z");

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    /** Sign-in off: the shell is answered the first declared active user and told sign-in is off. */
    @Test
    void withSignInOffWhoamiNamesTheDefaultUser(@TempDir Path dir) throws Exception {
        Path configDir = config(dir, true);
        try (HubService hub = readOnly(dir, configDir, journal(dir))) {
            JsonNode who = whoami(base(hub), null);
            assertEquals("ron", who.get("user").asText(), who.toPrettyString());
            assertEquals("Ron", who.get("name").asText(), who.toPrettyString());
            assertFalse(who.get("signedIn").asBoolean(), who.toPrettyString());
        }
    }

    /** Sign-in off, end to end: the UI declares the user whoami gave it, and the write lands signed. */
    @Test
    void withSignInOffTheDeclaredUserIsAppended(@TempDir Path dir) throws Exception {
        Path configDir = config(dir, true);
        Path journal = journalWithFact(dir);
        try (SequencerService sequencer = sequencer(dir, configDir);
             HubService hub = writer(dir, configDir, journal, sequencer)) {
            URI base = base(hub);
            await(() -> hub.status().counts().getOrDefault("txn_current", 0L) == 1L);

            // The way the UI submits now: it declares the user /api/whoami handed it (§9.5). With no
            // session the hub has nothing else, so this is the identity and the write must land.
            HttpResponse<String> ok = post(base, "/api/decisions",
                decisionsJson(draft("MARK_EXTERNAL", "user", "ron", "a", null, null, List.of())), null);
            assertEquals(200, ok.statusCode(), ok.body());

            Decision appended = lastDecision(journal);
            assertNotNull(appended, "a decision was appended");
            assertEquals("MARK_EXTERNAL", appended.action().name());
            assertEquals("ron", appended.user(), "the declared user signs it, read from the log");
        }
    }

    /** The default skips an inactive user declared first, or a config leading with one would break. */
    @Test
    void theDefaultUserSkipsAnInactiveFirstUser(@TempDir Path dir) throws Exception {
        Path configDir = configWithInactiveFirst(dir);
        try (HubService hub = readOnly(dir, configDir, journal(dir))) {
            JsonNode who = whoami(base(hub), null);
            assertEquals("ron", who.get("user").asText(), who.toPrettyString());
            assertFalse(who.get("signedIn").asBoolean(), who.toPrettyString());
        }
    }

    /** Sign-in on: whoami is the session, is gated for an anonymous caller, and says signedIn. */
    @Test
    void withSignInOnWhoamiReportsTheSession(@TempDir Path dir) throws Exception {
        Path configDir = config(dir, true);
        Path auth = writeAuth(dir);
        try (HubService hub = readOnly(dir, configDir, journal(dir), auth)) {
            URI base = base(hub);
            // Registered in both modes, but with --auth the gate refuses an anonymous caller (§9.5).
            assertEquals(401, get(base, "/api/whoami", null).statusCode());

            String cookie = signIn(base, "ron", RON);
            JsonNode who = whoami(base, cookie);
            assertEquals("ron", who.get("user").asText(), who.toPrettyString());
            assertEquals("Ron", who.get("name").asText(), who.toPrettyString());
            assertTrue(who.get("signedIn").asBoolean(), who.toPrettyString());
        }
    }

    // ---- fixtures ---------------------------------------------------------------------------

    private static HubService readOnly(Path dir, Path configDir, Path journal) {
        return HubService.start(new HubConfig(journal, dir.resolve("trex.sqlite"), configDir,
            "127.0.0.1", 0, 50));
    }

    private static HubService readOnly(Path dir, Path configDir, Path journal, Path auth) {
        return HubService.start(new HubConfig(journal, dir.resolve("trex.sqlite"), configDir,
            "127.0.0.1", 0, 50, null, null, null, null, HubConfig.DEFAULT_ASOF_REFRESH_MS, auth));
    }

    private static HubService writer(Path dir, Path configDir, Path journal, SequencerService sequencer) {
        return HubService.start(new HubConfig(journal, dir.resolve("trex.sqlite"), configDir,
            "127.0.0.1", 0, 50, "http://127.0.0.1:" + sequencer.port()));
    }

    private static SequencerService sequencer(Path dir, Path configDir) {
        return SequencerService.start(dir.resolve("trex.jsonl"), configDir, "127.0.0.1", 0, Clock.systemUTC());
    }

    private static URI base(HubService hub) {
        return URI.create("http://127.0.0.1:" + hub.port());
    }

    private static Path journal(Path dir) throws Exception {
        Path journal = dir.resolve("trex.jsonl");
        if (!Files.exists(journal)) {
            Files.write(journal, new byte[0]);
        }
        return journal;
    }

    /** A journal with one posted fact ("a") the decision prechecks can target. */
    private static Path journalWithFact(Path dir) throws Exception {
        Path journal = dir.resolve("trex.jsonl");
        try (JsonlJournal j = new JsonlJournal(journal)) {
            j.appendBatch(List.of(fact(1, "a", "ing-savings", -1000, "COLES 1234", AT)));
        }
        return journal;
    }

    /** ron active first; mel second, active unless told otherwise. */
    private static Path config(Path dir, boolean melActive) throws Exception {
        return config(dir, """
            users:
              - id: "ron"
                name: "Ron"
                active: true
              - id: "mel"
                name: "Mel"
                active: %s
            """.formatted(melActive));
    }

    /** mel (inactive) is declared before ron (active), so the default must skip to ron. */
    private static Path configWithInactiveFirst(Path dir) throws Exception {
        return config(dir, """
            users:
              - id: "mel"
                name: "Mel"
                active: false
              - id: "ron"
                name: "Ron"
                active: true
            """);
    }

    private static Path config(Path dir, String usersYaml) throws Exception {
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
        Files.writeString(configDir.resolve("users.yaml"), usersYaml);
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

    /** The 200 whoami body, parsed; a non-200 fails the test with the body for context. */
    private static JsonNode whoami(URI base, String cookie) throws Exception {
        HttpResponse<String> response = get(base, "/api/whoami", cookie);
        assertEquals(200, response.statusCode(), response.body());
        return Json.mapper().readTree(response.body());
    }

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
