package trex.resolver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import trex.core.state.LedgerView;
import trex.journal.Json;
import trex.web.EventStreams;
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

/** Resolver web server: static page, state API, and a CSRF-guarded decisions proxy. SPEC §5.4. */
public final class ResolverServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ResolverServer.class);

    static final int MAX_BODY_BYTES = 64 * 1024;
    static final String ADMIN_HEADER = "X-Trex-Admin";

    private static final Set<String> ACTIONS = Set.of("MARK_EXTERNAL", "CONFIRM_TRANSFER", "DISMISS_DUP");
    private static final Set<String> FIELDS = Set.of("action", "externalId", "legA", "legB", "comment");
    private static final Map<String, String> STATIC = Map.of(
        "/", "index.html",
        "/index.html", "index.html",
        "/app.css", "app.css",
        "/app.js", "app.js");

    private final JournalWatcher<LedgerView> watcher;
    private final SequencerClient sequencer;
    private final EventStreams events;
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public ResolverServer(JournalWatcher<LedgerView> watcher, SequencerClient sequencer, String bindAddress, int port) {
        this(watcher, sequencer, bindAddress, port, 15_000);
    }

    ResolverServer(JournalWatcher<LedgerView> watcher, SequencerClient sequencer, String bindAddress, int port, long heartbeatMillis) {
        this.watcher = watcher;
        this.sequencer = sequencer;
        this.events = new EventStreams("state", this::stateJson, heartbeatMillis);
        watcher.addListener(events::publish);
        try {
            server = HttpServer.create(new InetSocketAddress(bindAddress, port), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", this::handle);
        server.setExecutor(executor);
    }

    public ResolverServer start() {
        server.start();
        log.info("resolver serving on {}", server.getAddress());
        return this;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private void handle(HttpExchange ex) {
        try {
            String path = ex.getRequestURI().getPath();
            switch (path) {
                case "/api/state" -> {
                    Web.requireMethod(ex, "GET");
                    Web.json(ex, 200, state());
                }
                case "/api/events" -> {
                    Web.requireMethod(ex, "GET");
                    if (!events.tryAdmit()) {
                        throw new HttpError(503, "too many event streams");
                    }
                    events.stream(ex);
                }
                case "/api/decisions" -> {
                    Web.requireMethod(ex, "POST");
                    decisions(ex);
                }
                default -> {
                    String resource = STATIC.get(path);
                    if (resource == null) {
                        throw new HttpError(404, "not found");
                    }
                    Web.requireMethod(ex, "GET");
                    Web.serveStatic(ex, ResolverServer.class, resource);
                }
            }
        } catch (HttpError e) {
            log.debug("{} {} -> {}: {}", ex.getRequestMethod(), ex.getRequestURI().getPath(),
                e.status(), e.getMessage());
            Web.error(ex, e.status(), e.getMessage());
        } catch (Exception e) {
            log.error("{} {} -> 500: unhandled failure", ex.getRequestMethod(), ex.getRequestURI().getPath(), e);
            Web.error(ex, 500, "internal error: " + e.getMessage());
        } finally {
            ex.close();
        }
    }

    private byte[] stateJson() {
        try {
            return Json.mapper().writeValueAsBytes(state());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    int eventClients() {
        return events.clientCount();
    }

    private Map<String, Object> state() {
        JournalWatcher.Status<LedgerView> s = watcher.status();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("offset", s.offset());
        body.put("n", s.n());
        body.put("updatedAt", s.updatedAt());
        body.put("error", s.error());
        body.put("held", s.view().held());
        body.put("review", s.view().review());
        return body;
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
        } catch (IOException e) {
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
        server.stop(0);
        executor.shutdown();
    }
}
