package trex.grid;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import trex.journal.Json;
import trex.web.EventStreams;
import trex.web.JournalWatcher;
import trex.web.Web;
import trex.web.Web.HttpError;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Read-only grid web server: static page, head, rows, and SSE head events. SPEC §5.5. */
public final class GridServer implements AutoCloseable {

    private static final Map<String, String> STATIC = Map.of(
        "/", "index.html",
        "/index.html", "index.html",
        "/app.css", "app.css",
        "/app.js", "app.js");

    private final JournalWatcher<GridData> watcher;
    private final GridIndex index = new GridIndex();
    private final EventStreams events;
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public GridServer(JournalWatcher<GridData> watcher, String bindAddress, int port) {
        this(watcher, bindAddress, port, 15_000);
    }

    GridServer(JournalWatcher<GridData> watcher, String bindAddress, int port, long heartbeatMillis) {
        this.watcher = watcher;
        this.events = new EventStreams("head", this::headJson, heartbeatMillis);
        watcher.addListener(events::publish);
        try {
            server = HttpServer.create(new InetSocketAddress(bindAddress, port), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", this::handle);
        server.setExecutor(executor);
    }

    public GridServer start() {
        server.start();
        return this;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private void handle(HttpExchange ex) {
        try {
            String path = ex.getRequestURI().getPath();
            switch (path) {
                case "/api/head" -> {
                    Web.requireMethod(ex, "GET");
                    Web.json(ex, 200, head());
                }
                case "/api/rows" -> {
                    Web.requireMethod(ex, "GET");
                    GridQuery query;
                    try {
                        query = GridQuery.parse(ex.getRequestURI().getRawQuery());
                    } catch (IllegalArgumentException e) {
                        throw new HttpError(400, e.getMessage());
                    }
                    Web.json(ex, 200, index.query(watcher.status().view(), query));
                }
                case "/api/events" -> {
                    Web.requireMethod(ex, "GET");
                    if (!events.tryAdmit()) {
                        throw new HttpError(503, "too many event streams");
                    }
                    events.stream(ex);
                }
                default -> {
                    String resource = STATIC.get(path);
                    if (resource == null) {
                        throw new HttpError(404, "not found");
                    }
                    Web.requireMethod(ex, "GET");
                    Web.serveStatic(ex, GridServer.class, resource);
                }
            }
        } catch (HttpError e) {
            Web.error(ex, e.status(), e.getMessage());
        } catch (Exception e) {
            Web.error(ex, 500, "internal error: " + e.getMessage());
        } finally {
            ex.close();
        }
    }

    private Map<String, Object> head() {
        JournalWatcher.Status<GridData> s = watcher.status();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("n", s.n());
        body.put("offset", s.offset());
        body.put("updatedAt", s.updatedAt());
        body.put("error", s.error());
        body.put("lines", s.view().lines().size());
        body.put("transactions", s.view().transactions());
        body.put("accounts", s.view().accounts());
        return body;
    }

    private byte[] headJson() {
        try {
            return Json.mapper().writeValueAsBytes(head());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        events.close();
        server.stop(0);
        executor.shutdown();
    }
}
