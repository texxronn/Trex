package trex.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import trex.category.CategoryRules;
import trex.category.Categorizer;
import trex.category.Placement;
import trex.category.Rule;
import trex.category.RuleHealth;
import trex.core.CanonicalEvent;
import trex.gateway.Web.HttpError;
import trex.journal.Json;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Reading and amending the rule files (SPEC §5.6, §5.7). The endpoints that remove the clipboard
 * from the categorisation loop.
 * <p>
 * Nothing is written without first being dry-run: {@code /api/proposal} is that dry run made
 * visible, and every write repeats it and refuses a rule that would change nothing. The three
 * safety rails live in {@link RuleStore} rather than here, so no route can skip one.
 */
public final class RuleRoutes {

    static final int MAX_BODY_BYTES = 64 * 1024;
    static final String ADMIN_HEADER = "X-Trex-Admin";

    private final Rules rules;
    private final Supplier<List<CanonicalEvent>> lines;

    public RuleRoutes(Rules rules, Supplier<List<CanonicalEvent>> lines) {
        this.rules = rules;
        this.lines = lines;
    }

    public void handle(HttpExchange ex, String path) throws Exception {
        switch (path) {
            case "/api/rules" -> {
                if (ex.getRequestMethod().equals("GET")) {
                    Web.json(ex, 200, listing());
                    return;
                }
                Web.requireMethod(ex, "POST");
                addRule(ex);
            }
            case "/api/pins" -> {
                if (ex.getRequestMethod().equals("GET")) {
                    Web.json(ex, 200, listing());
                    return;
                }
                Web.requireMethod(ex, "POST");
                addPin(ex);
            }
            case "/api/proposal" -> {
                Web.requireMethod(ex, "GET");
                Web.json(ex, 200, proposal(ex));
            }
            case "/api/worklist" -> {
                Web.requireMethod(ex, "GET");
                Web.json(ex, 200, worklist());
            }
            default -> {
                // /api/rules/{index} and /api/pins/{index}: PATCH replaces, DELETE removes.
                Entry target = entryPath(path);
                if (target == null) {
                    throw new HttpError(404, "not found");
                }
                switch (ex.getRequestMethod()) {
                    case "GET" -> Web.json(ex, 200, one(target));
                    case "DELETE" -> delete(ex, target);
                    case "PATCH" -> patch(ex, target);
                    default -> {
                        ex.getResponseHeaders().set("Allow", "GET, DELETE, PATCH");
                        throw new HttpError(405, "method not allowed");
                    }
                }
            }
        }
    }

    private record Entry(boolean pin, int index) {}

    private static Entry entryPath(String path) {
        for (String prefix : List.of("/api/rules/", "/api/pins/")) {
            if (path.startsWith(prefix)) {
                try {
                    return new Entry(prefix.contains("pins"), Integer.parseInt(path.substring(prefix.length())));
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- reading

    /**
     * The rule set with its {@code when} trees and what each entry is actually doing.
     * <p>
     * Both halves matter and neither is enough alone: the file says what the rules are, the
     * journal says what they do, and a rule that fires for nothing looks exactly like a good one
     * until you put the two together (§9).
     */
    private Map<String, Object> listing() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("rulesRevision", rules.revision());
        body.put("categories", rules.categorizer().declared());
        if (rules.store() == null) {
            return body;
        }
        RuleHealth.Report health = RuleHealth.of(rules.categorizer(), lines.get());
        body.put("rules", merge(rules.store().rules(), health.rules(), RuleRoutes::describeRule));
        body.put("pins", merge(rules.store().pins(), health.pins(), RuleRoutes::describePin));
        body.put("promotions", health.promotions().stream().map(p -> Map.of(
            "stem", p.stem(), "category", p.category(), "pinned", p.pinned(),
            "externalIds", p.externalIds())).toList());
        body.put("categorized", health.categorized());
        body.put("uncategorized", health.uncategorized());
        body.put("structural", health.structural());
        return body;
    }

    /** Pair each entry with its statistics; both lists are in file order and the same length. */
    private static <S> List<Map<String, Object>> merge(List<CategoryRules.RuleEntry> entries, List<S> stats,
                                                       java.util.function.BiFunction<CategoryRules.RuleEntry, S,
                                                           Map<String, Object>> describe) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            out.add(describe.apply(entries.get(i), i < stats.size() ? stats.get(i) : null));
        }
        return out;
    }

    private static Map<String, Object> describeRule(CategoryRules.RuleEntry entry, RuleHealth.RuleStat stat) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("index", stat == null ? null : stat.index());
        out.put("category", entry.category());
        out.put("comment", entry.comment());
        // The tree as the file has it, so a client can edit a rule instead of retyping it.
        out.put("when", entry.when());
        if (stat != null) {
            out.put("hits", stat.hits());
            out.put("merchants", stat.merchants());
            out.put("total", stat.total());
            out.put("shadowed", stat.shadowed());
            out.put("shadowedBy", stat.shadowedBy());
            out.put("dead", stat.dead());
            out.put("fullyShadowed", stat.fullyShadowed());
        }
        return out;
    }

