package trex.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The page service (SPEC §5.4): static files, one proxy to trex-gateway, one SSE relay.
 * <p>
 * It holds no journal state, owns no config and knows one upstream. Every {@code /api/} path goes
 * through unchanged — one rule rather than a routing table that drifts as the gateway grows, and
 * no chance of trex-web answering a question differently from the service that owns the data.
 * <p>
 * The browser sees one origin. That is what makes the CSRF guard a single rule instead of a CORS
 * policy on a service that writes config files (DECISIONS V6).
 */
public final class WebServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WebServer.class);

    static final int MAX_BODY_BYTES = 64 * 1024;

    /** The pages. Each is served at its own path; the resolver's assets are under /resolve/. */
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
        Map.entry("/resolve", "resolve/index.html"),
        Map.entry("/resolve/", "resolve/index.html"),
        Map.entry("/resolve/index.html", "resolve/index.html"),
        Map.entry("/resolve/app.css", "resolve/app.css"),
        Map.entry("/resolve/app.js", "resolve/app.js"));

    private final Upstream upstream;
    private final EventRelay relay;
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public WebServer(Upstream upstream, String bindAddress, int port) {
        this.upstream = upstream;
        this.relay = new EventRelay(upstream, "/api/events");
        try {
            server = HttpServer.create(new InetSocketAddress(bindAddress, port), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", this::handle);
        server.setExecutor(executor);
    }

    public WebServer start() {
        relay.start();
        server.start();
        log.info("trex-web serving on {}", server.getAddress());
        return this;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    /** Browser clients attached to the relay, for the tests and the health line. */
    public int streamCount() {
        return relay.clientCount();
    }

    private void handle(HttpExchange ex) {
        String path = ex.getRequestURI().getPath();
        try {
            if (path.equals("/api/events")) {
                Web.requireMethod(ex, "GET");
                relay.stream(ex);
            } else if (path.startsWith("/api/")) {
                proxy(ex, path);
            } else {
                String resource = STATIC.get(path);
                if (resource == null) {
                    throw new Web.HttpError(404, "not found");
                }
                Web.requireMethod(ex, "GET");
                Web.serveStatic(ex, resource);
            }
        } catch (Web.HttpError e) {
            log.debug("{} {} -> {}: {}", ex.getRequestMethod(), path, e.status(), e.getMessage());
            Web.error(ex, e.status(), e.getMessage());
        } catch (Exception e) {
            log.error("{} {} -> 500: unhandled failure", ex.getRequestMethod(), path, e);
            Web.error(ex, 500, "internal error: " + e.getMessage());
        } finally {
            ex.close();
        }
    }

    /**
     * The CSRF guard that faces the browser. It is enforced <em>here</em>, because only here is
     * {@code Origin} meaningful: it describes the browser's relationship to this service. The
     * gateway keeps its own guard for a caller that reaches it directly, but a proxied request
     * arrives with no Origin at all — see {@link Upstream#post}.
     */
    private void proxy(HttpExchange ex, String path) throws Exception {
        String method = ex.getRequestMethod();
        if (method.equals("GET")) {
            Web.relay(ex, upstream.get(path, ex.getRequestURI().getRawQuery()));
            return;
        }
        if (!method.equals("POST")) {
            ex.getResponseHeaders().set("Allow", "GET, POST");
            throw new Web.HttpError(405, "method not allowed");
        }
        String contentType = ex.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase(java.util.Locale.ROOT).startsWith("application/json")) {
            throw new Web.HttpError(415, "Content-Type must be application/json");
        }
        if (!"1".equals(ex.getRequestHeaders().getFirst("X-Trex-Admin"))) {
            throw new Web.HttpError(403, "missing X-Trex-Admin header");
        }
        String origin = ex.getRequestHeaders().getFirst("Origin");
        String host = ex.getRequestHeaders().getFirst("Host");
        if (origin != null && (host == null || !origin.equals("http://" + host))) {
            throw new Web.HttpError(403, "cross-origin request refused");
        }
        Web.relay(ex, upstream.post(path, readBody(ex), contentType,
            ex.getRequestHeaders().getFirst("X-Trex-Admin")));
    }

    private static byte[] readBody(HttpExchange ex) throws IOException, Web.HttpError {
        try (InputStream in = ex.getRequestBody()) {
            byte[] bytes = in.readNBytes(MAX_BODY_BYTES + 1);
            if (bytes.length > MAX_BODY_BYTES) {
                throw new Web.HttpError(413, "request body too large");
            }
            return bytes;
        }
    }

    @Override
    public void close() {
        server.stop(0);
        relay.close();
        executor.close();
    }
}
