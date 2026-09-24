package trex.web;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC §5.4: pages, one proxy, one relay. A stub gateway stands in for the real one so the
 * proxy's own behaviour — what it forwards, what it refuses, what it serves when the upstream is
 * gone — is tested without a journal in the way.
 */
class WebServerTest {

    private HttpServer stub;
    private WebServer web;
    private final HttpClient http = HttpClient.newHttpClient();

    /** What the stub was asked for, so the test can assert the proxy forwarded it unchanged. */
    private final Map<String, String> seenQuery = new ConcurrentHashMap<>();
    private final Map<String, String> seenBody = new ConcurrentHashMap<>();
    private final Map<String, String> seenHeaders = new ConcurrentHashMap<>();
    private final Map<String, String> seenOrigin = new ConcurrentHashMap<>();
    private final AtomicInteger snapshotHits = new AtomicInteger();
    private final AtomicBoolean stubDown = new AtomicBoolean(false);
    private final AtomicInteger gatewayRefusals = new AtomicInteger();

    @BeforeEach
    void start() throws IOException {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/api/", ex -> {
            String path = ex.getRequestURI().getPath();
            if (stubDown.get()) {
                ex.close();                       // connection accepted then dropped: an upstream failure
                return;
            }
            // The real gateway's guard (RuleRoutes.guard): admin header required on a write, and
            // an Origin that does not match its Host is refused. Stubbing this faithfully is the
            // point — a stub that accepts anything cannot catch a proxy sending the wrong headers.
            if (!ex.getRequestMethod().equals("GET")) {
                String origin = ex.getRequestHeaders().getFirst("Origin");
                String host = ex.getRequestHeaders().getFirst("Host");
                boolean admin = "1".equals(ex.getRequestHeaders().getFirst("X-Trex-Admin"));
                if (!admin || (origin != null && !origin.equals("http://" + host))) {
                    gatewayRefusals.incrementAndGet();
                    byte[] refused = "{\"error\":\"refused by the gateway guard\"}".getBytes(StandardCharsets.UTF_8);
                    ex.getResponseHeaders().set("Content-Type", "application/json");
                    ex.sendResponseHeaders(403, refused.length);
                    try (OutputStream os = ex.getResponseBody()) {
                        os.write(refused);
                    }
                    ex.close();
                    return;
                }
            }
            seenQuery.put(path, String.valueOf(ex.getRequestURI().getRawQuery()));
            seenHeaders.put(path, String.valueOf(ex.getRequestHeaders().getFirst("X-Trex-Admin")));
            String upstreamOrigin = ex.getRequestHeaders().getFirst("Origin");
            if (upstreamOrigin != null) {
                seenOrigin.put(path, upstreamOrigin);
            }
            seenBody.put(path, new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String body = switch (path) {
                case "/api/snapshot" -> "{\"total\":" + snapshotHits.incrementAndGet() + "}";
                case "/api/decisions" -> "{\"result\":\"Resolved\"}";
                default -> "{\"ok\":true}";
            };
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
            ex.close();
        });
        stub.start();
        URI gateway = URI.create("http://127.0.0.1:" + stub.getAddress().getPort());
        web = new WebServer(new Upstream(gateway), "127.0.0.1", 0).start();
    }

    @AfterEach
    void stop() {
        web.close();
        stub.stop(0);
    }

    private String base() {
        return "http://127.0.0.1:" + web.port();
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base() + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body, boolean admin, String origin) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base() + path))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .header("Content-Type", "application/json");
        if (admin) {
            b.header("X-Trex-Admin", "1");
        }
        if (origin != null) {
            b.header("Origin", origin);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void servesBothPagesWithSecurityHeaders() throws Exception {
        HttpResponse<String> page = get("/");
        assertEquals(200, page.statusCode());
        assertTrue(page.headers().firstValue("Content-Type").orElseThrow().startsWith("text/html"));
        assertTrue(page.headers().firstValue("Content-Security-Policy").orElseThrow().contains("default-src 'self'"));
        assertEquals("nosniff", page.headers().firstValue("X-Content-Type-Options").orElseThrow());
        assertEquals(200, get("/resolve").statusCode());
        assertEquals(200, get("/categorize").statusCode());
        assertEquals(404, get("/secret").statusCode());
    }

    /**
     * Each page must load its OWN script and stylesheet. When the two pages were merged onto one
     * port, resolve/index.html still asked for {@code /app.js} — the browsing page's script — which
     * ran against the wrong DOM, threw, and left the page blank with rows waiting behind it. A
     * status code cannot catch that, so the asset references themselves are asserted.
     */
    @Test
    void eachPageLoadsItsOwnAssets() throws Exception {
        assertEquals(List.of("/app.css", "/app.js"), assetsOf(get("/").body()));
        assertEquals(List.of("/resolve/app.css", "/resolve/app.js"), assetsOf(get("/resolve").body()));
        assertEquals(List.of("/categorize/app.css", "/categorize/app.js"), assetsOf(get("/categorize").body()));
        for (String asset : List.of("/app.css", "/app.js", "/resolve/app.css", "/resolve/app.js",
                "/categorize/app.css", "/categorize/app.js")) {
            assertEquals(200, get(asset).statusCode(), asset);
        }
    }

    /** Every page carries the tabs, or a page becomes a dead end you can only reach by URL. */
    @Test
    void everyPageLinksToTheOthers() throws Exception {
        for (String page : List.of("/", "/resolve", "/categorize")) {
            String html = get(page).body();
            for (String tab : List.of("href=\"/\"", "href=\"/categorize\"", "href=\"/resolve\"")) {
                assertTrue(html.contains(tab) || page.equals(tabTarget(tab)), page + " is missing " + tab);
            }
        }
    }

    private static String tabTarget(String href) {
        return href.substring("href=\"".length(), href.length() - 1);
    }

    private static List<String> assetsOf(String html) {
        return Pattern.compile("(?:src|href)=\"([^\"]+\\.(?:js|css))\"")
            .matcher(html).results().map(m -> m.group(1)).sorted().toList();
    }

    @Test
    void pagesAdaptToNarrowScreensAndTouch() throws Exception {
        assertTrue(get("/").body().contains("name=\"viewport\" content=\"width=device-width"));
        for (String css : List.of("/app.css", "/resolve/app.css", "/categorize/app.css")) {
            String body = get(css).body();
            assertTrue(body.contains("@media (max-width: 720px)"), css + ": phone layout");
            assertTrue(body.contains("@media (max-width: 1024px)"), css + ": tablet layout");
            assertTrue(body.contains("@media (pointer: coarse)"), css + ": touch targets");
        }
    }

    /** Verbatim means verbatim: the path and the query string reach the gateway untouched. */
    @Test
    void proxiesGetsWithTheirQueryUnchanged() throws Exception {
        HttpResponse<String> r = get("/api/snapshot?sort=amount:asc&size=25&q=woolworths");
        assertEquals(200, r.statusCode());
        assertEquals("{\"total\":1}", r.body());
        assertEquals("sort=amount:asc&size=25&q=woolworths", seenQuery.get("/api/snapshot"));
    }

    /** A path trex-web has never heard of still reaches the gateway: no routing table to drift. */
    @Test
    void proxiesPathsItDoesNotKnowAbout() throws Exception {
        assertEquals(200, get("/api/something-added-later").statusCode());
        assertTrue(seenQuery.containsKey("/api/something-added-later"));
    }

    /**
     * A browser always sends Origin; curl does not. Every earlier test here posted without one,
     * so the proxy forwarded the browser's Origin to a gateway on a different port, where it
     * could only mismatch — and every Apply and every decision came back 403 in a real browser
     * while passing in tests and by curl. The Origin belongs to the hop that faces the browser.
     */
    @Test
    void aBrowserShapedPostReachesTheGateway() throws Exception {
        HttpResponse<String> r = post("/api/rules", "{\"category\":\"FOOD\"}", true, "http://127.0.0.1:" + web.port());
        assertEquals(200, r.statusCode(), "a same-origin browser write must go through");
        assertEquals(0, gatewayRefusals.get(), "and must not be refused upstream");
        assertNull(seenOrigin.get("/api/rules"), "the browser's Origin is not the proxy's to forward");
        assertEquals("1", seenHeaders.get("/api/rules"), "but the admin header is, since the gateway requires it");
    }

    @Test
    void forwardsPostsWithTheirBodyAndAdminHeader() throws Exception {
        HttpResponse<String> r = post("/api/decisions", "{\"action\":\"MARK_EXTERNAL\"}", true, null);
        assertEquals(200, r.statusCode());
        assertEquals("{\"result\":\"Resolved\"}", r.body());
        assertEquals("{\"action\":\"MARK_EXTERNAL\"}", seenBody.get("/api/decisions"));
        assertEquals("1", seenHeaders.get("/api/decisions"), "the gateway runs its own guard and needs the header");
    }

    /**
     * The CSRF guard is enforced at the browser-facing edge as well as by the gateway. This copy
     * knows it is talking to a browser, so a cross-site form post is refused before it reaches
     * anything that writes.
     */
    @Test
    void csrfGuardRefusesBeforeForwarding() throws Exception {
        assertEquals(403, post("/api/decisions", "{}", false, null).statusCode());
        assertEquals(403, post("/api/decisions", "{}", true, "http://evil.example").statusCode());
        assertFalse(seenBody.containsKey("/api/decisions"), "nothing may reach the gateway");

        HttpRequest wrongType = HttpRequest.newBuilder(URI.create(base() + "/api/decisions"))
            .POST(HttpRequest.BodyPublishers.ofString("x"))
            .header("Content-Type", "text/plain").header("X-Trex-Admin", "1").build();
        assertEquals(415, http.send(wrongType, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    /**
     * A gateway restart degrades the page to stale-and-labelled rather than emptying it — the
     * numbers stay on screen, marked as not live, instead of the table vanishing mid-read.
     */
    @Test
    void servesTheLastGoodAnswerWhenTheGatewayIsGone() throws Exception {
        assertEquals("{\"total\":1}", get("/api/snapshot?size=1").body());

        stubDown.set(true);
        HttpResponse<String> stale = get("/api/snapshot?size=1");
        assertEquals(200, stale.statusCode());
        assertEquals("{\"total\":1}", stale.body());
        assertEquals("1", stale.headers().firstValue("X-Trex-Stale").orElse(null));

        // A query never answered before has nothing to fall back on, and says so honestly.
        HttpResponse<String> unseen = get("/api/snapshot?size=99");
        assertEquals(502, unseen.statusCode());
        assertTrue(unseen.body().contains("gateway unreachable"));

        // A write is never served from cache: one that did not reach the gateway did not happen.
        assertEquals(502, post("/api/decisions", "{}", true, null).statusCode());

        stubDown.set(false);
        assertEquals("{\"total\":2}", get("/api/snapshot?size=1").body());
        assertTrue(get("/api/snapshot?size=1").headers().firstValue("X-Trex-Stale").isEmpty());
    }
}