    private static Map<String, Object> describePin(CategoryRules.RuleEntry entry, RuleHealth.PinStat stat) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("index", stat == null ? null : stat.index());
        out.put("category", entry.category());
        out.put("comment", entry.comment());
        out.put("when", entry.when());
        if (stat != null) {
            out.put("hits", stat.hits());
            out.put("missingIds", stat.missingIds());
            out.put("stale", stat.stale());
        }
        return out;
    }

    /** One entry, for a client editing it. Same shape as a row of the listing. */
    private Map<String, Object> one(Entry target) throws HttpError {
        if (rules.store() == null) {
            throw new HttpError(404, "no rules are loaded");
        }
        List<CategoryRules.RuleEntry> entries = target.pin() ? rules.store().pins() : rules.store().rules();
        if (target.index() < 1 || target.index() > entries.size()) {
            throw new HttpError(404, (target.pin() ? "pin #" : "rule #") + target.index() + " does not exist");
        }
        RuleHealth.Report health = RuleHealth.of(rules.categorizer(), lines.get());
        CategoryRules.RuleEntry entry = entries.get(target.index() - 1);
        Map<String, Object> body = target.pin()
            ? describePin(entry, health.pins().get(target.index() - 1))
            : describeRule(entry, health.rules().get(target.index() - 1));
        Map<String, Object> out = new LinkedHashMap<>(body);
        out.put("rulesRevision", rules.revision());
        return out;
    }

    /**
     * What still needs a rule: uncategorised rows grouped by merchant stem, ranked by count then
     * spend, each carrying the evidence that decides rule-versus-pin (§5.7).
     * <p>
     * It does not empty, and is not meant to. UNCATEGORIZED is a legitimate answer (§0.6) — the
     * tail of a two-year card history is one-off merchants, and chasing it is waste.
     */
    private Map<String, Object> worklist() {
        List<CanonicalEvent> all = lines.get();
        List<trex.category.DryRun.WorklistEntry> entries =
            trex.category.DryRun.worklist(rules.categorizer(), all);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("rulesRevision", rules.revision());
        body.put("categories", rules.categorizer().declared());
        body.put("transactions", all.size());
        body.put("uncategorized", entries.stream().mapToInt(trex.category.DryRun.WorklistEntry::count).sum());
        body.put("merchants", entries.size());
        body.put("entries", entries.stream().map(e -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("stem", e.stem());
            row.put("count", e.count());
            row.put("total", e.total());
            row.put("firstSeen", e.firstSeen().toString());
            row.put("lastSeen", e.lastSeen().toString());
            row.put("accounts", e.accounts());
            row.put("sampleIds", e.sampleIds());
            return row;
        }).toList());
        return body;
    }

    /**
     * Dry-run a candidate and say what it would do. The numbers here are the whole argument for
     * writing a rule or not: how many rows, how much money, how many were uncategorised, and
     * which existing rule loses them.
     */
    private Map<String, Object> proposal(HttpExchange ex) throws HttpError {
        Map<String, String> q = query(ex.getRequestURI().getRawQuery());
        CategoryRules.RuleEntry entry = entryFrom(q);
        Placement.Coverage coverage = dryRun(entry, "pin".equals(q.get("kind")));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("rulesRevision", rules.revision());
        body.put("category", entry.category());
        body.put("matched", coverage.matched());
        body.put("total", coverage.total());
        body.put("fromUncategorized", coverage.fromNone());
        body.put("blockedByPin", coverage.blockedByPin());
        body.put("takes", coverage.taken().stream().map(t -> Map.of(
            "ruleIndex", t.ruleIndex(), "category", t.category(), "count", t.count())).toList());
        body.put("insertBefore", coverage.insertBefore());
        body.put("allowed", coverage.allowed());
        body.put("refusal", coverage.refusal());
        body.put("sampleIds", coverage.sampleIds());
        // What would actually be written, so a preview can show the diff rather than describe it.
        body.put("yaml", RuleText.render(entry));
        return body;
    }

    private Placement.Coverage dryRun(CategoryRules.RuleEntry entry, boolean pin) throws HttpError {
        Categorizer current = rules.categorizer();
        Rule candidate;
        try {
            candidate = CategoryRules.compileOne("proposal", pin, 0, entry, Set.copyOf(current.declared()));
        } catch (IllegalArgumentException e) {
            throw new HttpError(400, e.getMessage());
        }
        return Placement.of(candidate, current, lines.get());
    }

    // ---------------------------------------------------------------- writing

    private void addRule(HttpExchange ex) throws Exception {
        JsonNode body = body(ex);
        CategoryRules.RuleEntry entry = entryFrom(body);
        String revision = text(body, "rulesRevision");

        // Before anything is computed: if the rule set has moved, every number this call would
        // produce describes a state the caller has not seen. "Matches nothing" against rules they
        // do not have is worse than no answer, so the conflict is reported first.
        requireCurrent(revision);

        Placement.Coverage coverage = dryRun(entry, false);
        if (!coverage.allowed()) {
            // A rule that changes nothing still costs a line in a file a person reads top to
            // bottom. Refusing is the service doing its job, not being unhelpful.
            throw new HttpError(422, coverage.refusal());
        }
        // Not a ternary: mixing an int branch with an Integer one unboxes, and insertBefore is
        // null whenever the rule collides with nothing — which is the ordinary case.
        final Integer at = body.hasNonNull("at")
            ? Integer.valueOf(body.get("at").asInt())
            : coverage.insertBefore();
        String after = write(() -> rules.store().addRule(entry, at, revision));
        Web.json(ex, 200, written(entry, at == null ? "appended" : "inserted before rule #" + at, after, coverage));
    }

    private void addPin(HttpExchange ex) throws Exception {
        JsonNode body = body(ex);
        List<String> ids = new ArrayList<>();
        if (body.has("externalIds")) {
            body.get("externalIds").forEach(n -> ids.add(n.asText()));
        }
        if (ids.isEmpty()) {
            throw new HttpError(400, "externalIds must name at least one transaction");
        }
        CategoryRules.RuleEntry entry = new CategoryRules.RuleEntry(
            text(body, "category"), text(body, "comment"),
            new CategoryRules.WhenEntry(null, null, null, ids, null, null, null, null, null, null));
        String after = write(() -> rules.store().addPin(entry, text(body, "rulesRevision")));
        // Pins are order-independent — they match exact ids, so at most one can fire for a
        // transaction — which is why appending is always correct and no placement is computed.
        Web.json(ex, 200, written(entry, "appended", after, null));
    }

    private void patch(HttpExchange ex, Entry target) throws Exception {
        JsonNode body = body(ex);
        CategoryRules.RuleEntry entry = entryFrom(body);
        String revision = text(body, "rulesRevision");
        String after = write(() -> target.pin()
            ? rules.store().replacePin(target.index(), entry, revision)
            : rules.store().replaceRule(target.index(), entry, revision));
        Web.json(ex, 200, written(entry, "replaced #" + target.index(), after, null));
    }

    private void delete(HttpExchange ex, Entry target) throws Exception {
        String revision = query(ex.getRequestURI().getRawQuery()).get("rulesRevision");
        String after = write(() -> target.pin()
            ? rules.store().deletePin(target.index(), revision)
            : rules.store().deleteRule(target.index(), revision));
        Web.json(ex, 200, Map.of("removed", target.index(), "rulesRevision", after));
    }

    /** The revision check, hoisted ahead of the dry run so a stale caller is told that first. */
    private void requireCurrent(String revision) throws HttpError {
        String actual = rules.revision();
        if (revision != null && !revision.equals(actual)) {
            throw new HttpError(409, "the rules changed since this was composed "
                + "(expected " + revision + ", found " + actual + ")");
        }
    }

    /** Every write ends the same way: the store's rails, then an immediate reload. */
    private String write(Supplier<String> amendment) throws HttpError {
        if (rules.store() == null) {
            throw new HttpError(409, "this service was started without a config directory");
        }
        try {
            String after = amendment.get();
            // Reload now rather than waiting for the watch: the caller is about to re-read.
            rules.reload();
            return after;
        } catch (RuleStore.RevisionConflict e) {
            throw new HttpError(409, e.getMessage());
        } catch (RuleStore.Invalid e) {
            throw new HttpError(422, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new HttpError(400, e.getMessage());
        }
    }

    private static Map<String, Object> written(CategoryRules.RuleEntry entry, String where, String revision,
                                               Placement.Coverage coverage) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("category", entry.category());
        out.put("comment", entry.comment());
        out.put("placement", where);
        out.put("rulesRevision", revision);
        if (coverage != null) {
            out.put("matched", coverage.matched());
            out.put("fromUncategorized", coverage.fromNone());
        }
        return out;
    }

    // ---------------------------------------------------------------- parsing

    /** A candidate from query parameters: the simple shape the Categorize tab proposes. */
    private static CategoryRules.RuleEntry entryFrom(Map<String, String> q) throws HttpError {
        String category = q.get("category");
        if (category == null || category.isBlank()) {
            throw new HttpError(400, "category is required");
        }
        List<String> ids = q.containsKey("externalId") ? List.of(q.get("externalId").split(",")) : null;
        List<String> accounts = q.containsKey("accounts") ? List.of(q.get("accounts").split(",")) : null;
        CategoryRules.WhenEntry when = new CategoryRules.WhenEntry(
            null, null, null, ids, q.get("match"), q.get("matchOn"), q.get("direction"), accounts,
            longOrNull(q.get("amountMin")), longOrNull(q.get("amountMax")));
        return new CategoryRules.RuleEntry(category, q.get("comment"), when);
    }

    /** A candidate from a JSON body, where {@code when} is the full tree. */
    private static CategoryRules.RuleEntry entryFrom(JsonNode body) throws HttpError {
        String category = text(body, "category");
        if (category == null || category.isBlank()) {
            throw new HttpError(400, "category is required");
        }
        if (!body.hasNonNull("when")) {
            throw new HttpError(400, "when is required");
        }
        try {
            CategoryRules.WhenEntry when =
                Json.mapper().treeToValue(body.get("when"), CategoryRules.WhenEntry.class);
            return new CategoryRules.RuleEntry(category, text(body, "comment"), when);
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            throw new HttpError(400, "when: " + e.getOriginalMessage());
        }
    }

    private static Long longOrNull(String value) throws HttpError {
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            throw new HttpError(400, "not a number: " + value);
        }
    }

    private static String text(JsonNode body, String field) {
        return body.hasNonNull(field) ? body.get(field).asText() : null;
    }

    private JsonNode body(HttpExchange ex) throws HttpError, IOException {
        String contentType = ex.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
            throw new HttpError(415, "Content-Type must be application/json");
        }
        guard(ex);
        byte[] bytes;
        try (InputStream in = ex.getRequestBody()) {
            bytes = in.readNBytes(MAX_BODY_BYTES + 1);
        }
        if (bytes.length > MAX_BODY_BYTES) {
            throw new HttpError(413, "request body too large");
        }
        JsonNode body;
        try {
            body = Json.mapper().readTree(bytes);
        } catch (IOException e) {
            throw new HttpError(400, "malformed JSON");
        }
        if (body == null || !body.isObject()) {
            throw new HttpError(400, "body must be a JSON object");
        }
        return body;
    }

    /** The gateway trusts no caller, including trex-web (§5.7). */
    static void guard(HttpExchange ex) throws HttpError {
        if (!"1".equals(ex.getRequestHeaders().getFirst(ADMIN_HEADER))) {
            throw new HttpError(403, "missing " + ADMIN_HEADER + " header");
        }
        String origin = ex.getRequestHeaders().getFirst("Origin");
        String host = ex.getRequestHeaders().getFirst("Host");
        if (origin != null && (host == null || !origin.equals("http://" + host))) {
            throw new HttpError(403, "cross-origin request refused");
        }
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return out;
    }
}
