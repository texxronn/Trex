package trex.v2.hub;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import trex.v2.hub.api.ErrorResponse;
import trex.v2.hub.api.HeadResponse;
import trex.v2.hub.api.StatusResponse;
import trex.v2.log.Json;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * The hub's read API (V2-PROPOSAL.md §7.4, §10). For now it serves the status strip and the head;
 * the blotter routes land next. Handlers run on virtual threads and read through the pool.
 */
final class HubHttpApi {

    private static final Logger log = LoggerFactory.getLogger(HubHttpApi.class);

    private HubHttpApi() {}

    static HttpServer start(String host, int port, Supplier<HeadResponse> head, Supplier<StatusResponse> status)
            throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
        route(server, "/head", "GET", ex -> write(ex, 200, head.get()));
        route(server, "/api/status", "GET", ex -> write(ex, 200, status.get()));
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
