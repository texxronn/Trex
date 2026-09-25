package trex.ws;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.Map;

/**
 * The pages (SPEC §5.4), served by the same process that holds the fold (§5.7).
 * <p>
 * This used to be a service of its own with a proxy, a last-good-snapshot cache and an SSE relay
 * — 325 lines whose entire purpose was to bridge a process boundary. All of it is gone, and with
 * it a class of bug that could not be reproduced with {@code curl}: the proxy forwarded no
 * {@code Origin}, so a CSRF check that rejected every browser write passed every command-line
 * test. One origin in fact, rather than one manufactured by proxying, makes that check ordinary.
 * <p>
 * What remains is the route table. It is explicit rather than a directory walk, so a file dropped
 * into the resources tree is not automatically reachable, and so each page's assets are named
 * beside it — the bug where {@code /resolve} referenced the grid's {@code /app.js} was invisible
 * until the paths were written down together.
 */
final class PageServer {

    /** Each page at its own path; a page's assets live under its own prefix. */
    private static final Map<String, String> STATIC = Map.ofEntries(
        Map.entry("/", "index.html"),
        Map.entry("/index.html", "index.html"),
        Map.entry("/app.css", "app.css"),
        Map.entry("/app.js", "app.js"),
        Map.entry("/categorize", "categorize/index.html"),
        Map.entry("/categorize/", "categorize/index.html"),
        Map.entry("/categorize/index.html", "categorize/index.html"),
        Map.entry("/categorize/app.css", "categorize/app.css"),
        Map.entry("/categorize/app.js", "categorize/app.js"),
        Map.entry("/cash", "cash/index.html"),
        Map.entry("/cash/", "cash/index.html"),
        Map.entry("/cash/index.html", "cash/index.html"),
        Map.entry("/cash/app.css", "cash/app.css"),
        Map.entry("/cash/app.js", "cash/app.js"),
        Map.entry("/resolve", "resolve/index.html"),
        Map.entry("/resolve/", "resolve/index.html"),
        Map.entry("/resolve/index.html", "resolve/index.html"),
        Map.entry("/resolve/app.css", "resolve/app.css"),
        Map.entry("/resolve/app.js", "resolve/app.js"));

    private PageServer() {}

    static void servePage(HttpExchange ex, String path) throws Web.HttpError, IOException {
        String resource = STATIC.get(path);
        if (resource == null) {
            throw new Web.HttpError(404, "not found");
        }
        Web.requireMethod(ex, "GET");
        Web.serveStatic(ex, PageServer.class, resource);
    }

    /** Every path this listener will serve, for the test that asserts nothing else is reachable. */
    static java.util.Set<String> paths() {
        return STATIC.keySet();
    }
}
