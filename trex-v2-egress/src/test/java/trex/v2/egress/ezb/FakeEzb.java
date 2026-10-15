package trex.v2.egress.ezb;

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
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A small in-memory ezbookkeeping that reproduces the behaviour that matters (V2-SPEC.md §11.2): an
 * account and a category are created on demand and keep their id; a transaction is a row whose
 * account balance is derived by type (a type-1 row is signed, income adds, expense subtracts,
 * transfer moves payer to payee); the clear lists ids and deletes one; the import is asynchronous
 * with a process route; every write carries the timezone headers. Not a happy-path-only stub: the
 * client test forces statuses and the view test forces a short import.
 */
final class FakeEzb implements AutoCloseable {

    record Account(String id, String name, int category, String currency) {}

    record Category(String id, String name, int type, String parentId) {}

    private final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    private final AtomicInteger nextId = new AtomicInteger(1);

    private final Map<String, Account> accounts = new ConcurrentHashMap<>();
    private final List<Category> categories = new CopyOnWriteArrayList<>();
    private final List<Map<String, Object>> transactions = new CopyOnWriteArrayList<>();

    /** What the view created, for assertions about reuse. */
    final List<String> createdAccountNames = new CopyOnWriteArrayList<>();
    final List<String> createdCategoryNames = new CopyOnWriteArrayList<>();

    /** Every clientSessionId seen on an import, in order (the fresh-id guard, §11.2). */
    final List<String> clientSessionIds = new CopyOnWriteArrayList<>();

    /** The headers of every write, and the latest, so a test can assert the timezone pair. */
    final List<Map<String, String>> writeHeaders = new CopyOnWriteArrayList<>();
    volatile Map<String, String> lastWriteHeaders = Map.of();

    /** The headers of the most recent request of ANY method — the timezone pair rides reads too. */
    volatile Map<String, String> lastHeaders = Map.of();

    /** A repeated clientSessionId is a repeat submission: the import is dropped (measured on 2.0.1). */
    private final java.util.Set<String> seenSessions = ConcurrentHashMap.newKeySet();
    final AtomicInteger duplicateImports = new AtomicInteger();

    /** The login the fast clear mints a session token from; a wrong pair is refused. */
    volatile String loginName = "ron";
    volatile String loginPassword = "secret";
    final List<String> sessionTokens = new CopyOnWriteArrayList<>();
    final AtomicInteger authorizeCalls = new AtomicInteger();
    final AtomicInteger dataClearCalls = new AtomicInteger();

    final Deque<Integer> forcedStatuses = new ArrayDeque<>();
    final AtomicInteger attempts = new AtomicInteger();
    final AtomicInteger gets = new AtomicInteger();
    final AtomicInteger importProcessGets = new AtomicInteger();
    final AtomicInteger deletes = new AtomicInteger();
    /** Category re-parents and duplicate-leaf deletes (§11.2), so a test can tell a move from a create. */
    final AtomicInteger categoryMoves = new AtomicInteger();
    final AtomicInteger categoryDeletes = new AtomicInteger();

    volatile boolean importInProgress;
    /** One short import: the next import drops this many trailing rows, then forgets (test-only). */
    volatile int dropRows;

    FakeEzb() throws Exception {
        server.createContext("/api/v1/", exchange -> {
            attempts.incrementAndGet();
            try {
                if (!forcedStatuses.isEmpty()) {
                    respond(exchange, forcedStatuses.pop(), "{\"message\":\"forced\"}");
                    return;
                }
                String path = exchange.getRequestURI().getPath();
                String method = exchange.getRequestMethod();
                Map<String, String> headers = new TreeMap<>();
                exchange.getRequestHeaders().forEach((k, v) ->
                    headers.put(k.toLowerCase(Locale.ROOT), String.join(",", v)));
                lastHeaders = headers;
                if (method.equals("POST")) {
                    lastWriteHeaders = headers;
                    writeHeaders.add(headers);
                }
                switch (path) {
                    case "/api/v1/accounts/list.json" -> accountsList(exchange);
                    case "/api/v1/accounts/add.json" -> accountsAdd(exchange);
                    case "/api/v1/transaction/categories/list.json" -> categoriesList(exchange);
                    case "/api/v1/transaction/categories/add.json" -> categoriesAdd(exchange);
                    case "/api/v1/transaction/categories/modify.json" -> categoriesModify(exchange);
                    case "/api/v1/transaction/categories/delete.json" -> categoriesDelete(exchange);
                    case "/api/v1/transactions/list/all.json" -> transactionsList(exchange);
                    case "/api/v1/transactions/delete.json" -> transactionsDelete(exchange);
                    case "/api/v1/data/clear/transactions.json" -> dataClear(exchange);
                    case "/api/v1/transactions/import.json" -> transactionsImport(exchange);
                    case "/api/v1/transactions/import/process.json" -> importProcess(exchange);
                    default -> respond(exchange, 404, "{\"message\":\"no route " + path + "\"}");
                }
            } catch (Exception e) {
                respond(exchange, 500, "{\"message\":\"" + e + "\"}");
            }
        });
        // The login route lives OUTSIDE /api/v1; {loginName, password} -> a session token.
        server.createContext("/api/authorize.json", exchange -> {
            try {
                authorize(exchange);
            } catch (Exception e) {
                respond(exchange, 500, "{\"message\":\"" + e + "\"}");
            }
        });
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    List<Account> accounts() {
        return new ArrayList<>(accounts.values());
    }

    Map<String, String> accountIdByName() {
        Map<String, String> out = new TreeMap<>();
        accounts.values().forEach(a -> out.put(a.name(), a.id()));
        return out;
    }

    List<Category> categories() {
        return new ArrayList<>(categories);
    }

    List<Map<String, Object>> transactions() {
        return new ArrayList<>(transactions);
    }

    List<String> transactionIds() {
        return transactions.stream().map(t -> String.valueOf(t.get("id"))).toList();
    }

    /** Seed an account with a fixed id, as if it were already in a saved exploration. */
    void seedAccount(String id, String name, int category, String currency) {
        accounts.put(id, new Account(id, name, category, currency));
    }

    /** Seed a category with a fixed id (a "saved exploration" id the view must reuse). */
    void seedCategory(String id, String name, int type, String parentId) {
        categories.add(new Category(id, name, type, parentId));
    }

    /** Move an account's balance out from under the view by posting an extra signed row. */
    void drift(String accountId, long cents) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", "drift-" + nextId.getAndIncrement());
        row.put("type", 1);
        row.put("sourceAccountId", accountId);
        row.put("sourceAmount", cents);
        transactions.add(row);
    }

