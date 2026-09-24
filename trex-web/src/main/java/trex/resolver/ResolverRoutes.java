package trex.resolver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import trex.category.Categorized;
import trex.category.Categorizer;
import trex.category.Transfers;
import trex.core.CanonicalEvent;
import trex.core.state.LedgerView;
import trex.journal.Json;
import trex.web.EventStreams;
import trex.web.JournalView;
import trex.web.JournalWatcher;
import trex.web.Web;
import trex.web.Web.HttpError;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * The resolution half of the web service (SPEC §5.4): its page at {@code /resolve}, the state
 * API, and the CSRF-guarded decisions proxy. Everything here is namespaced so the two halves can
 * each keep an event stream without one shadowing the other.
 */
public final class ResolverRoutes implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ResolverRoutes.class);

    static final int MAX_BODY_BYTES = 64 * 1024;
    static final String ADMIN_HEADER = "X-Trex-Admin";

    private static final Set<String> ACTIONS = Set.of("MARK_EXTERNAL", "CONFIRM_TRANSFER", "DISMISS_DUP");
    private static final Set<String> FIELDS = Set.of("action", "externalId", "legA", "legB", "comment");
    private static final Map<String, String> STATIC = Map.of(
        "/resolve", "resolve/index.html",
        "/resolve/", "resolve/index.html",
        "/resolve/index.html", "resolve/index.html",
        "/resolve/app.css", "resolve/app.css",
        "/resolve/app.js", "resolve/app.js");

    private final JournalWatcher<JournalView> watcher;
    private final SequencerClient sequencer;
    private final Categorizer categorizer;
    private final EventStreams events;

    public ResolverRoutes(JournalWatcher<JournalView> watcher, SequencerClient sequencer, Categorizer categorizer,
                          long heartbeatMillis) {
        this.watcher = watcher;
        this.sequencer = sequencer;
        this.categorizer = categorizer;
        this.events = new EventStreams("state", this::stateJson, heartbeatMillis);
        watcher.addListener(events::publish);
    }

    /** Errors are turned into responses by the server; this half only routes. */
    public void handle(HttpExchange ex, String path) throws Exception {
            switch (path) {
                case "/api/resolve/state" -> {
                    Web.requireMethod(ex, "GET");
                    Web.json(ex, 200, state());
                }
                case "/api/resolve/events" -> {
                    Web.requireMethod(ex, "GET");
                    if (!events.tryAdmit()) {
                        throw new HttpError(503, "too many event streams");
                    }
                    events.stream(ex);
                }
                case "/api/resolve/decisions" -> {
                    Web.requireMethod(ex, "POST");
                    decisions(ex);
                }
                default -> {
                    String resource = STATIC.get(path);
                    if (resource == null) {
                        throw new HttpError(404, "not found");
                    }
                    Web.requireMethod(ex, "GET");
                    Web.serveStatic(ex, ResolverRoutes.class, resource);
                }
            }
    }

    private byte[] stateJson() {
        try {
            return Json.mapper().writeValueAsBytes(state());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public int streamCount() {
        return events.clientCount();
    }

    private Map<String, Object> state() {
        JournalWatcher.Status<JournalView> s = watcher.status();
        LedgerView view = s.view().ledger();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("offset", s.offset());
        body.put("n", s.n());
        body.put("updatedAt", s.updatedAt());
        body.put("error", s.error());
        body.put("held", view.held());
        body.put("review", view.review());
        // Read-only: the resolver shows what a row's category is and why, but takes no category
        // action — a correction is a pin in categories.yaml, not a decision (SPEC §0.7, §5.4).
        body.put("categories", categories(view));
        return body;
    }

    /** Category per shown row, keyed by n, derived from the same snapshot the rows come from. */
    private Map<String, Object> categories(LedgerView view) {
        Set<String> transfers = Transfers.ids(view.latestLines());
        Map<String, Object> out = new LinkedHashMap<>();
        for (CanonicalEvent line : Stream.concat(view.held().stream(), view.review().stream()).toList()) {
            Categorized c = categorizer.categorize(line, transfers);
            out.put(String.valueOf(line.n()), Map.of(
                "category", c.category(),
                "origin", c.origin().name(),
                "why", c.explain(),
                // What to paste into categories.yaml to pin this one transaction.
                "pin", "  - category: <CATEGORY>\n    when: {externalId: [\"" + line.externalId() + "\"]}"));
        }
        return out;
    }

    private void decisions(HttpExchange ex) throws Exception {
        String contentType = ex.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase().startsWith("application/json")) {
            throw new HttpError(415, "Content-Type must be application/json");
        }
        if (!"1".equals(ex.getRequestHeaders().getFirst(ADMIN_HEADER))) {
            throw new HttpError(403, "missing " + ADMIN_HEADER + " header");
        }
        String origin = ex.getRequestHeaders().getFirst("Origin");
        String host = ex.getRequestHeaders().getFirst("Host");
        if (origin != null && (host == null || !origin.equals("http://" + host))) {
            throw new HttpError(403, "cross-origin request refused");
        }

        JsonNode body;
        try {
            body = Json.mapper().readTree(readBody(ex));
        } catch (IOException _) {
            throw new HttpError(400, "malformed JSON");
        }
        if (body == null || !body.isObject()) {
            throw new HttpError(400, "body must be a JSON object");
        }
        ObjectNode decision = Json.mapper().createObjectNode();
        decision.put("decisionRef", "ui-" + UUID.randomUUID());
        var names = body.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            JsonNode value = body.get(name);
            if (!FIELDS.contains(name)) {
                throw new HttpError(400, "unknown field: " + name);
            }
            if (value.isNull()) {
                continue;
            }
            if (!value.isTextual()) {
                throw new HttpError(400, name + " must be a string");
            }
            decision.put(name, value.asText());
        }
        if (!decision.has("action") || !ACTIONS.contains(decision.get("action").asText())) {
            throw new HttpError(400, "action must be one of " + ACTIONS);
        }
        ObjectNode request = Json.mapper().createObjectNode();
        ArrayNode list = request.putArray("decisions");
        list.add(decision);

        SequencerClient.Reply reply;
        try {
            reply = sequencer.postDecisions(Json.mapper().writeValueAsBytes(request));
        } catch (IOException e) {
            throw new HttpError(502, "sequencer unreachable: " + e.getMessage());
        }
        byte[] out = reply.body().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(reply.status(), out.length == 0 ? -1 : out.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(out);
        }
    }

    private static byte[] readBody(HttpExchange ex) throws IOException, HttpError {
        try (InputStream in = ex.getRequestBody()) {
            byte[] bytes = in.readNBytes(MAX_BODY_BYTES + 1);
            if (bytes.length > MAX_BODY_BYTES) {
                throw new HttpError(413, "request body too large");
            }
            return bytes;
        }
    }

    @Override
    public void close() {
        events.close();
    }
}
