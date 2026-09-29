package trex.v2.hub;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import trex.v2.hub.api.AckDiff;
import trex.v2.hub.api.AckRequest;
import trex.v2.hub.api.DecisionRequest;
import trex.v2.hub.api.ErrorResponse;
import trex.v2.hub.api.ReflowRequest;
import trex.v2.log.Json;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;

/**
 * The hub's read and decision API (V2-PROPOSAL.md §7.4, §10, §6.6). Handlers run on virtual
 * threads; reads go through the pool, writes are forwarded to the only writer.
 */
final class HubHttpApi {

    private static final Logger log = LoggerFactory.getLogger(HubHttpApi.class);

    private HubHttpApi() {}

    static HttpServer start(String host, int port, HubApi api, HubEvents events) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/api/events", ex -> streamEvents(ex, api, events));
        route(server, "/head", "GET", ex -> write(ex, 200, api.head()));
        route(server, "/api/status", "GET", ex -> write(ex, 200, api.status()));
        route(server, "/api/refdata", "GET", ex -> write(ex, 200, api.refdata()));
        route(server, "/api/ledger", "GET", ex -> {
            try {
                write(ex, 200, api.ledger(BlotterQuery.parse(ex.getRequestURI().getQuery())));
            } catch (IllegalArgumentException e) {
                sendError(ex, 400, e.getMessage());
            }
        });
        route(server, "/api/review", "GET",
            ex -> write(ex, 200, api.review(param(ex.getRequestURI().getQuery(), "kind"))));
        route(server, "/api/transfers", "GET", ex -> write(ex, 200, api.transfers()));
        route(server, "/api/units", "GET", ex -> write(ex, 200, api.units()));
        route(server, "/api/reconcile", "GET", ex -> write(ex, 200, api.reconcile()));
        route(server, "/api/opening", "GET", ex -> write(ex, 200, api.opening()));
        route(server, "/api/workbook", "GET", ex -> write(ex, 200, api.workbook()));
        Map<String, Handler> projection = new LinkedHashMap<>();
        projection.put("GET", ex -> write(ex, 200, api.projection()));
        projection.put("POST", ex -> {
            try {
                trex.v2.hub.api.ProjectionRequest request = Json.mapper().readValue(readBody(ex),
                    trex.v2.hub.api.ProjectionRequest.class);
                DecisionOutcome outcome = api.putProjection(request);
                write(ex, outcome.status(), outcome.body());
            } catch (com.fasterxml.jackson.core.JacksonException e) {
                sendError(ex, 400, "malformed body: " + e.getOriginalMessage());
            }
        });
        route(server, "/api/projection", projection);
        Map<String, Handler> cursors = new LinkedHashMap<>();
        cursors.put("GET", ex -> write(ex, 200, api.cursors()));
        cursors.put("POST", ex -> {
            try {
                trex.v2.hub.api.CursorRequest request = Json.mapper().readValue(readBody(ex),
                    trex.v2.hub.api.CursorRequest.class);
                DecisionOutcome outcome = api.putCursors(request);
                write(ex, outcome.status(), outcome.body());
            } catch (com.fasterxml.jackson.core.JacksonException e) {
                sendError(ex, 400, "malformed body: " + e.getOriginalMessage());
            }
        });
        route(server, "/api/cursors", cursors);
        route(server, "/api/decisions", "POST", ex -> {
            try {
                DecisionRequest request = Json.mapper().readValue(readBody(ex), DecisionRequest.class);
                DecisionOutcome outcome = api.submitDecisions(request);
                write(ex, outcome.status(), outcome.body());
            } catch (com.fasterxml.jackson.core.JacksonException e) {
                sendError(ex, 400, "malformed body: " + e.getOriginalMessage());
            }
        });

        Map<String, Handler> acks = new LinkedHashMap<>();
        acks.put("GET", ex -> write(ex, 200, api.acks()));
        acks.put("POST", ex -> {
            try {
                AckRequest request = Json.mapper().readValue(readBody(ex), AckRequest.class);
                DecisionOutcome outcome = api.postAck(request);
                write(ex, outcome.status(), outcome.body());
            } catch (com.fasterxml.jackson.core.JacksonException e) {
                sendError(ex, 400, "malformed body: " + e.getOriginalMessage());
            }
        });
        route(server, "/api/acks", acks);
        route(server, "/api/eyeball", "GET", ex -> {
            try {
                String query = ex.getRequestURI().getQuery();
                String period = param(query, "period");
                String user = param(query, "user");
                String asOf = param(query, "asOf");
                write(ex, 200, api.eyeball(period, user, asOf == null ? null : java.time.LocalDate.parse(asOf)));
            } catch (IllegalArgumentException e) {
                sendError(ex, 400, e.getMessage());
            }
        });
        route(server, "/api/reflow/preview", "POST", ex -> {
            try {
                ReflowRequest request = Json.mapper().readValue(readBody(ex), ReflowRequest.class);
                DecisionOutcome outcome = api.reflowPreview(request.categories());
                write(ex, outcome.status(), outcome.body());
            } catch (com.fasterxml.jackson.core.JacksonException e) {
                sendError(ex, 400, "malformed body: " + e.getOriginalMessage());
            }
        });
        Map<String, Handler> configCategories = new LinkedHashMap<>();
        configCategories.put("GET", ex -> {
            Optional<String> yaml = api.categoriesYaml();
            if (yaml.isEmpty()) {
                sendError(ex, 404, "no categories.yaml");
            } else {
                writeText(ex, 200, "text/yaml", yaml.get());
            }
        });
        configCategories.put("PUT", ex -> {
            try {
                ReflowRequest request = Json.mapper().readValue(readBody(ex), ReflowRequest.class);
                DecisionOutcome outcome = api.saveCategories(request.categories());
                write(ex, outcome.status(), outcome.body());
            } catch (com.fasterxml.jackson.core.JacksonException e) {
                sendError(ex, 400, "malformed body: " + e.getOriginalMessage());
            }
        });
        route(server, "/api/config/categories", configCategories);
        route(server, "/api/acks/diff", "GET", ex -> {
            String user = param(ex.getRequestURI().getQuery(), "user");
            String period = param(ex.getRequestURI().getQuery(), "period");
            Optional<AckDiff> diff = api.ackDiff(user, period);
            if (diff.isEmpty()) {
                sendError(ex, 404, "no such ack");
            } else {
                write(ex, 200, diff.get());
            }
        });

