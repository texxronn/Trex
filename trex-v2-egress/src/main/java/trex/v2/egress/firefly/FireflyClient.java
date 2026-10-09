package trex.v2.egress.firefly;

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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Firefly III API (V2-PROPOSAL.md §11). The token comes from the environment and is put in a
 * header; it is never a flag (a flag is visible in {@code ps}) and never logged.
 *
 * <p>Retries are for the transient only: a dropped connection, a 5xx or a 429. A 4xx is Firefly
 * saying the request is wrong, and repeating it changes nothing but the clock (§11.7).
 */
public final class FireflyClient {

    private static final Logger log = LoggerFactory.getLogger(FireflyClient.class);

    /** Firefly caps list endpoints at 100 per page. */
    static final int PAGE = 100;

    /** "Duplicate of transaction #2." — the rejection names the group, so no search is needed. */
    private static final Pattern DUPLICATE = Pattern.compile("Duplicate of transaction #(\\d+)");

    /** A transfer's legs, written into our first notes line (Stage 5). */
    private static final Pattern LEGS = Pattern.compile("\\blegs=(\\S+)");

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();

    private final URI base;
    private final String token;
    private final Retry retry;

    public FireflyClient(String baseUrl, String token) {
        this(baseUrl, token, Retry.NONE);
    }

    public FireflyClient(String baseUrl, String token, Retry retry) {
        String trimmed = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.base = URI.create(trimmed);
        this.token = token;
        this.retry = retry;
    }

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

    /** A request that failed on every attempt. Terminal: the pass stops here (§11.7). */
    public static final class Unreachable extends IOException {
        public Unreachable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public sealed interface Result {
        record Created(String groupId) implements Result {}

        record Duplicate(String groupId) implements Result {}

        record Failed(int status, String message) implements Result {}
    }

    public String version() throws IOException, InterruptedException {
        return get("/api/v1/about").path("data").path("version").asText("unknown");
    }

    public Map<String, AccountInfo> accounts() throws IOException, InterruptedException {
        Map<String, AccountInfo> out = new LinkedHashMap<>();
        for (String type : List.of("asset", "liability")) {
            out.putAll(accountsOfType(type));
        }
        return out;
    }

    private Map<String, AccountInfo> accountsOfType(String type) throws IOException, InterruptedException {
        Map<String, AccountInfo> out = new LinkedHashMap<>();
        for (int page = 1; ; page++) {
            JsonNode body = get("/api/v1/accounts?type=" + type + "&limit=" + PAGE + "&page=" + page);
            for (JsonNode a : body.path("data")) {
                JsonNode at = a.path("attributes");
                out.put(at.path("name").asText(), new AccountInfo(
                    a.path("id").asText(), at.path("name").asText(), at.path("type").asText(),
                    at.path("currency_code").asText(null), at.path("current_balance").asText(null)));
            }
            if (page >= body.path("meta").path("pagination").path("total_pages").asInt(1)) {
                return out;
            }
        }
    }

    public record AccountInfo(String id, String name, String type, String currency, String balance) {

        public boolean isLiability() {
            return type != null && type.startsWith("liabilit");
        }
    }

    /** One transaction as Firefly currently holds it — what a rebuild and a re-tag read back. */
    public record Existing(String groupId, String externalId, String category, String projectedCategory,
                           List<String> tags, long journalN, JsonNode group) {}

    public List<Existing> allTransactions() throws IOException, InterruptedException {
        return transactions(true, true);
    }

    /**
     * Every transaction group, tagged or not and keyed by {@code external_id} or not: what
     * {@code --validate} inventories so a lost ownership or a removed {@code external_id} can be
     * named (D9/R2). The tags are kept on {@link Existing} for exactly that; a group with no
     * {@code external_id} still carries its group id, so a state row can be matched to it.
     */
    public List<Existing> inventory() throws IOException, InterruptedException {
        return transactions(false, false);
    }

