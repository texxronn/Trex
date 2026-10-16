package trex.v2.hub;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trex.v2.hub.auth.Auth;
import trex.v2.hub.auth.PasswordHash;
import trex.v2.hub.auth.Session;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sign-in, the gate and the endpoints (V2-SPEC.md §9.5, V2-AUTH-PLAN.md §7 tests 1, 2, 3, 6, 7, 8).
 * Runs a real hub on a temp index against a real credential file; auth off must be byte-for-byte
 * today's hub, and auth on must gate every read but the three open routes and {@code /mcp}.
 */
class HubAuthTest {

    private static final String RON = "hunter2";
    private static final String MEL = "letmein";
    private static final String SECRET = "hZ8vQ0m3nJ2wX5tR7kL9pB4cD6fG1sA0eY3uI6oP8qZ=";

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Test
    void offByDefaultIsTodaysHub(@TempDir Path dir) throws Exception {
        Path configDir = config(dir, true);
        Path journal = journal(dir);
        try (HubService hub = HubService.start(new HubConfig(journal, dir.resolve("trex.sqlite"),
                configDir, "127.0.0.1", 0, 50))) {
            URI base = base(hub);
            assertEquals(200, get(base, "/api/status", null).statusCode());
            // No sequencer: the decision path is a 503 (read-only), never the gate's 401.
            assertEquals(503, post(base, "/api/decisions", "{}", null).statusCode());
            assertEquals(200, get(base, "/", null).statusCode());
        }
    }

    @Test
    void loginSetsACookieAndEveryFailureIsUniform(@TempDir Path dir) throws Exception {
        Path configDir = config(dir, true);
        Path auth = writeAuth(dir, SECRET);
        try (HubService hub = start(dir, configDir, journal(dir), auth)) {
            URI base = base(hub);

            HttpResponse<String> wrong = post(base, "/api/login",
                "{\"user\":\"ron\",\"password\":\"nope\"}", null);
            assertEquals(401, wrong.statusCode());
            assertTrue(wrong.headers().allValues("Set-Cookie").isEmpty(), "a failure sets no cookie");

            assertEquals(401, post(base, "/api/login",
                "{\"user\":\"nobody\",\"password\":\"" + RON + "\"}", null).statusCode());

            // Anonymous reads through the API are a uniform 401.
            HttpResponse<String> anonymous = get(base, "/api/status", null);
            assertEquals(401, anonymous.statusCode());
            assertTrue(anonymous.body().contains("not signed in"), anonymous.body());

            HttpResponse<String> ok = post(base, "/api/login",
                "{\"user\":\"ron\",\"password\":\"" + RON + "\"}", null);
            assertEquals(200, ok.statusCode(), ok.body());
            assertTrue(ok.body().contains("\"user\":\"ron\""), ok.body());
            assertTrue(ok.body().contains("\"name\":\"Ron\""), ok.body());

            String setCookie = ok.headers().firstValue("Set-Cookie").orElseThrow();
            assertTrue(setCookie.startsWith("trex_session="), setCookie);
            assertTrue(setCookie.contains("HttpOnly"), setCookie);
            assertTrue(setCookie.contains("SameSite=Lax"), setCookie);
            assertTrue(setCookie.contains("Max-Age=2592000"), setCookie);

            String cookie = cookieValue(setCookie);
            HttpResponse<String> who = get(base, "/api/whoami", cookie);
            assertEquals(200, who.statusCode(), who.body());
            assertTrue(who.body().contains("\"user\":\"ron\""), who.body());
            assertEquals(200, get(base, "/api/status", cookie).statusCode());
        }
    }

    @Test
    void tamperedExpiredAndForeignCookiesAreAnonymous(@TempDir Path dir) throws Exception {
        Path configDir = config(dir, true);
        Path auth = writeAuth(dir, SECRET);
        try (HubService hub = start(dir, configDir, journal(dir), auth)) {
            URI base = base(hub);
            long now = java.time.Instant.now().getEpochSecond();
            Session session = new Session(SECRET);

            String valid = session.sign("ron", now + 3600);
            String tampered = valid.substring(0, valid.length() - 1)
                + (valid.endsWith("A") ? "B" : "A");
            String expired = session.sign("ron", now - 1);
            String foreign = new Session("a-completely-different-secret").sign("ron", now + 3600);

            assertEquals(401, get(base, "/api/whoami", tampered).statusCode());
            assertEquals(401, get(base, "/api/whoami", expired).statusCode());
            assertEquals(401, get(base, "/api/whoami", foreign).statusCode());
            assertEquals(401, get(base, "/api/whoami", "garbage").statusCode());

            HttpResponse<String> root = get(base, "/", null);
            assertEquals(302, root.statusCode());
            assertTrue(root.headers().firstValue("Location").orElse("").startsWith("/login?next="),
                root.headers().firstValue("Location").orElse(""));
        }
    }

