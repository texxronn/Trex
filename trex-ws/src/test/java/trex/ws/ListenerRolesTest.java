package trex.ws;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The listener split, SPEC §5.7.
 * <p>
 * While the rule writer was a separate process it bound loopback, and only the page server was
 * ever exposed. Merging them removed that boundary. These tests are what replaces it, and they
 * check the two properties that make the replacement equivalent: the admin listener cannot be
 * bound anywhere but loopback, and a mutating route is <b>absent</b> from the read listener rather
 * than guarded on it — because a guard can be defeated by a routing mistake and a missing route
 * cannot.
 */
class ListenerRolesTest {

    private GatewayServer server;
    private JournalWatcher<JournalView> watcher;
    private final HttpClient http = HttpClient.newHttpClient();

    /** Every path that changes something. Adding a write endpoint must mean adding it here. */
    private static final java.util.List<String> MUTATING =
        java.util.List.of("/api/decisions", "/api/cash",
            "/api/rules", "/api/pins", "/api/proposal", "/api/worklist");

    @AfterEach
    void stop() {
        if (server != null) server.close();
        if (watcher != null) watcher.close();
    }

    /** A minimal but valid rule set; these tests are about routing, not categorisation. */
    private Rules rules() throws IOException {
        Path dir = Files.createTempDirectory("trex-listener-cfg");
        dir.toFile().deleteOnExit();
        Files.writeString(dir.resolve("categories.yaml"), "categories: [GROCERIES]\nrules: []\n");
        return Rules.load(dir);
    }

    private GatewayServer start(GatewayServer.Role role) throws IOException {
        Path journal = Files.createTempFile("trex-listener", ".jsonl");
        journal.toFile().deleteOnExit();
        watcher = new JournalWatcher<>(journal, Clock.systemUTC(), CombinedFold::new);
        watcher.poll();
        return server = new GatewayServer(watcher, null, rules(), "127.0.0.1", 0, 15_000, role).start();
    }

    private int status(String path, String method) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(
            URI.create("http://127.0.0.1:" + server.port() + path));
        HttpRequest req = method.equals("GET") ? b.GET().build()
            : b.method(method, HttpRequest.BodyPublishers.ofString("{}"))
                .header("Content-Type", "application/json").header("X-Trex-Admin", "1").build();
        return http.send(req, HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    /**
     * The point of the whole exercise. Every mutating path must be unreachable on the listener
     * that may face a network — and 404, not 403, because "forbidden" advertises that it exists.
     */
    @Test
    void theReadListenerCarriesNoMutatingRoute() throws Exception {
        start(GatewayServer.Role.READ);
        for (String path : MUTATING) {
            assertEquals(404, status(path, "POST"), path + " is reachable on the read listener");
        }
    }

    /** ...while still serving what it exists to serve. */
    @Test
    void theReadListenerStillServesPagesAndReads() throws Exception {
        start(GatewayServer.Role.READ);
        assertEquals(200, status("/", "GET"), "the index page");
        assertEquals(200, status("/resolve", "GET"), "the resolve page");
        assertTrue(status("/api/head", "GET") < 400, "reads must work");
        // The registry is a read: the egresses need it to know what an account IS, and publishing
        // it is the whole reason it has one owner (§5.7).
        assertTrue(status("/api/accounts", "GET") < 400, "the registry must be readable");
    }

    /** The admin listener carries everything; these are not 404 there. */
    @Test
    void theAdminListenerCarriesThemAll() throws Exception {
        start(GatewayServer.Role.ADMIN);
        for (String path : MUTATING) {
            assertTrue(status(path, "POST") != 404, path + " is missing from the admin listener");
        }
    }

    /**
     * Loopback is not the admin listener's default, it is its only option — enforced in the
     * constructor, so a config mistake fails at startup rather than exposing the rule writer.
     */
    @Test
    void theAdminListenerRefusesToBindAnythingButLoopback() throws IOException {
        Path journal = Files.createTempFile("trex-listener", ".jsonl");
        journal.toFile().deleteOnExit();
        watcher = new JournalWatcher<>(journal, Clock.systemUTC(), CombinedFold::new);
        Rules rules = rules();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> new GatewayServer(watcher, null, rules, "0.0.0.0", 0, 15_000, GatewayServer.Role.ADMIN));
        assertTrue(e.getMessage().contains("127.0.0.1"), e.getMessage());
    }
}
