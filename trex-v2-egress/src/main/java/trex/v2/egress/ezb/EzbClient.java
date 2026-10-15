package trex.v2.egress.ezb;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import trex.v2.log.Json;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The ezbookkeeping HTTP API (V2-SPEC.md §11.2). Everything is under {@code /api/v1/} and
 * {@code .json}; the token is a Bearer API token, never a flag (a flag is visible in {@code ps}).
 *
 * <p>Every write carries {@code X-Timezone-Name} and {@code X-Timezone-Offset} or the instance
 * refuses it ({@code 200008}); the offset comes from the view's zone's rules, not from a constant.
 *
 * <p>Retries are for the transient only: a dropped connection, a 5xx or a 429 (§11.2 Failure,
 * mirroring {@code FireflyClient}). A 4xx is ezbookkeeping saying the request is wrong, and is a
 * named {@link IllegalStateException} carrying the route and the body.
 */
public final class EzbClient {

    private static final Logger log = LoggerFactory.getLogger(EzbClient.class);

    /** ezbookkeeping's "nothing will be updated": a modify whose payload equals the current state. */
    private static final int NOTHING_TO_UPDATE = 200004;

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();

    private final URI base;
    private final String token;
    private final ZoneId zone;
    private final Retry retry;

    public EzbClient(String baseUrl, String token, ZoneId zone) {
        this(baseUrl, token, zone, Retry.NONE);
    }

    public EzbClient(String baseUrl, String token, ZoneId zone, Retry retry) {
        String trimmed = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.base = URI.create(trimmed);
        this.token = token;
        this.zone = zone;
        this.retry = retry;
    }

    /** Retry policy, identical in shape to {@code FireflyClient.Retry}: attempts, capped backoff, jitter. */
    public record Retry(int attempts, long baseMs, long maxMs) {

        public static final Retry NONE = new Retry(1, 0, 0);

        public Retry {
            if (attempts < 1) {
                throw new IllegalArgumentException("--retries must be at least 1");
            }
        }

        long waitFor(int attempt) {
            long grow = Math.min(baseMs << Math.min(attempt - 1, 20), maxMs);
            return grow / 2 + (long) (Math.random() * (grow / 2.0 + 1));
        }
    }

    /** A request that failed on every attempt. Terminal: the pass stops here (§11.2). */
    public static final class Unreachable extends IOException {
        public Unreachable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** An ezbookkeeping account, keyed by name. {@code balance} is in hundredths (its unit). */
    public record AccountInfo(String id, String name, long balance) {}

    /** Login credentials for the one-call clear (§11.2). Without them the view clears by id. */
    public record Credentials(String user, String password) {}

    /** One ezbookkeeping category; {@code categoryType} is its <em>category</em> enum (1/2/3). */
    public record CategoryInfo(String id, String name, int categoryType, String parentId) {}

    /** Accounts keyed by display name — the view's ref resolves through {@code EzbMap} to the name. */
    public Map<String, AccountInfo> accountsByName() throws IOException, InterruptedException {
        Map<String, AccountInfo> out = new LinkedHashMap<>();
        for (JsonNode a : result("/api/v1/accounts/list.json")) {
            out.put(a.path("name").asText(), new AccountInfo(a.path("id").asText(),
                a.path("name").asText(), a.path("balance").asLong()));
        }
        return out;
    }

    public String addAccount(String name, int category, String currency) throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("category", category);
        body.put("type", 1);
        body.put("icon", "1");
        body.put("color", "607D8B");
        body.put("currency", currency);
        return posted("/api/v1/accounts/add.json", body).path("id").asText();
    }

    /**
     * The category tree, flattened: every primary (parentId {@code "0"}) and every child, each
     * carrying its category type. A row may only name a child, so the view resolves one per
     * (type, trex category) from here.
     */
    public List<CategoryInfo> categories() throws IOException, InterruptedException {
        JsonNode result = result("/api/v1/transaction/categories/list.json");
        List<CategoryInfo> out = new ArrayList<>();
        for (Iterator<Map.Entry<String, JsonNode>> types = result.fields(); types.hasNext(); ) {
            Map.Entry<String, JsonNode> type = types.next();
            int categoryType = Integer.parseInt(type.getKey());
            for (JsonNode primary : type.getValue()) {
                out.add(new CategoryInfo(primary.path("id").asText(), primary.path("name").asText(),
                    categoryType, primary.path("parentId").asText("0")));
                for (JsonNode sub : primary.path("subCategories")) {
                    out.add(new CategoryInfo(sub.path("id").asText(), sub.path("name").asText(),
                        categoryType, sub.path("parentId").asText()));
                }
            }
        }
        return out;
    }

