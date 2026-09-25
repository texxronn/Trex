package trex.ws.grid;

import com.sun.net.httpserver.HttpExchange;
import trex.journal.Json;
import trex.category.Categories;
import trex.category.Categorizer;
import trex.ws.Rules;
import trex.ws.EventStreams;
import trex.ws.JournalView;
import trex.ws.JournalWatcher;
import trex.ws.Web;
import trex.ws.Web.HttpError;

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
 * events. Read-only — everything that changes anything lives in the ledger half.
 */
public final class BrowseRoutes implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(BrowseRoutes.class);

    private final JournalWatcher<JournalView> watcher;
    private final Rules rules;
    private final EventStreams events;

    public BrowseRoutes(JournalWatcher<JournalView> watcher, Rules rules, long heartbeatMillis) {
        this.watcher = watcher;
        this.rules = rules;
        this.events = new EventStreams("head", this::headJson, heartbeatMillis);
        watcher.addListener(events::publish);
        // A rule change moves no journal line but moves what every row says, so it is a change
        // worth announcing on the same stream (§5.7).
        rules.addListener(events::publish);
    }

    /** Rebuilt per query: the rule set can change under us, and each answer names its revision. */
    private GridIndex index() {
        return new GridIndex(rules.categorizer());
    }

    private List<String> categories() {
        List<String> declared = new ArrayList<>(rules.categorizer().declared());
        declared.add(Categories.TRANSFER);
        declared.add(Categories.UNCATEGORIZED);
        return List.copyOf(declared);
    }

    /** Errors are turned into responses by the server; this half only routes. */
    public void handle(HttpExchange ex, String path) throws Exception {
        switch (path) {
            case "/api/head" -> {
                Web.requireMethod(ex, "GET");
                Web.json(ex, 200, head());
            }
            case "/api/snapshot" -> {
                Web.requireMethod(ex, "GET");
                GridQuery query;
                try {
                    query = GridQuery.parse(ex.getRequestURI().getRawQuery());
                } catch (IllegalArgumentException e) {
                    throw new HttpError(400, e.getMessage());
                }
                // The page is a record; the revision that produced it is added alongside rather
                // than inside, so the query engine stays unaware of rule versioning.
                @SuppressWarnings("unchecked")
                Map<String, Object> answer = new LinkedHashMap<>(
                    Json.mapper().convertValue(index().query(watcher.status().view().grid(), query), Map.class));
                answer.put("rulesRevision", rules.revision());
                Web.json(ex, 200, answer);
            }
            // One stream for the whole API: it says what moved, never what the rows now say,
            // so a rule change costs a frame rather than a megabyte (§5.7).
            case "/api/events" -> {
                Web.requireMethod(ex, "GET");
                if (!events.tryAdmit()) {
                    throw new HttpError(503, "too many event streams");
                }
                events.stream(ex);
            }
            default -> throw new HttpError(404, "not found");
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
        // Every frame and every answer names the rule set that produced it, so a consumer can
        // say which revision it projected under and notice when that changes (§5.7).
        body.put("rulesRevision", rules.revision());
        body.put("lines", s.view().grid().lines().size());
        body.put("transactions", s.view().grid().transactions());
        body.put("accounts", s.view().grid().accounts());
        // The declared categories, so the page can offer them as filters. Derived, never stored (§0.7).
        body.put("categories", categories());
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
