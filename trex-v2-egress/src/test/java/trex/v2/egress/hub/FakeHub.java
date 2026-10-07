package trex.v2.egress.hub;

import com.sun.net.httpserver.HttpServer;
import trex.v2.log.Json;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** A small in-memory hub: the egress's two feeds (units, projection state) and nothing else. */
public final class FakeHub implements AutoCloseable {

    private final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    public final Map<String, Map<String, Object>> projection = new ConcurrentHashMap<>();
    public volatile String configRevision = "sha256:cfg1";
    public volatile List<Map<String, Object>> units = new ArrayList<>();

    public FakeHub() throws Exception {
        server.createContext("/api/units", exchange -> respond(exchange, 200, Json.mapper().writeValueAsString(
            map("asOfN", 1, "configRevision", configRevision, "deriveVersion", "derive/1",
                "hashVersion", "statehash/1", "units", units))));
        server.createContext("/api/projection", exchange -> {
            if (exchange.getRequestMethod().equals("POST")) {
                Map<String, Object> body = Json.mapper().readValue(
                    exchange.getRequestBody().readAllBytes(), Map.class);
                boolean replace = Boolean.TRUE.equals(body.get("replace"));
                if (replace) {
                    projection.clear();
                }
                for (Object row : (List<?>) body.get("rows")) {
                    Map<String, Object> r = (Map<String, Object>) row;
                    projection.put((String) r.get("unitId"), r);
                }
                respond(exchange, 200, "{\"recorded\":true}");
            } else {
                respond(exchange, 200, Json.mapper().writeValueAsString(map("rows", new ArrayList<>(projection.values()))));
            }
        });
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public static Map<String, Object> unit(String unitId, String kind, long n, String accountRef, String toAccountRef,
                                    String date, long amount, String category, String raw, String hash) {
        return map("unitId", unitId, "unitKind", kind, "n", n, "accountRef", accountRef,
            "toAccountRef", toAccountRef, "date", date, "amount", amount, "currency", "AUD",
            "category", category, "origin", "RULE", "pairing", "EXTERNAL", "retired", false,
            "ineffective", false, "rawDescription", raw, "unitHash", hash);
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            out.put((String) kv[i], kv[i + 1]);
        }
        return out;
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws java.io.IOException {
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
