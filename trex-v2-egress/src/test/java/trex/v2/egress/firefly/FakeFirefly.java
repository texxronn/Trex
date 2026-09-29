package trex.v2.egress.firefly;

import com.sun.net.httpserver.HttpServer;
import trex.v2.log.Json;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A small in-memory Firefly that reproduces the behaviour that matters: duplicate external_id is a
 * 422 naming the existing group, a PUT replaces the split as given, and {@code category_id} comes
 * back beside {@code category_name}. Not a happy-path-only stub.
 */
final class FakeFirefly implements AutoCloseable {

    record Group(String id, Map<String, Object> split) {}

    private final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    private final Map<String, Group> byId = new ConcurrentHashMap<>();
    private final Map<String, String> idByExternal = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);

    /** Forced statuses, popped before normal handling (to exercise the retry taxonomy). */
    final Deque<Integer> forcedStatuses = new ArrayDeque<>();
    final AtomicInteger posts = new AtomicInteger();
    final AtomicInteger gets = new AtomicInteger();
    final AtomicInteger puts = new AtomicInteger();
    final AtomicInteger deletes = new AtomicInteger();
    final AtomicInteger attempts = new AtomicInteger();
    volatile Map<String, Object> lastPutBody;

    FakeFirefly() throws Exception {
        server.createContext("/api/v1/transactions", exchange -> {
            attempts.incrementAndGet();
            try {
                if (!forcedStatuses.isEmpty()) {
                    respond(exchange, forcedStatuses.pop(), "{}");
                    return;
                }
                String method = exchange.getRequestMethod();
                if (method.equals("POST")) {
                    posts.incrementAndGet();
                    create(exchange);
                } else if (method.equals("GET")) {
                    list(exchange);
                } else {
                    respond(exchange, 405, "{}");
                }
            } catch (Exception e) {
                respond(exchange, 500, "{\"message\":\"" + e + "\"}");
            }
        });
        server.createContext("/api/v1/transactions/", exchange -> {
            attempts.incrementAndGet();
            try {
                if (!forcedStatuses.isEmpty()) {
                    respond(exchange, forcedStatuses.pop(), "{}");
                    return;
                }
                String id = exchange.getRequestURI().getPath().substring("/api/v1/transactions/".length());
                switch (exchange.getRequestMethod()) {
                    case "GET" -> get(exchange, id);
                    case "PUT" -> put(exchange, id);
                    case "DELETE" -> {
                        deletes.incrementAndGet();
                        Group removed = byId.remove(id);
                        if (removed != null) {
                            idByExternal.remove(removed.split().get("external_id"));
                        }
                        respond(exchange, 204, "");
                    }
                    default -> respond(exchange, 405, "{}");
                }
            } catch (Exception e) {
                respond(exchange, 500, "{\"message\":\"" + e + "\"}");
            }
        });
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    Map<String, Group> groups() {
        return byId;
    }

    @SuppressWarnings("unchecked")
    private void create(com.sun.net.httpserver.HttpExchange exchange) throws Exception {
        Map<String, Object> body = Json.mapper().readValue(exchange.getRequestBody().readAllBytes(), Map.class);
        List<Map<String, Object>> splits = (List<Map<String, Object>>) body.get("transactions");
        Map<String, Object> split = splits.getFirst();
        String external = (String) split.get("external_id");
        String existing = idByExternal.get(external);
        if (existing != null) {
            respond(exchange, 422, "{\"message\":\"Duplicate of transaction #" + existing + ".\"}");
            return;
        }
        String id = String.valueOf(nextId.getAndIncrement());
        byId.put(id, new Group(id, split));
        idByExternal.put(external, id);
        respond(exchange, 200, "{\"data\":{\"id\":\"" + id + "\"}}");
    }

    private void list(com.sun.net.httpserver.HttpExchange exchange) throws Exception {
        gets.incrementAndGet();
        List<Map<String, Object>> data = new ArrayList<>();
        for (Group group : byId.values()) {
            data.add(map("id", group.id(), "attributes", map("group_title", null,
                "transactions", List.of(group.split()))));
        }
        Map<String, Object> response = map("data", data,
            "meta", map("pagination", map("total_pages", 1)));
        respond(exchange, 200, Json.mapper().writeValueAsString(response));
    }

    private void get(com.sun.net.httpserver.HttpExchange exchange, String id) throws Exception {
        gets.incrementAndGet();
        Group group = byId.get(id);
        if (group == null) {
            respond(exchange, 404, "{}");
            return;
        }
        respond(exchange, 200, Json.mapper().writeValueAsString(map("data", map("id", id,
            "attributes", map("group_title", null, "transactions", List.of(group.split()))))));
    }

    @SuppressWarnings("unchecked")
    private void put(com.sun.net.httpserver.HttpExchange exchange, String id) throws Exception {
        puts.incrementAndGet();
        Map<String, Object> body = Json.mapper().readValue(exchange.getRequestBody().readAllBytes(), Map.class);
        lastPutBody = body;
        Group group = byId.get(id);
        if (group == null) {
            respond(exchange, 404, "{}");
            return;
        }
        List<Map<String, Object>> splits = (List<Map<String, Object>>) body.get("transactions");
        byId.put(id, new Group(id, splits.getFirst()));
        respond(exchange, 200, "{\"data\":{\"id\":\"" + id + "\"}}");
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
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
