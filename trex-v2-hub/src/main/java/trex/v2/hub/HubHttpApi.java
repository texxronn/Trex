package trex.v2.hub;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import trex.v2.hub.api.ErrorResponse;
import trex.v2.log.Json;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * The hub's read API (V2-PROPOSAL.md §7.4, §10). Handlers run on virtual threads and read through
 * the pool, so every client sees one instant of the journal.
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
        route(server, "/api/review", "GET", ex -> {
            String kind = param(ex.getRequestURI().getQuery(), "kind");
            write(ex, 200, api.review(kind));
        });
        route(server, "/api/transfers", "GET", ex -> write(ex, 200, api.transfers()));
        route(server, "/api/units", "GET", ex -> write(ex, 200, api.units()));
        server.createContext("/", ex -> sendError(ex, 404, "not found"));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        return server;
    }

    private interface Handler {
        void handle(HttpExchange ex) throws Exception;
    }

    private static void route(HttpServer server, String path, String method, Handler handler) {
        server.createContext(path, ex -> {
            try {
                if (!ex.getRequestURI().getPath().equals(path)) {
                    sendError(ex, 404, "not found");
                } else if (!ex.getRequestMethod().equals(method)) {
                    ex.getResponseHeaders().set("Allow", method);
                    sendError(ex, 405, "method not allowed");
                } else {
                    handler.handle(ex);
                }
            } catch (Exception e) {
                log.error("500 {} {}: unhandled failure", method, path, e);
                sendError(ex, 500, "internal error: " + e.getMessage());
            } finally {
                ex.close();
            }
        });
    }

    private static String param(String rawQuery, String key) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return null;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(key)) {
                String value = java.net.URLDecoder.decode(pair.substring(eq + 1),
                    java.nio.charset.StandardCharsets.UTF_8);
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