    private List<Existing> transactions(boolean oursOnly, boolean requireExternal)
            throws IOException, InterruptedException {
        List<Existing> out = new ArrayList<>();
        for (int page = 1; ; page++) {
            JsonNode body = get("/api/v1/transactions?limit=" + PAGE + "&page=" + page);
            for (JsonNode g : body.path("data")) {
                JsonNode splits = g.path("attributes").path("transactions");
                if (splits.isEmpty()) {
                    continue;
                }
                JsonNode first = splits.get(0);
                String external = first.path("external_id").asText(null);
                if ((requireExternal && external == null) || (oursOnly && !isOurs(first))) {
                    continue;                  // nothing to name it by, or not ours
                }
                out.add(new Existing(g.path("id").asText(), external,
                    first.path("category_name").asText(null), tagCategory(first), tags(first),
                    journalN(first.path("notes").asText("")), g));
            }
            if (page >= body.path("meta").path("pagination").path("total_pages").asInt(1)) {
                return out;
            }
        }
    }

    private static List<String> tags(JsonNode split) {
        List<String> out = new ArrayList<>();
        split.path("tags").forEach(t -> out.add(t.asText()));
        return out;
    }

    /** Ours is the trex tag, never an external_id alone: an importer or a hand entry can carry one. */
    public static boolean isOurs(JsonNode split) {
        for (JsonNode t : split.path("tags")) {
            if (Projection.TAG.equals(t.asText())) {
                return true;
            }
        }
        return false;
    }

    /** What trex last said, read off our own tag — not Firefly's category, which you may have edited. */
    public static String tagCategory(JsonNode split) {
        for (JsonNode t : split.path("tags")) {
            String v = t.asText();
            if (v.startsWith(Projection.CATEGORY_TAG_PREFIX)) {
                return v.substring(Projection.CATEGORY_TAG_PREFIX.length());
            }
        }
        return null;
    }

    /** {@code trex n=1234 rules=…} — put there because the projection state is not allowed to be the record. */
    static long journalN(String notes) {
        Matcher m = Pattern.compile("\\btrex n=(\\d+)").matcher(notes == null ? "" : notes);
        return m.find() ? Long.parseLong(m.group(1)) : -1;
    }

    /** A transfer's legs, from our first notes line — empty for a group projected before Stage 5. */
    static List<String> legs(String notes) {
        if (notes == null) {
            return List.of();
        }
        int nl = notes.indexOf('\n');
        Matcher m = LEGS.matcher(nl < 0 ? notes : notes.substring(0, nl));
        return m.find() ? List.of(m.group(1).split(",")) : List.of();
    }

    public record CategoryInfo(String id, String name, String notes) {

        public boolean hasNotes() {
            return notes != null && !notes.isBlank();
        }
    }

    public Map<String, CategoryInfo> categories() throws IOException, InterruptedException {
        Map<String, CategoryInfo> out = new LinkedHashMap<>();
        for (int page = 1; ; page++) {
            JsonNode body = get("/api/v1/categories?limit=" + PAGE + "&page=" + page);
            for (JsonNode c : body.path("data")) {
                String name = c.path("attributes").path("name").asText();
                out.put(name, new CategoryInfo(c.path("id").asText(), name,
                    c.path("attributes").path("notes").asText(null)));
            }
            if (page >= body.path("meta").path("pagination").path("total_pages").asInt(1)) {
                return out;
            }
        }
    }

    /** Only ever called when the existing notes are empty: anything you typed there is yours. */
    public void setCategoryNotes(String id, String notes) throws IOException, InterruptedException {
        Map<String, Object> body = Map.of("notes", notes);
        HttpResponse<String> r = send("PUT", "/api/v1/categories/" + id, bytes(body));
        if (r.statusCode() / 100 != 2) {
            throw new IOException("annotating category " + id + ": " + r.statusCode() + " " + message(r.body()));
        }
    }

    public void createCategory(String name, String notes) throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        if (notes != null && !notes.isBlank()) {
            body.put("notes", notes);
        }
        HttpResponse<String> r = send("POST", "/api/v1/categories", bytes(body));
        if (r.statusCode() / 100 != 2) {
            throw new IOException("creating category \"" + name + "\": " + r.statusCode() + " " + message(r.body()));
        }
    }