    public String addCategory(String name, int categoryType, String parentId)
            throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("type", categoryType);
        body.put("parentId", parentId);
        body.put("icon", "1");
        body.put("color", "607D8B");
        return posted("/api/v1/transaction/categories/add.json", body).path("id").asText();
    }

    /**
     * Re-parent a category, keeping its id, so a saved exploration survives the move (§11.2).
     * {@code modify} is a full replace: the name, icon/iconType, colour, comment and hidden flag go
     * with the new parent (we send the same defaults {@code addCategory} uses). ezbookkeeping takes
     * the category <em>type</em> from the existing row, not the request, so the {@code type} we send
     * is ignored — it is there for symmetry with the add route.
     *
     * <p>A {@code modify} whose payload equals the current state is not a 200: the instance answers
     * {@code 400 200004 "nothing will be updated"}. That specific code is a success here; the caller
     * also skips the call when the parent already matches, so the no-op is rare anyway.
     */
    public void moveCategory(String id, String name, int categoryType, String parentId)
            throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("name", name);
        body.put("type", categoryType);
        body.put("parentId", parentId);
        body.put("icon", "1");
        body.put("color", "607D8B");
        HttpResponse<String> r = send("POST", "/api/v1/transaction/categories/modify.json", bytes(body));
        if (r.statusCode() / 100 == 2) {
            return;
        }
        if (errorCode(r) == NOTHING_TO_UPDATE) {
            log.debug("categories/modify {} was already in place", id);
            return;
        }
        throw new IllegalStateException("ezbookkeeping POST /api/v1/transaction/categories/modify.json answered "
            + r.statusCode() + ": " + body(r));
    }

    /** Delete a duplicate category leaf — the one place a category is ever removed (§11.2). */
    public void deleteCategory(String id) throws IOException, InterruptedException {
        send("/api/v1/transaction/categories/delete.json", Map.of("id", id));
    }

    /** Every transaction id, for the clear (V2-SPEC.md §11.2). */
    public List<String> transactionIds() throws IOException, InterruptedException {
        List<String> out = new ArrayList<>();
        for (JsonNode t : result("/api/v1/transactions/list/all.json")) {
            out.add(t.path("id").asText());
        }
        return out;
    }

    /** Delete one transaction — no password, unlike {@code batch_delete}/{@code data/clear} (§11.2). */
    public void deleteTransaction(String id) throws IOException, InterruptedException {
        send("/api/v1/transactions/delete.json", Map.of("id", id));
    }

    /**
     * The one-call clear ({@code data/clear/transactions}, §11.2): mint a login token from the
     * credentials, then clear. It keeps accounts, categories, tags and saved explorations and deletes
     * only transactions — the same view as the per-id delete, in one request instead of thousands.
     * The API token alone cannot reach this route, so it is the fast mode's reason to exist.
     */
    public void clearAll(Credentials credentials) throws IOException, InterruptedException {
        String session = authorize(credentials.user(), credentials.password());
        HttpResponse<String> r = send("POST", "/api/v1/data/clear/transactions.json",
            bytes(Map.of("password", credentials.password())), session);
        if (r.statusCode() / 100 != 2) {
            throw new IllegalStateException("ezbookkeeping data/clear/transactions answered "
                + r.statusCode() + ": " + body(r));
        }
    }

    /**
     * A normal login token from {@code /api/authorize.json} — outside {@code /api/v1} — whose body is
     * {@code {loginName, password}} ({@code username} silently 401s). Needed only for the fast clear;
     * the token is never persisted.
     */
    private String authorize(String user, String password) throws IOException, InterruptedException {
        HttpResponse<String> r = send("POST", "/api/authorize.json",
            bytes(Map.of("loginName", user, "password", password)), null);
        if (r.statusCode() / 100 != 2) {
            throw new IllegalStateException("ezbookkeeping /api/authorize.json answered "
                + r.statusCode() + ": " + body(r));
        }
        JsonNode result = Json.mapper().readTree(r.body()).path("result");
        String token = result.path("token").asText("");
        if (token.isEmpty()) {
            throw new IllegalStateException("ezbookkeeping login returned no token; check EZB_USER/EZB_PASSWORD");
        }
        return token;
    }

    public void importRows(String clientSessionId, List<Map<String, Object>> rows)
            throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("clientSessionId", clientSessionId);
        body.put("transactions", rows);
        send("/api/v1/transactions/import.json", body);
    }

    /** {@code result} is null (or empty) when nothing is importing; the import is asynchronous. */
    public boolean importIdle() throws IOException, InterruptedException {
        JsonNode result = get("/api/v1/transactions/import/process.json").path("result");
        return result.isMissingNode() || result.isNull() || (result.isObject() && result.isEmpty());
    }

    private JsonNode get(String path) throws IOException, InterruptedException {
        HttpResponse<String> r = send("GET", path, null);
        if (r.statusCode() / 100 != 2) {
            throw new IllegalStateException("ezbookkeeping GET " + path + " answered " + r.statusCode()
                + ": " + body(r));
        }
        return Json.mapper().readTree(r.body());
    }

    private JsonNode result(String path) throws IOException, InterruptedException {
        return get(path).path("result");
    }

    /** POST and return {@code result}; a non-retried non-2xx is a named failure. */
    private JsonNode posted(String path, Object body) throws IOException, InterruptedException {
        HttpResponse<String> r = send("POST", path, bytes(body));
        if (r.statusCode() / 100 != 2) {
            throw new IllegalStateException("ezbookkeeping POST " + path + " answered " + r.statusCode()
                + ": " + body(r));
        }
        return Json.mapper().readTree(r.body() == null || r.body().isEmpty() ? "{}" : r.body()).path("result");
    }

    private void send(String path, Object body) throws IOException, InterruptedException {
        HttpResponse<String> r = send("POST", path, bytes(body));
        if (r.statusCode() / 100 != 2) {
            throw new IllegalStateException("ezbookkeeping POST " + path + " answered " + r.statusCode()
                + ": " + body(r));
        }
    }

    private static byte[] bytes(Object body) {
        try {
            return Json.mapper().writeValueAsBytes(body);
        } catch (IOException e) {
            throw new IllegalStateException("cannot serialise the request body", e);
        }
    }

    private static String body(HttpResponse<String> r) {
        String text = r.body();
        return text == null ? "" : text.substring(0, Math.min(200, text.length()));
    }

    /** The ezbookkeeping {@code errorCode} a failure body carries, or 0 when it names none. */
    private static int errorCode(HttpResponse<String> r) {
        try {
            return Json.mapper().readTree(r.body() == null ? "{}" : r.body()).path("errorCode").asInt(0);
        } catch (IOException e) {
            return 0;
        }
    }

    /** The zone's offset in minutes from its own rules — never a constant, never "now" pinned once. */
    private int offsetMinutes() {
        return (int) (zone.getRules().getOffset(Instant.now()).getTotalSeconds() / 60);
    }

    private HttpResponse<String> send(String method, String path, byte[] body)
            throws IOException, InterruptedException {
        return send(method, path, body, token);
    }

    /**
     * @param bearer the token to send, or null for no {@code Authorization} header (the login route
     *               needs none; {@code data/clear} needs the minted session token, not the API token)
     */
    private HttpResponse<String> send(String method, String path, byte[] body, String bearer)
            throws IOException, InterruptedException {
        // Every call carries the timezone headers — the instance reads them for reads too, and
        // refuses a request without them (§11.2).
        HttpRequest.Builder b = HttpRequest.newBuilder(base.resolve(path))
            .timeout(Duration.ofSeconds(60))
            .header("Accept", "application/json")
            .header("X-Timezone-Name", zone.getId())
            .header("X-Timezone-Offset", String.valueOf(offsetMinutes()));
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }
        if (body == null) {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            b.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofByteArray(body));
        }
        HttpRequest request = b.build();
        IOException last = null;
        for (int attempt = 1; attempt <= retry.attempts(); attempt++) {
            HttpResponse<String> r = null;
            try {
                r = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (!transientStatus(r.statusCode())) {
                    log.debug("{} {} -> {}", method, path, r.statusCode());
                    return r;
                }
                last = new IOException(method + " " + path + " -> " + r.statusCode());
            } catch (IOException e) {
                last = e;
            }
            if (attempt == retry.attempts()) {
                break;
            }
            long backoff = retry.waitFor(attempt);
            long wait = r == null ? backoff
                : r.headers().firstValue("Retry-After").map(EzbClient::seconds).orElse(backoff);
            log.warn("{} {} failed ({}), attempt {}/{}; retrying in {}ms",
                method, path, last.getMessage(), attempt, retry.attempts(), wait);
            Thread.sleep(wait);
        }
        throw new Unreachable(method + " " + path + " failed on all " + retry.attempts()
            + " attempt(s): " + last.getMessage(), last);
    }

    /** Worth trying again: the instance is restarting, overloaded, or the connection dropped. */
    static boolean transientStatus(int status) {
        return status == 429 || status / 100 == 5;
    }

    private static long seconds(String header) {
        try {
            return Math.max(0, Long.parseLong(header.strip()) * 1000);
        } catch (NumberFormatException e) {
            return 1000;                   // a date-form Retry-After; a second is close enough
        }
    }
}
