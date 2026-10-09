package trex.v2.egress.firefly;

import com.sun.net.httpserver.HttpExchange;
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
 * 422 naming the existing group; GET returns a group's {@code group_title} and every split, so a
 * read-modify-write must return them; {@code category_id} comes back beside {@code category_name};
 * a group can hold more than one split (a hand-split). Not a happy-path-only stub.
 */
final class FakeFirefly implements AutoCloseable {

    record Group(String id, String groupTitle, List<Map<String, Object>> splits) {}

    private final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    private final Map<String, Group> byId = new ConcurrentHashMap<>();
    private final Map<String, String> idByExternal = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);

    /** Accounts the instance already has, keyed by name; and the bodies used to create new ones. */
    final Map<String, Map<String, Object>> accounts = new ConcurrentHashMap<>();
    final List<Map<String, Object>> createdAccounts = new ArrayList<>();

    /** Categories keyed by name; and the create/annotate calls made. */
    final Map<String, String> categoryNotes = new ConcurrentHashMap<>();
    final List<String> createdCategories = new ArrayList<>();
    final List<String> annotatedCategories = new ArrayList<>();

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
                if (exchange.getRequestMethod().equals("POST")) {
                    posts.incrementAndGet();
                    create(exchange);
                } else if (exchange.getRequestMethod().equals("GET")) {
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
                        if (removed == null) {
                            respond(exchange, 404, "{\"message\":\"Resource not found\"}");
                            return;
                        }
                        removed.splits().forEach(s -> idByExternal.remove(s.get("external_id")));
                        respond(exchange, 204, "");
                    }
                    default -> respond(exchange, 405, "{}");
                }
            } catch (Exception e) {
                respond(exchange, 500, "{\"message\":\"" + e + "\"}");
            }
        });
        server.createContext("/api/v1/accounts", exchange -> {
            try {
                if (exchange.getRequestMethod().equals("POST")) {
                    Map<String, Object> body = read(exchange);
                    createdAccounts.add(body);
                    String id = String.valueOf(nextId.getAndIncrement());
                    accounts.put((String) body.get("name"), map("id", id, "type", body.getOrDefault("type", "asset")));
                    respond(exchange, 200, "{\"data\":{\"id\":\"" + id + "\"}}");
                } else {
                    String type = query(exchange, "type");
                    List<Map<String, Object>> data = new ArrayList<>();
                    accounts.forEach((name, a) -> {
                        if (type == null || type.equals(a.get("type"))) {
                            data.add(map("id", a.get("id"), "attributes", map("name", name, "type", a.get("type"),
                                "currency_code", "AUD", "current_balance", "0")));
                        }
                    });
                    respond(exchange, 200, Json.mapper().writeValueAsString(map("data", data,
                        "meta", map("pagination", map("total_pages", 1)))));
                }
            } catch (Exception e) {
                respond(exchange, 500, "{\"message\":\"" + e + "\"}");
            }
        });
        server.createContext("/api/v1/categories", exchange -> {
            try {
                if (exchange.getRequestMethod().equals("POST")) {
                    Map<String, Object> body = read(exchange);
                    String name = (String) body.get("name");
                    createdCategories.add(name);
                    categoryNotes.put(name, body.get("notes") == null ? "" : String.valueOf(body.get("notes")));
                    respond(exchange, 200, "{\"data\":{\"id\":\"9\"}}");
                } else {
                    List<Map<String, Object>> data = new ArrayList<>();
                    categoryNotes.forEach((name, notes) -> data.add(map("id", "9", "attributes",
                        map("name", name, "notes", notes))));
                    respond(exchange, 200, Json.mapper().writeValueAsString(map("data", data,
                        "meta", map("pagination", map("total_pages", 1)))));
                }
            } catch (Exception e) {
                respond(exchange, 500, "{\"message\":\"" + e + "\"}");
            }
        });
        server.createContext("/api/v1/categories/", exchange -> {
            try {
                String id = exchange.getRequestURI().getPath().substring("/api/v1/categories/".length());
                Map<String, Object> body = read(exchange);
                String notes = String.valueOf(body.getOrDefault("notes", ""));
                categoryNotes.replaceAll((name, value) -> value.isEmpty() ? notes : value);
                annotatedCategories.add(id);
                respond(exchange, 200, "{\"data\":{\"id\":\"" + id + "\"}}");
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

    /** Seed a group as if it were already projected and hand-edited (multi-split supported). */
    String seedGroup(String externalId, String groupTitle, List<Map<String, Object>> splits) {
        String id = String.valueOf(nextId.getAndIncrement());
        byId.put(id, new Group(id, groupTitle, new ArrayList<>(splits)));
        idByExternal.put(externalId, id);
        return id;
    }

    @SuppressWarnings("unchecked")
    private void create(HttpExchange exchange) throws Exception {
        Map<String, Object> body = read(exchange);
        List<Map<String, Object>> splits = (List<Map<String, Object>>) body.get("transactions");
        Map<String, Object> split = splits.getFirst();
        String external = (String) split.get("external_id");
        String existing = idByExternal.get(external);
        if (existing != null) {
            respond(exchange, 422, "{\"message\":\"Duplicate of transaction #" + existing + ".\"}");
            return;
        }
        String id = String.valueOf(nextId.getAndIncrement());
        byId.put(id, new Group(id, null, List.of(split)));
        idByExternal.put(external, id);
        respond(exchange, 200, "{\"data\":{\"id\":\"" + id + "\"}}");
    }

    private void list(HttpExchange exchange) throws Exception {
        gets.incrementAndGet();
        List<Map<String, Object>> data = new ArrayList<>();
        for (Group group : byId.values()) {
            data.add(map("id", group.id(), "attributes",
                map("group_title", group.groupTitle(), "transactions", group.splits())));
        }
        respond(exchange, 200, Json.mapper().writeValueAsString(map("data", data,
            "meta", map("pagination", map("total_pages", 1)))));
    }

    private void get(HttpExchange exchange, String id) throws Exception {
        gets.incrementAndGet();
        Group group = byId.get(id);
        if (group == null) {
            respond(exchange, 404, "{}");
            return;
        }
        respond(exchange, 200, Json.mapper().writeValueAsString(map("data", map("id", id, "attributes",
            map("group_title", group.groupTitle(), "transactions", group.splits())))));
    }

    @SuppressWarnings("unchecked")
    private void put(HttpExchange exchange, String id) throws Exception {
        puts.incrementAndGet();
        Map<String, Object> body = read(exchange);
        lastPutBody = body;
        Group group = byId.get(id);
        if (group == null) {
            respond(exchange, 404, "{}");
            return;
        }
        List<Map<String, Object>> splits = (List<Map<String, Object>>) body.get("transactions");
        byId.put(id, new Group(id, (String) body.get("group_title"), new ArrayList<>(splits)));
        respond(exchange, 200, "{\"data\":{\"id\":\"" + id + "\"}}");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> read(HttpExchange exchange) throws Exception {
        return Json.mapper().readValue(exchange.getRequestBody().readAllBytes(), Map.class);
    }

    private static String query(HttpExchange exchange, String key) {
        String q = exchange.getRequestURI().getQuery();
        if (q == null) {
            return null;
        }
        for (String pair : q.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(key)) {
                return pair.substring(eq + 1);
            }
        }
        return null;
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            out.put((String) kv[i], kv[i + 1]);
        }
        return out;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws java.io.IOException {
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