    public String createAccount(String name, AccountMap.Kind kind, String currency, long opening, LocalDate asOf)
            throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("currency_code", currency);
        if (kind == AccountMap.Kind.LIABILITY) {
            body.put("type", "liability");
            body.put("liability_type", "debt");
            body.put("liability_direction", "credit");
        } else {
            body.put("type", "asset");
            body.put("account_role", "defaultAsset");
        }
        if (asOf != null) {
            body.put("opening_balance", Projection.signedAmount(opening));
            body.put("opening_balance_date", asOf.toString());
        }
        HttpResponse<String> r = send("POST", "/api/v1/accounts", bytes(body));
        if (r.statusCode() / 100 != 2) {
            throw new IOException("creating account \"" + name + "\": " + r.statusCode() + " " + message(r.body()));
        }
        return Json.mapper().readTree(r.body()).path("data").path("id").asText();
    }

    public Result post(Map<String, Object> body) throws IOException, InterruptedException {
        HttpResponse<String> r = send("POST", "/api/v1/transactions", bytes(body));
        if (r.statusCode() / 100 == 2) {
            return new Result.Created(Json.mapper().readTree(r.body()).path("data").path("id").asText());
        }
        Matcher m = DUPLICATE.matcher(r.body() == null ? "" : r.body());
        if (r.statusCode() == 422 && m.find()) {
            return new Result.Duplicate(m.group(1));
        }
        return new Result.Failed(r.statusCode(), message(r.body()));
    }

    /** Replace a group with the body given. Callers must pass what they read back, modified. */
    public Result put(String groupId, Map<String, Object> body) throws IOException, InterruptedException {
        HttpResponse<String> r = send("PUT", "/api/v1/transactions/" + groupId, bytes(body));
        return r.statusCode() / 100 == 2
            ? new Result.Created(groupId)
            : new Result.Failed(r.statusCode(), message(r.body()));
    }

    /** Gone is the goal: a group already deleted (by you, or by a run that stopped) is not an error. */
    public void deleteTransaction(String groupId) throws IOException, InterruptedException {
        HttpResponse<String> r = send("DELETE", "/api/v1/transactions/" + groupId, null);
        if (r.statusCode() == 404) {
            return;
        }
        if (r.statusCode() / 100 != 2) {
            throw new IOException("deleting group " + groupId + ": " + r.statusCode() + " " + message(r.body()));
        }
    }

    public JsonNode group(String groupId) throws IOException, InterruptedException {
        return get("/api/v1/transactions/" + groupId);
    }

    /**
     * The group, or null when Firefly no longer has it (D9). A missing group is a named recovery,
     * never a raw {@link IOException}: {@link #group} still throws for callers that require it.
     */
    public JsonNode groupOrNull(String groupId) throws IOException, InterruptedException {
        HttpResponse<String> r = send("GET", "/api/v1/transactions/" + groupId, null);
        if (r.statusCode() == 404) {
            return null;
        }
        if (r.statusCode() / 100 != 2) {
            throw new IOException("GET /api/v1/transactions/" + groupId + " -> " + r.statusCode()
                + ": " + message(r.body()));
        }
        return Json.mapper().readTree(r.body());
    }

    private static byte[] bytes(Object body) {
        try {
            return Json.mapper().writeValueAsBytes(body);
        } catch (IOException e) {
            throw new IllegalStateException("cannot serialise the request body", e);
        }
    }

    private static String message(String body) {
        try {
            JsonNode n = Json.mapper().readTree(body);
            return n.path("message").asText(body);
        } catch (IOException e) {
            return body == null ? "" : body.substring(0, Math.min(200, body.length()));
        }
    }

    private JsonNode get(String path) throws IOException, InterruptedException {
        HttpResponse<String> r = send("GET", path, null);
        if (r.statusCode() / 100 != 2) {
            throw new IOException("GET " + path + " -> " + r.statusCode() + ": " + message(r.body()));
        }
        return Json.mapper().readTree(r.body());
    }

    private HttpResponse<String> send(String method, String path, byte[] body)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(base.resolve(path))
            .timeout(Duration.ofSeconds(60))
            .header("Authorization", "Bearer " + token)
            .header("Accept", "application/json");
        if (body == null) {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofByteArray(body));
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
                : r.headers().firstValue("Retry-After").map(FireflyClient::seconds).orElse(backoff);
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
