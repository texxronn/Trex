package trex.resolver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import trex.journal.Json;

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

    static final int MAX_BODY_BYTES = 64 * 1024;
    static final String ADMIN_HEADER = "X-Trex-Admin";

    private static final Set<String> ACTIONS = Set.of("MARK_EXTERNAL", "CONFIRM_TRANSFER", "DISMISS_DUP");
    private static final Set<String> FIELDS = Set.of("action", "externalId", "legA", "legB", "comment");
    private static final Map<String, String> STATIC = Map.of(
        "/", "index.html",
        "/index.html", "index.html",
        "/app.css", "app.css",
        "/app.js", "app.js");

    private final JournalWatcher watcher;
    private final SequencerClient sequencer;
    private final EventStreams events;
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    private static final class HttpError extends Exception {
        final int status;

        HttpError(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    public ResolverServer(JournalWatcher watcher, SequencerClient sequencer, String bindAddress, int port) {
        this(watcher, sequencer, bindAddress, port, 15_000);
    }

    ResolverServer(JournalWatcher watcher, SequencerClient sequencer, String bindAddress, int port, long heartbeatMillis) {
        this.watcher = watcher;
        this.sequencer = sequencer;
        this.events = new EventStreams(this::stateJson, heartbeatMillis);
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
                    requireMethod(ex, "GET");
                    json(ex, 200, state());
                }
                case "/api/events" -> {
                    requireMethod(ex, "GET");
                    if (!events.tryAdmit()) {
                        throw new HttpError(503, "too many event streams");
                    }
                    events.stream(ex);
                }
                case "/api/decisions" -> {
                    requireMethod(ex, "POST");
                    decisions(ex);
                }
                default -> {
                    String resource = STATIC.get(path);
                    if (resource == null) {
                        throw new HttpError(404, "not found");
                    }
                    requireMethod(ex, "GET");
                    serveStatic(ex, resource);
                }
            }
        } catch (HttpError e) {
            error(ex, e.status, e.getMessage());
        } catch (Exception e) {
            error(ex, 500, "internal error: " + e.getMessage());
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
        JournalWatcher.Status s = watcher.status();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("offset", s.offset());
        body.put("n", s.view().highWaterN());
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

    private static void requireMethod(HttpExchange ex, String method) throws HttpError {
        if (!ex.getRequestMethod().equals(method)) {
            ex.getResponseHeaders().set("Allow", method);
            throw new HttpError(405, "method not allowed");
        }
    }

    private static void serveStatic(HttpExchange ex, String resource) throws IOException {
        byte[] bytes;
        try (InputStream in = ResolverServer.class.getResourceAsStream("/web/" + resource)) {
            if (in == null) {
                throw new IOException("missing resource " + resource);
            }
            bytes = in.readAllBytes();
        }
        String type = switch (resource.substring(resource.lastIndexOf('.') + 1)) {
            case "html" -> "text/html; charset=utf-8";
            case "css" -> "text/css; charset=utf-8";
            default -> "text/javascript; charset=utf-8";
        };
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Content-Security-Policy",
            "default-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void json(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = Json.mapper().writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void error(HttpExchange ex, int status, String message) {
        try {
            json(ex, status, Map.of("error", message == null ? "" : message));
        } catch (IOException | RuntimeException ignored) {
            // response already started or client gone
        }
    }

    @Override
    public void close() {
        events.close();
        server.stop(0);
        executor.shutdown();
    }
}
