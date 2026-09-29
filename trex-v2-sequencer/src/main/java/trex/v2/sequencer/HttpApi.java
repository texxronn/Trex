package trex.v2.sequencer;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import trex.v2.log.Json;
import trex.v2.sequencer.api.BatchResponse;
import trex.v2.sequencer.api.DecisionBatch;
import trex.v2.sequencer.api.ErrorResponse;
import trex.v2.sequencer.api.FactBatch;
import trex.v2.sequencer.api.HeadResponse;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The sequencer's HTTP surface (V2-PROPOSAL.md §6.5): {@code POST /facts}, {@code POST /decisions},
 * {@code GET /head}. Handlers run on virtual threads; gzip is accepted and returned when offered,
 * and never touches the journal.
 */
final class HttpApi {

    private HttpApi() {}

    static HttpServer start(String host, int port, Sequencer sequencer) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/head", ex -> {
            if (!"GET".equals(ex.getRequestMethod())) {
                methodNotAllowed(ex, "GET");
                return;
            }
            write(ex, 200, sequencer.head());
        });
        server.createContext("/facts", ex -> {
            if (!"POST".equals(ex.getRequestMethod())) {
                methodNotAllowed(ex, "POST");
                return;
            }
            try {
                FactBatch batch = Json.mapper().readValue(readBody(ex), FactBatch.class);
                write(ex, 200, sequencer.submitFacts(batch));
            } catch (com.fasterxml.jackson.core.JacksonException e) {
                write(ex, 400, new ErrorResponse("malformed /facts body: " + e.getOriginalMessage()));
            } catch (RuntimeException e) {
                write(ex, 500, new ErrorResponse(e.getClass().getSimpleName() + ": " + e.getMessage()));
            }
        });
        server.createContext("/decisions", ex -> {
            if (!"POST".equals(ex.getRequestMethod())) {
                methodNotAllowed(ex, "POST");
                return;
            }
            try {
                DecisionBatch batch = Json.mapper().readValue(readBody(ex), DecisionBatch.class);
                write(ex, 200, sequencer.submitDecisions(batch));
            } catch (com.fasterxml.jackson.core.JacksonException e) {
                write(ex, 400, new ErrorResponse("malformed /decisions body: " + e.getOriginalMessage()));
            } catch (RuntimeException e) {
                write(ex, 500, new ErrorResponse(e.getClass().getSimpleName() + ": " + e.getMessage()));
            }
        });
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        return server;
    }

    private static void methodNotAllowed(HttpExchange ex, String allowed) throws IOException {
        ex.getResponseHeaders().set("Allow", allowed);
        write(ex, 405, new ErrorResponse("use " + allowed + " " + ex.getRequestURI().getPath()));
    }

    private static String readBody(HttpExchange ex) throws IOException {
        InputStream in = ex.getRequestBody();
        String encoding = ex.getRequestHeaders().getFirst("Content-Encoding");
        if (encoding != null && encoding.toLowerCase(java.util.Locale.ROOT).contains("gzip")) {
            in = new GZIPInputStream(in);
        }
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
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

    private static boolean acceptsGzip(HttpExchange ex) {
        String accept = ex.getRequestHeaders().getFirst("Accept-Encoding");
        return accept != null && accept.toLowerCase(java.util.Locale.ROOT).contains("gzip");
    }

    private static byte[] gzip(byte[] bytes) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(bytes);
        }
        return out.toByteArray();
    }
}