    long balance(String accountId) {
        long balance = 0;
        for (Map<String, Object> row : transactions) {
            int type = asInt(row.get("type"));
            Object source = row.get("sourceAccountId");
            Object destination = row.get("destinationAccountId");
            switch (type) {
                case 1, 2 -> { if (accountId.equals(source)) balance += asLong(row.get("sourceAmount")); }
                case 3 -> { if (accountId.equals(source)) balance -= asLong(row.get("sourceAmount")); }
                case 4 -> {
                    if (accountId.equals(source)) balance -= asLong(row.get("sourceAmount"));
                    if (accountId.equals(destination)) balance += asLong(row.get("destinationAmount"));
                }
                default -> { }
            }
        }
        return balance;
    }

    private void accountsList(HttpExchange exchange) throws Exception {
        gets.incrementAndGet();
        List<Map<String, Object>> result = new ArrayList<>();
        accounts.values().forEach(a -> result.add(map("id", a.id(), "name", a.name(),
            "balance", String.valueOf(balance(a.id())), "category", a.category())));
        respond(exchange, 200, Json.mapper().writeValueAsString(map("result", result)));
    }

    private void accountsAdd(HttpExchange exchange) throws Exception {
        Map<String, Object> body = read(exchange);
        String id = String.valueOf(nextId.getAndIncrement());
        String name = String.valueOf(body.get("name"));
        accounts.put(id, new Account(id, name, asInt(body.get("category")),
            String.valueOf(body.getOrDefault("currency", "AUD"))));
        createdAccountNames.add(name);
        respond(exchange, 200, "{\"result\":{\"id\":\"" + id + "\"}}");
    }

    private void categoriesList(HttpExchange exchange) throws Exception {
        gets.incrementAndGet();
        Map<String, Object> result = new LinkedHashMap<>();
        for (int type = 1; type <= 3; type++) {
            List<Map<String, Object>> primaries = new ArrayList<>();
            for (Category primary : categories) {
                if (primary.type() != type || !"0".equals(primary.parentId())) {
                    continue;
                }
                List<Map<String, Object>> children = new ArrayList<>();
                for (Category child : categories) {
                    if (child.type() == type && primary.id().equals(child.parentId())) {
                        children.add(map("id", child.id(), "name", child.name(), "parentId", child.parentId()));
                    }
                }
                primaries.add(map("id", primary.id(), "name", primary.name(), "parentId", "0",
                    "subCategories", children));
            }
            result.put(String.valueOf(type), primaries);
        }
        respond(exchange, 200, Json.mapper().writeValueAsString(map("result", result)));
    }

    private void categoriesAdd(HttpExchange exchange) throws Exception {
        Map<String, Object> body = read(exchange);
        String id = String.valueOf(nextId.getAndIncrement());
        String name = String.valueOf(body.get("name"));
        categories.add(new Category(id, name, asInt(body.get("type")),
            String.valueOf(body.get("parentId"))));
        createdCategoryNames.add(name);
        respond(exchange, 200, "{\"result\":{\"id\":\"" + id + "\"}}");
    }

