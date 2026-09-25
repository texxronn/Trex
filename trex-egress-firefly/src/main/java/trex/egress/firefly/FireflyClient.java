package trex.egress.firefly;

import com.fasterxml.jackson.databind.JsonNode;
import trex.journal.Json;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Firefly III API, verified against 6.7.3. SPEC §5.8.
 * <p>
 * The token comes from the environment and is put in a header; it is never a flag (a flag is
 * visible in {@code ps} and lands in shell history) and never logged.
 */
public final class FireflyClient {

    private static final Logger log = LoggerFactory.getLogger(FireflyClient.class);

    /** Firefly caps list endpoints at 100 per page. */
    static final int PAGE = 100;

    /** "Duplicate of transaction #2." — the rejection names the group, so no search is needed. */
    private static final Pattern DUPLICATE = Pattern.compile("Duplicate of transaction #(\\d+)");

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();

    private final URI base;
    private final String token;
    private final Retry retry;

    public FireflyClient(URI base, String token) {
        this(base, token, Retry.NONE);
    }

    public FireflyClient(URI base, String token, Retry retry) {
        this.base = base;
        this.token = token;
        this.retry = retry;
    }

    /**
     * How hard to try again before giving up on a request. SPEC §5.8.
     * <p>
     * Only <b>transient</b> failures are retried: a dropped connection, a 5xx, or a 429. A 4xx is
     * Firefly telling us the request is wrong, and repeating it changes nothing but the clock.
     * Once the attempts are spent the failure is terminal and the pass stops — the retry exists so
     * a container restart or a rate limit does not end a long run, not so that a broken row can be
     * hidden behind a count.
     *
     * @param attempts total tries including the first; 1 disables retrying
     * @param baseMs   first backoff; each further wait doubles it, with jitter so a burst of
     *                 requests does not resynchronise and hit the instance together
     * @param maxMs    ceiling on a single wait
     */
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

    /** A request that failed on every attempt. Terminal: the pass stops here (§5.8). */
    public static final class Unreachable extends IOException {
        public Unreachable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** What happened to one posting. */
    public sealed interface Result {
        record Created(String groupId) implements Result {}

        /** Firefly already had it, and told us which group it is. */
        record Duplicate(String groupId) implements Result {}

        record Failed(int status, String message) implements Result {}
    }

    public String version() throws IOException, InterruptedException {
        return get("/api/v1/about").path("data").path("version").asText("unknown");
    }

    /**
     * The accounts we could ever map to: asset and liability, never expense or revenue.
     * <p>
     * Filtered by type on purpose. Firefly auto-creates one expense account per merchant — 442 of
     * them for this journal — so an unfiltered listing pages through hundreds of accounts that can
     * never be a source or destination we configure, and it grows every time a new shop appears.
     * With the filter it is two requests and fifteen accounts, forever.
     */
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

        /** Firefly reports liabilities as "liabilities"; assets as "asset". */
        public boolean isLiability() {
            return type != null && type.startsWith("liabilit");
        }
    }

    /** One transaction as Firefly currently holds it — what a rebuild and a re-tag read back. */
    public record Existing(String groupId, String externalId, String category, List<String> tags,
                           long journalN, JsonNode group) {}