        server.createContext("/", ex -> serveStaticOrNotFound(ex));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        return server;
    }

    /** Static UI assets under {@code /ui/}, the index at {@code /}; everything else is 404. */
    private static void serveStaticOrNotFound(HttpExchange ex) {
        try {
            if (!"GET".equals(ex.getRequestMethod())) {
                sendError(ex, 405, "method not allowed");
                return;
            }
            String path = ex.getRequestURI().getPath();
            if (path.equals("/") || path.equals("/index.html")) {
                serveStatic(ex, "index.html");
            } else if (path.startsWith("/ui/")) {
                serveStatic(ex, path.substring("/ui/".length()));
            } else {
                sendError(ex, 404, "not found");
            }
        } catch (Exception e) {
            log.error("500 static: unhandled failure", e);
            sendError(ex, 500, "internal error: " + e.getMessage());
        } finally {
            ex.close();
        }
    }

    private static void serveStatic(HttpExchange ex, String relative) throws IOException {
        if (relative.contains("..") || relative.startsWith("/") || relative.isBlank()) {
            sendError(ex, 404, "not found");
            return;
        }
        try (InputStream in = HubHttpApi.class.getResourceAsStream("/trex/v2/hub/web/" + relative)) {
            if (in == null) {
                sendError(ex, 404, "not found");
                return;
            }
            byte[] bytes = in.readAllBytes();
            ex.getResponseHeaders().set("Content-Type", contentType(relative));
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    private static String contentType(String path) {
        if (path.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (path.endsWith(".js")) {
            return "text/javascript; charset=utf-8";
        }
        if (path.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (path.endsWith(".json")) {
            return "application/json";
        }
        if (path.endsWith(".svg")) {
            return "image/svg+xml";
        }
        return "application/octet-stream";
    }

    private interface Handler {
        void handle(HttpExchange ex) throws Exception;
    }

    /**
     * Server-Sent Events (V2-PROPOSAL.md §7.4): a snapshot at the current instant, then a delta
     * whenever the index moves. The subscriber is registered before the snapshot is read, so no
     * change can fall between them.
     */
    private static void streamEvents(HttpExchange ex, HubApi api, HubEvents events) {
        if (!"GET".equals(ex.getRequestMethod())) {
            sendError(ex, 405, "method not allowed");
            ex.close();
            return;
        }
        try {
            ex.getResponseHeaders().set("Content-Type", "text/event-stream");
            ex.getResponseHeaders().set("Cache-Control", "no-cache");
            ex.getResponseHeaders().set("Connection", "keep-alive");
            ex.sendResponseHeaders(200, 0);
        } catch (IOException e) {
            ex.close();
            return;
        }
        try (OutputStream out = ex.getResponseBody();
             HubEvents.Subscription subscription = events.subscribe()) {
            writeEvent(out, "snapshot", api.head());
            while (events.isOpen()) {
                java.util.Optional<HubEvents.Change> change = subscription.poll(Duration.ofSeconds(15));
                if (change.isPresent()) {
                    writeEvent(out, "delta", change.get());
                } else {
                    out.write(": ping\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            }
        } catch (IOException e) {
            log.debug("SSE client disconnected");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            ex.close();
        }
    }

    private static void writeEvent(OutputStream out, String event, Object data) throws IOException {
        out.write(("event: " + event + "\n").getBytes(StandardCharsets.UTF_8));
        out.write(("data: " + Json.mapper().writeValueAsString(data) + "\n\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static void route(HttpServer server, String path, String method, Handler handler) {
        route(server, path, Map.of(method, handler));
    }

    private static void route(HttpServer server, String path, Map<String, Handler> handlers) {
        server.createContext(path, ex -> {
            try {
                if (!ex.getRequestURI().getPath().equals(path)) {
                    sendError(ex, 404, "not found");
                    return;
                }
                Handler handler = handlers.get(ex.getRequestMethod());
                if (handler == null) {
                    ex.getResponseHeaders().set("Allow", String.join(", ", handlers.keySet()));
                    sendError(ex, 405, "method not allowed");
                    return;
                }
                handler.handle(ex);
            } catch (Exception e) {
                log.error("500 {} {}: unhandled failure", ex.getRequestMethod(), path, e);
                sendError(ex, 500, "internal error: " + e.getMessage());
            } finally {
                ex.close();
            }
        });
    }

    private static String readBody(HttpExchange ex) throws IOException {
        byte[] bytes = ex.getRequestBody().readAllBytes();
        if (bytes.length > 10 * 1024 * 1024) {
            throw new IOException("request body too large");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String param(String rawQuery, String key) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return null;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(key)) {
                String value = java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                return value.isBlank() ? null : value;
            }
        }
        return null;
    }

    private static void write(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = Json.mapper().writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void writeText(HttpExchange ex, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void sendError(HttpExchange ex, int status, String message) {
        try {
            write(ex, status, new ErrorResponse(message == null ? "" : message));
        } catch (IOException | RuntimeException e) {
            log.debug("could not send {} response", status, e);
        }
    }
}