    /**
     * {@code categories/modify.json} re-parents an existing leaf and keeps its id. The real instance
     * answers {@code 400 200004 "nothing will be updated"} when the payload equals the current state,
     * so the fake does too (measured on 2.0.1).
     */
    private void categoriesModify(HttpExchange exchange) throws Exception {
        Map<String, Object> body = read(exchange);
        String id = String.valueOf(body.get("id"));
        Category updated = new Category(id, String.valueOf(body.get("name")), asInt(body.get("type")),
            String.valueOf(body.get("parentId")));
        for (int i = 0; i < categories.size(); i++) {
            if (!categories.get(i).id().equals(id)) {
                continue;
            }
            if (categories.get(i).equals(updated)) {
                respond(exchange, 400,
                    "{\"success\":false,\"errorCode\":200004,\"errorMessage\":\"nothing will be updated\"}");
                return;
            }
            categories.set(i, updated);
            categoryMoves.incrementAndGet();
            respond(exchange, 200, "{\"result\":true}");
            return;
        }
        respond(exchange, 400, "{\"success\":false,\"errorCode\":200005,\"errorMessage\":\"category not found\"}");
    }

    private void categoriesDelete(HttpExchange exchange) throws Exception {
        Map<String, Object> body = read(exchange);
        String id = String.valueOf(body.get("id"));
        categories.removeIf(c -> c.id().equals(id));
        categoryDeletes.incrementAndGet();
        respond(exchange, 200, "{\"result\":true}");
    }

    private void transactionsList(HttpExchange exchange) throws Exception {
        gets.incrementAndGet();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : transactions) {
            result.add(map("id", row.get("id")));
        }
        respond(exchange, 200, Json.mapper().writeValueAsString(map("result", result)));
    }

    private void transactionsDelete(HttpExchange exchange) throws Exception {
        Map<String, Object> body = read(exchange);
        String id = String.valueOf(body.get("id"));
        transactions.removeIf(t -> id.equals(String.valueOf(t.get("id"))));
        deletes.incrementAndGet();
        respond(exchange, 200, "{\"result\":true}");
    }

    /** The one-call clear: a valid session token AND the matching password, then every row goes. */
    private void dataClear(HttpExchange exchange) throws Exception {        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        String bearer = auth == null ? "" : auth.replaceFirst("^Bearer\\s+", "");
        Map<String, Object> body = read(exchange);
        boolean okSession = sessionTokens.contains(bearer);
        boolean okPassword = loginPassword.equals(String.valueOf(body.get("password")));
        if (!okSession || !okPassword) {
            respond(exchange, 400,
                "{\"success\":false,\"errorCode\":202007,\"errorMessage\":\"token is invalid\"}");
            return;
        }
        dataClearCalls.incrementAndGet();
        transactions.clear();
        respond(exchange, 200, "{\"result\":true,\"success\":true}");
    }

    /** {@code POST /api/authorize.json}: the login the fast clear mints its session token from. */
    private void authorize(HttpExchange exchange) throws Exception {
        authorizeCalls.incrementAndGet();
        Map<String, Object> body = read(exchange);
        if (loginName.equals(String.valueOf(body.get("loginName")))
            && loginPassword.equals(String.valueOf(body.get("password")))) {
            String tok = "session-" + nextId.getAndIncrement();
            sessionTokens.add(tok);
            respond(exchange, 200, "{\"success\":true,\"result\":{\"token\":\"" + tok + "\"}}");
        } else {
            respond(exchange, 400,
                "{\"success\":false,\"errorCode\":202007,\"errorMessage\":\"incorrect username or password\"}");
        }
    }

    @SuppressWarnings("unchecked")
    private void transactionsImport(HttpExchange exchange) throws Exception {
        Map<String, Object> body = read(exchange);
        String session = String.valueOf(body.getOrDefault("clientSessionId", ""));
        clientSessionIds.add(session);
        if (!seenSessions.add(session)) {
            // The real duplicate checker keys on the id alone: a repeated id is a repeat submission
            // and every row in it is dropped, however different the payload (measured on 2.0.1).
            duplicateImports.incrementAndGet();
            respond(exchange, 200, "{\"result\":true}");
            return;
        }
        List<Map<String, Object>> rows = (List<Map<String, Object>>) body.getOrDefault("transactions", List.of());
        int drop = Math.min(dropRows, rows.size());
        dropRows = 0;                          // one-shot: a single short import
        int keep = rows.size() - drop;
        for (int i = 0; i < keep; i++) {
            Map<String, Object> row = new LinkedHashMap<>(rows.get(i));
            row.put("id", "t" + nextId.getAndIncrement());
            transactions.add(row);
        }
        respond(exchange, 200, "{\"result\":true}");
    }

    private void importProcess(HttpExchange exchange) throws Exception {
        importProcessGets.incrementAndGet();
        respond(exchange, 200, importInProgress ? "{\"result\":{\"total\":2,\"processed\":1}}"
            : "{\"result\":null}");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> read(HttpExchange exchange) throws Exception {
        byte[] bytes = exchange.getRequestBody().readAllBytes();
        return bytes.length == 0 ? new LinkedHashMap<>()
            : Json.mapper().readValue(bytes, Map.class);
    }

    private static long asLong(Object value) {
        return value instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(value));
    }

    private static int asInt(Object value) {
        return value instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(value));
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
