package trex.v2.hub;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import trex.v2.hub.api.AckDiff;
import trex.v2.hub.api.AckRequest;
import trex.v2.hub.api.DecisionRequest;
import trex.v2.hub.api.ErrorResponse;
import trex.v2.log.Json;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
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

    static HttpServer start(String host, int port, HubApi api) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
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

        server.createContext("/", ex -> sendError(ex, 404, "not found"));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        return server;
    }

    private interface Handler {
        void handle(HttpExchange ex) throws Exception;
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

    private static void sendError(HttpExchange ex, int status, String message) {
        try {
            write(ex, status, new ErrorResponse(message == null ? "" : message));
        } catch (IOException | RuntimeException e) {
            log.debug("could not send {} response", status, e);
        }
    }
}
