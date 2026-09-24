package trex.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.sun.net.httpserver.HttpExchange;
import trex.journal.Json;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;

/** Shared HttpServer helpers for the follower web services: static pages, JSON, errors, headers. */
public final class Web {

    private static final Logger log = LoggerFactory.getLogger(Web.class);

    /** An error mapped to an HTTP status and a JSON {@code {"error": ...}} body. */
    public static final class HttpError extends Exception {
        private final int status;

        public HttpError(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    private Web() {}

    public static void requireMethod(HttpExchange ex, String method) throws HttpError {
        if (!ex.getRequestMethod().equals(method)) {
            ex.getResponseHeaders().set("Allow", method);
            throw new HttpError(405, "method not allowed");
        }
    }

    /** Serve a classpath resource under {@code /web/} with strict security headers. */
    public static void serveStatic(HttpExchange ex, Class<?> anchor, String resource) throws IOException {
        byte[] bytes;
        try (InputStream in = anchor.getResourceAsStream("/web/" + resource)) {
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

    public static void json(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = Json.mapper().writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    /** Best-effort error response; ignored if the response has already started. */
    public static void error(HttpExchange ex, int status, String message) {
        try {
            json(ex, status, Map.of("error", message == null ? "" : message));
        } catch (IOException | RuntimeException e) {
            log.debug("could not send {} response; the response had started or the client is gone", status, e);
        }
    }
}
