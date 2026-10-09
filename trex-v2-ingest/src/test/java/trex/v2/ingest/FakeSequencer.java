package trex.v2.ingest;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import trex.v2.log.Json;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.GZIPInputStream;

/**
 * A minimal sequencer for the ingest tests: answers one outcome for every fact, and records the
 * decisions posted (the re-parse apply path).
 */
public final class FakeSequencer implements AutoCloseable {

    private final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    public volatile int calls;
    public volatile int factsSeen;
    public volatile int decisionCalls;
    public volatile int ingests;
    public final List<Map<String, Object>> decisions = new CopyOnWriteArrayList<>();
    public final List<Map<String, Object>> ingestEvents = new CopyOnWriteArrayList<>();
    private final String outcome;

    public FakeSequencer(String outcome) throws Exception {
        this.outcome = outcome;
        server.createContext("/facts", exchange -> {
            calls++;
            Map<String, Object> body = Json.mapper().readValue(body(exchange), Map.class);
            List<?> facts = (List<?>) body.get("facts");
            factsSeen += facts.size();
            List<Map<String, Object>> results = new ArrayList<>();
            for (int i = 0; i < facts.size(); i++) {
                results.add(map("ref", "fact[" + i + "]", "outcome", outcome, "externalId", "x" + i,
                    "n", i + 1L, "reason", null));
            }
            respond(exchange, 200, Json.mapper().writeValueAsString(
                map("batchHandle", "h", "batchStatus", "COMMITTED", "results", results)));
        });
        server.createContext("/decisions", exchange -> {
            decisionCalls++;
            Map<String, Object> body = Json.mapper().readValue(body(exchange), Map.class);
            for (Object d : (List<?>) body.get("decisions")) {
                decisions.add((Map<String, Object>) d);
            }
            respond(exchange, 200, "{\"batchHandle\":\"h\",\"batchStatus\":\"COMMITTED\",\"results\":[]}");
        });
        server.createContext("/ingest", exchange -> {
            ingests++;
            ingestEvents.add(Json.mapper().readValue(body(exchange), Map.class));
            respond(exchange, 200, "{\"n\":1,\"offset\":1}");
        });
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static byte[] body(HttpExchange exchange) throws IOException {
        byte[] raw = exchange.getRequestBody().readAllBytes();
        String encoding = exchange.getRequestHeaders().getFirst("Content-Encoding");
        if (encoding != null && encoding.contains("gzip")) {
            try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(raw))) {
                return in.readAllBytes();
            }
        }
        return raw;
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            out.put((String) kv[i], kv[i + 1]);
        }
        return out;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
