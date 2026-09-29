package trex.v2.sequencer;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import trex.v2.log.Json;
import trex.v2.sequencer.api.DecisionBatch;
import trex.v2.sequencer.api.ErrorResponse;
import trex.v2.sequencer.api.FactBatch;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipException;

/**
 * The sequencer's HTTP surface (V2-PROPOSAL.md §6.5): {@code POST /facts}, {@code POST /decisions},
 * {@code GET /head}. Handlers run on virtual threads. Gzip is accepted only when declared
 * ({@code Content-Encoding: gzip}, never sniffed), the decompressed body is capped, and an
 * unsupported encoding is a 415 rather than a parse failure — v1's operational posture, kept.
 */
final class HttpApi {

    private static final Logger log = LoggerFactory.getLogger(HttpApi.class);

    /** v1's cap: a whole-file statement batch, with headroom. */
    static final long DEFAULT_MAX_BODY_BYTES = 100L * 1024 * 1024;

    private HttpApi() {}

    static HttpServer start(String host, int port, Sequencer sequencer) throws IOException {
        long maxBody = DEFAULT_MAX_BODY_BYTES;
        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
        route(server, "/head", "GET", ex -> write(ex, 200, sequencer.head()));
        route(server, "/facts", "POST", ex -> {
            try {
                FactBatch batch = Json.mapper().readValue(readBody(ex, maxBody), FactBatch.class);
                write(ex, 200, sequencer.submitFacts(batch));
            } catch (com.fasterxml.jackson.core.JacksonException e) {
                write(ex, 400, new ErrorResponse("malformed /facts body: " + e.getOriginalMessage()));
            }
        });
        route(server, "/decisions", "POST", ex -> {
            try {
                DecisionBatch batch = Json.mapper().readValue(readBody(ex, maxBody), DecisionBatch.class);
                write(ex, 200, sequencer.submitDecisions(batch));
            } catch (com.fasterxml.jackson.core.JacksonException e) {
                write(ex, 400, new ErrorResponse("malformed /decisions body: " + e.getOriginalMessage()));
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

    /** Exact path and method only; anything else is 404/405, as v1 behaved. */
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
            } catch (PayloadTooLargeException e) {
                log.debug("413 {} {}: {}", method, path, e.getMessage());
                sendError(ex, 413, e.getMessage());
            } catch (UnsupportedEncodingException e) {
                log.debug("415 {} {}: {}", method, path, e.getMessage());
                sendError(ex, 415, e.getMessage());
            } catch (ZipException e) {
                log.debug("400 {} {}: bad gzip", method, path);
                sendError(ex, 400, "malformed gzip body");
            } catch (Exception e) {
                log.error("500 {} {}: unhandled failure", method, path, e);
                sendError(ex, 500, "internal error: " + e.getMessage());
            } finally {
                ex.close();
            }
        });
    }

    private static String readBody(HttpExchange ex, long maxBody) throws IOException {
        String encoding = ex.getRequestHeaders().getFirst("Content-Encoding");
        InputStream in = ex.getRequestBody();
        if (encoding != null && !encoding.isBlank()) {
            String normalized = encoding.toLowerCase(Locale.ROOT).strip();
            if (normalized.equals("gzip")) {
                in = new GZIPInputStream(in);
            } else if (!normalized.equals("identity")) {
                throw new UnsupportedEncodingException(encoding);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > maxBody) {
                throw new PayloadTooLargeException(maxBody);
            }
            out.write(buffer, 0, read);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static void write(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = Json.mapper().writeValueAsBytes(body);
        boolean gzip = acceptsGzip(ex);
        if (gzip) {
            bytes = gzip(bytes);
        }
        ex.getResponseHeaders().set("Content-Type", "application/json");
        if (gzip) {
            ex.getResponseHeaders().set("Content-Encoding", "gzip");
        }
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void sendError(HttpExchange ex, int status, String message) {
        try {
            write(ex, status, new ErrorResponse(message == null ? "" : message));
        } catch (IOException | RuntimeException e) {
            log.debug("could not send {} response; the response had started or the client is gone", status, e);
        }
    }

    private static boolean acceptsGzip(HttpExchange ex) {
        String accept = ex.getRequestHeaders().getFirst("Accept-Encoding");
        return accept != null && accept.toLowerCase(Locale.ROOT).contains("gzip");
    }

    private static byte[] gzip(byte[] bytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(bytes);
        }
        return out.toByteArray();
    }

    /** The decompressed body exceeded the cap. */
    static final class PayloadTooLargeException extends IOException {
        PayloadTooLargeException(long cap) {
            super("request body exceeds " + cap + " bytes");
        }
    }

    /** A Content-Encoding other than gzip or identity. */
    static final class UnsupportedEncodingException extends IOException {
        UnsupportedEncodingException(String encoding) {
            super("unsupported Content-Encoding: " + encoding);
        }
    }
}