    /**
     * Every transaction, paged. At 100 a page, the 1751 units are eighteen requests — which is why
     * the projection cache can be thrown away without consequence (§5.8).
     */
    public List<Existing> allTransactions() throws IOException, InterruptedException {
        List<Existing> out = new ArrayList<>();
        for (int page = 1; ; page++) {
            JsonNode body = get("/api/v1/transactions?limit=" + PAGE + "&page=" + page);
            for (JsonNode g : body.path("data")) {
                JsonNode splits = g.path("attributes").path("transactions");
                if (splits.isEmpty()) {
                    continue;
                }
                // external_id is copied to every split, so the first one identifies the group.
                JsonNode first = splits.get(0);
                String external = first.path("external_id").asText(null);
                if (external == null) {
                    continue;                       // not ours
                }
                out.add(new Existing(g.path("id").asText(), external,
                    first.path("category_name").asText(null), tags(first),
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

    /** {@code trex n=1234 rules=…} — put there because the cache is not allowed to be the record. */
    static long journalN(String notes) {
        Matcher m = Pattern.compile("\\btrex n=(\\d+)").matcher(notes == null ? "" : notes);
        return m.find() ? Long.parseLong(m.group(1)) : -1;
    }

    /**
     * Create an asset or liability account. Only ever called for one this config maps and the
     * instance does not have, and only behind an explicit flag — a mistyped name would otherwise
     * create an eleventh account and quietly post a year of transactions into it.
     *
     * @param opening cents; negative for a debt. Seeding a liability positive is what made four
     *                accounts disagree by exactly twice their opening balance.
     */
    public String createAccount(String name, AccountMap.Kind kind, String currency,
                                long opening, java.time.LocalDate asOf)
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
        HttpResponse<String> r = send("POST", "/api/v1/accounts", Json.mapper().writeValueAsBytes(body));
        if (r.statusCode() / 100 != 2) {
            throw new IOException("creating account \"" + name + "\": " + r.statusCode() + " " + message(r.body()));
        }
        return Json.mapper().readTree(r.body()).path("data").path("id").asText();
    }

    /** A category Firefly already has: its id, and whether anything is written on it. */
    public record CategoryInfo(String id, String name, String notes) {

        public boolean hasNotes() {
            return notes != null && !notes.isBlank();
        }
    }

    /** Categories Firefly already has, keyed by name, so seeding is idempotent. */
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

    /**
     * Write notes onto a category that has none. Only ever called when the existing notes are
     * empty: anything you have typed there is yours, and an egress that overwrites it would be
     * doing the thing the compare-and-swap on categories exists to prevent.
     */
    public void setCategoryNotes(String id, String notes) throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("notes", notes);
        HttpResponse<String> r = send("PUT", "/api/v1/categories/" + id, Json.mapper().writeValueAsBytes(body));
        if (r.statusCode() / 100 != 2) {
            throw new IOException("annotating category " + id + ": " + r.statusCode() + " " + message(r.body()));
        }
    }

    /**
     * Create a category, carrying trex's rule comment as its notes — so "why is this GROCERIES?"
     * is answerable inside Firefly too, without coming back to the rule file.
     */
    public void createCategory(String name, String notes) throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        if (notes != null && !notes.isBlank()) {
            body.put("notes", notes);
        }
        HttpResponse<String> r = send("POST", "/api/v1/categories", Json.mapper().writeValueAsBytes(body));
        if (r.statusCode() / 100 != 2) {
            throw new IOException("creating category \"" + name + "\": " + r.statusCode() + " " + message(r.body()));
        }
    }

    public Result post(Projection.Posting posting) throws IOException, InterruptedException {
        HttpResponse<String> r = send("POST", "/api/v1/transactions", Json.mapper().writeValueAsBytes(posting.body()));
        if (r.statusCode() / 100 == 2) {
            return new Result.Created(Json.mapper().readTree(r.body()).path("data").path("id").asText());
        }
        Matcher m = DUPLICATE.matcher(r.body());
        if (r.statusCode() == 422 && m.find()) {
            return new Result.Duplicate(m.group(1));
        }
        return new Result.Failed(r.statusCode(), message(r.body()));
    }

    /**
     * Replace a group with the body given. Callers must pass what they read back, modified — a
     * body built from scratch collapses a split group to one line and destroys the work silently.
     */
    public Result put(String groupId, Map<String, Object> body) throws IOException, InterruptedException {
        HttpResponse<String> r = send("PUT", "/api/v1/transactions/" + groupId, Json.mapper().writeValueAsBytes(body));
        return r.statusCode() / 100 == 2
            ? new Result.Created(groupId)
            : new Result.Failed(r.statusCode(), message(r.body()));
    }

    public JsonNode group(String groupId) throws IOException, InterruptedException {
        return get("/api/v1/transactions/" + groupId);
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

    private HttpResponse<String> send(String method, String path, byte[] body) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(base.resolve(path))
            .timeout(Duration.ofSeconds(60))
            .header("Authorization", "Bearer " + token)
            .header("Accept", "application/json");
        if (body == null) {
            b.GET();
        } else {
            b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofByteArray(body));
        }
        HttpRequest request = b.build();
        IOException last = null;
        for (int attempt = 1; attempt <= retry.attempts(); attempt++) {
            HttpResponse<String> r = null;
            try {
                r = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (!transient_(r.statusCode())) {
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
            // Retry-After is the server telling us how long it wants; prefer it over our guess.
            long backoff = retry.waitFor(attempt);
            long wait = r == null ? backoff
                : r.headers().firstValue("Retry-After").map(FireflyClient::seconds).orElse(backoff);
            log.warn("{} {} failed ({}), attempt {}/{}; retrying in {}ms",
                method, path, last.getMessage(), attempt, retry.attempts(), wait);
            Thread.sleep(wait);
        }
        throw new Unreachable(method + " " + path + " failed on all "
            + retry.attempts() + " attempt(s): " + last.getMessage(), last);
    }

    /** Worth trying again: the instance is restarting, overloaded, or the connection dropped. */
    private static boolean transient_(int status) {
        return status == 429 || status / 100 == 5;
    }

    private static long seconds(String header) {
        try {
            return Math.max(0, Long.parseLong(header.strip()) * 1000);
        } catch (NumberFormatException e) {
            return 1000;                 // a date-form Retry-After; a second is close enough
        }
    }
}
