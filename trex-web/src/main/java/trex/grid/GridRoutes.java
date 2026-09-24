package trex.grid;

import com.sun.net.httpserver.HttpExchange;
import trex.journal.Json;
import trex.category.Categories;
import trex.category.Categorizer;
import trex.web.EventStreams;
import trex.web.JournalView;
import trex.web.JournalWatcher;
import trex.web.Web;
import trex.web.Web.HttpError;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The browsing half of the web service (SPEC §5.4): the table page, head, rows, and SSE head
 * events. Read-only — everything that changes anything lives in the resolution half.
 */
public final class GridRoutes implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(GridRoutes.class);

    private static final Map<String, String> STATIC = Map.of(
        "/", "index.html",
        "/index.html", "index.html",
        "/app.css", "app.css",
        "/app.js", "app.js");

    private final JournalWatcher<JournalView> watcher;
    private final GridIndex index;
    private final List<String> categories;
    private final EventStreams events;

    public GridRoutes(JournalWatcher<JournalView> watcher, Categorizer categorizer, long heartbeatMillis) {
        this.watcher = watcher;
        this.index = new GridIndex(categorizer);
        List<String> declared = new ArrayList<>(categorizer.declared());
        declared.add(Categories.TRANSFER);
        declared.add(Categories.UNCATEGORIZED);
        this.categories = List.copyOf(declared);
        this.events = new EventStreams("head", this::headJson, heartbeatMillis);
        watcher.addListener(events::publish);
    }

    /** Errors are turned into responses by the server; this half only routes. */
    public void handle(HttpExchange ex, String path) throws Exception {
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
                    Web.json(ex, 200, index.query(watcher.status().view().grid(), query));
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
                    Web.serveStatic(ex, GridRoutes.class, resource);
                }
            }
    }

    public int streamCount() {
        return events.clientCount();
    }

    private Map<String, Object> head() {
        JournalWatcher.Status<JournalView> s = watcher.status();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("n", s.n());
        body.put("offset", s.offset());
        body.put("updatedAt", s.updatedAt());
        body.put("error", s.error());
        body.put("lines", s.view().grid().lines().size());
        body.put("transactions", s.view().grid().transactions());
        body.put("accounts", s.view().grid().accounts());
        // The declared categories, so the page can offer them as filters. Derived, never stored (§0.7).
        body.put("categories", categories);
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
    }
}