    @Test
    void inactiveUserCannotSignIn(@TempDir Path dir) throws Exception {
        Path configDir = config(dir, false);
        Path auth = writeAuth(dir, SECRET);
        try (HubService hub = start(dir, configDir, journal(dir), auth)) {
            URI base = base(hub);
            // mel has a hash but is inactive: the refusal is the same 401 as a wrong password.
            assertEquals(401, post(base, "/api/login",
                "{\"user\":\"mel\",\"password\":\"" + MEL + "\"}", null).statusCode());
        }
    }

    @Test
    void mcpStillAnswersWithoutACookie(@TempDir Path dir) throws Exception {
        Path configDir = config(dir, true);
        Path auth = writeAuth(dir, SECRET);
        try (HubService hub = start(dir, configDir, journal(dir), auth)) {
            URI base = base(hub);
            // A-4: /mcp inherits the listener and is deliberately not gated; this pins the decision.
            HttpResponse<String> ping = post(base, "/mcp", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}", null);
            assertEquals(200, ping.statusCode(), ping.body());
            assertTrue(ping.body().contains("\"result\""), ping.body());
            // The open seam is exactly one path: a sub-path is a 404, not another anonymous door.
            assertEquals(404, post(base, "/mcp/tools", "{}", null).statusCode());
            assertEquals(404, post(base, "/mcp/", "{}", null).statusCode());
        }
    }

    @Test
    void anOverLimitBodyIsRefusedWith413(@TempDir Path dir) throws Exception {
        Path configDir = config(dir, true);
        Path auth = writeAuth(dir, SECRET);
        try (HubService hub = start(dir, configDir, journal(dir), auth)) {
            URI base = base(hub);
            // One byte past the 10 MiB cap, anonymous on the open /api/login route: the hub must
            // refuse before it reads and allocates a body it already knows is too big.
            String overLimit = "x".repeat(10 * 1024 * 1024 + 1);
            HttpResponse<String> refused = post(base, "/api/login", overLimit, null);
            assertEquals(413, refused.statusCode(), refused.body());
            assertTrue(refused.body().contains("too large"), refused.body());
        }
    }

    @Test
    void restartKeepsACookieAndFlippingTheSecretInvalidatesIt(@TempDir Path dir) throws Exception {
        Path configDir = config(dir, true);
        Path journal = journal(dir);
        Path auth = writeAuth(dir, SECRET);

        String cookie;
        try (HubService first = start(dir, configDir, journal, auth)) {
            URI base = base(first);
            HttpResponse<String> ok = post(base, "/api/login",
                "{\"user\":\"ron\",\"password\":\"" + RON + "\"}", null);
            assertEquals(200, ok.statusCode(), ok.body());
            cookie = cookieValue(ok.headers().firstValue("Set-Cookie").orElseThrow());
        }

        // Same file, same secret: the stateless cookie survives the restart.
        try (HubService second = start(dir, configDir, journal, auth)) {
            assertEquals(200, get(base(second), "/api/whoami", cookie).statusCode());
        }

        // Flipping sessionSecret signs everyone out at once: the whole revocation model (no table).
        writeAuth(dir, "DWPJzTCbPYE4nQs9/fb4fAvDIuG8Oynb00NPyRGm2A0=");
        try (HubService third = start(dir, configDir, journal, auth)) {
            assertEquals(401, get(base(third), "/api/whoami", cookie).statusCode());
        }
    }

    // ---- fixtures ---------------------------------------------------------------------------

    private static HubService start(Path dir, Path configDir, Path journal, Path auth) {
        // The full twelve-component shape: sign-in on, named by the credential file (A-7).
        return HubService.start(new HubConfig(journal, dir.resolve("trex.sqlite"), configDir,
            "127.0.0.1", 0, 50, null, null, null, null, HubConfig.DEFAULT_ASOF_REFRESH_MS, auth));
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

    /** Write the credential file with the given secret; ron active, mel present (config decides). */
    private static Path writeAuth(Path dir, String secret) throws Exception {
        Path auth = dir.resolve("auth.yaml");
        Files.writeString(auth, """
            version: 1
            sessionSecret: "%s"
            users:
              ron:
                pbkdf2: "%s"
              mel:
                pbkdf2: "%s"
            """.formatted(secret, PasswordHash.hash(RON), PasswordHash.hash(MEL)));
        return auth;
    }

    private static Path config(Path dir, boolean melActive) throws Exception {
        Path configDir = dir.resolve("config");
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
              - id: "mel"
                name: "Mel"
                active: %s
            """.formatted(melActive));
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
        return configDir;
    }

    // ---- HTTP helpers -----------------------------------------------------------------------

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

    /** The value of the {@code trex_session} cookie in a {@code Set-Cookie} header. */
    private static String cookieValue(String setCookie) {
        String prefix = "trex_session=";
        int start = setCookie.indexOf(prefix) + prefix.length();
        int end = setCookie.indexOf(';', start);
        return end < 0 ? setCookie.substring(start) : setCookie.substring(start, end);
    }
}
