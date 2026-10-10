package trex.v2.hub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import trex.v2.log.Json;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The MCP read tools (V2-MCP-SERVER-PLAN.md §3.1, Stage 2): one tool per {@link HubApi} read, each
 * descriptor a name, a one-sentence description, an object {@code inputSchema} and a handler. Reads
 * go straight through the hub's own read surface — never the journal or the index — and add no
 * writer. {@link McpApi} lists this registry and looks a name up on {@code tools/call}.
 *
 * <p>{@code trex_get} is the allow-listed passthrough (D6): it accepts only the read paths below,
 * rejects any other, and dispatches to the same handler the typed tool uses.
 */
final class McpTools {

    /** One read route: the path a {@code trex_get} accepts, its query params and its handler. */
    private record Route(String path, Set<String> params, Handler handler) {}

    /** A tool's behaviour: map the (already validated) arguments to the structured result data. */
    interface Handler {
        JsonNode handle(HubApi api, JsonNode args);
    }

    /** A tool descriptor: what {@code tools/list} publishes and {@code tools/call} invokes. */
    record Tool(String name, String description, ObjectNode inputSchema, Set<String> args,
                Handler handler) {}

    /** The ledger query parameters, shared by the typed tool and the {@code /api/ledger} passthrough. */
    private static final Set<String> LEDGER_ARGS = Set.of(
        "account", "category", "leg", "role", "direction", "from", "to", "q", "minAmount",
        "maxAmount", "hasReview", "sort", "order", "limit", "offset");

    private static final Map<String, Route> ROUTES = routes();
    private static final Map<String, Tool> TOOLS = build();

    private McpTools() {}

    /** Every tool, in the stable order {@code tools/list} publishes. */
    static Map<String, Tool> tools() {
        return TOOLS;
    }

    static Tool find(String name) {
        return TOOLS.get(name);
    }

    // ---- the read routes (also the {@code trex_get} allow-list) ------------------------------

    private static Map<String, Route> routes() {
        Map<String, Route> map = new LinkedHashMap<>();
        add(map, "/api/status", Set.of(), McpTools::status);
        add(map, "/api/units", Set.of(), McpTools::units);
        add(map, "/api/ledger", LEDGER_ARGS, McpTools::ledger);
        add(map, "/api/review", Set.of("kind", "account"), McpTools::review);
        add(map, "/api/commitments", Set.of(), McpTools::commitments);
        add(map, "/api/commitments/activity", Set.of("id"), McpTools::activity);
        add(map, "/api/expected", Set.of("window", "asOf"), McpTools::expected);
        add(map, "/api/accounts", Set.of("window", "granularity", "asOf"), McpTools::accounts);
        add(map, "/api/opening", Set.of(), McpTools::opening);
        add(map, "/api/reconcile", Set.of(), McpTools::reconcile);
        add(map, "/api/transfers", Set.of(), McpTools::transfers);
        add(map, "/api/workbook", Set.of(), McpTools::workbook);
        add(map, "/api/notes", Set.of("externalId"), McpTools::notes);
        add(map, "/api/chains", Set.of("account"), McpTools::chains);
        add(map, "/api/since", Set.of("n", "user"), McpTools::since);
        add(map, "/api/ingests", Set.of("sinceN"), McpTools::ingests);
        add(map, "/api/config/drift", Set.of(), McpTools::configDrift);
        add(map, "/api/projection", Set.of(), McpTools::projection);
        return Map.copyOf(map);
    }

    private static void add(Map<String, Route> map, String path, Set<String> params, Handler handler) {
        map.put(path, new Route(path, params, handler));
    }

    // ---- the registry ------------------------------------------------------------------------

    private static Map<String, Tool> build() {
        Map<String, Tool> tools = new LinkedHashMap<>();
        tools.put("trex_status", tool("trex_status",
            "Trex hub status and head (asOfN, deriveVersion, counts)",
            noProps(), Set.of(), new String[0], McpTools::status));
        tools.put("trex_units", tool("trex_units",
            "The projectable egress units and the asOfN they are current at",
            noProps(), Set.of(), new String[0], McpTools::units));
        tools.put("trex_ledger", tool("trex_ledger",
            "The ledger page: transactions matching the filters, with the matching total",
            ledgerProps(), LEDGER_ARGS, new String[0], McpTools::ledger));
        tools.put("trex_review", tool("trex_review",
            "The derived review queue, optionally filtered by kind and account",
            reviewProps(), Set.of("kind", "account"), new String[0], McpTools::review));
        tools.put("trex_commitments", tool("trex_commitments",
            "The commitment registry: detected candidates and declared commitments",
            noProps(), Set.of(), new String[0], McpTools::commitments));
        tools.put("trex_commitment_activity", tool("trex_commitment_activity",
            "One commitment's activity: the facts behind a candidate or a declared row",
            activityProps(), Set.of("id"), new String[] {"id"}, McpTools::activity));
        tools.put("trex_expected", tool("trex_expected",
            "The expected view: a window's occurrences, the arrears backlog and the totals",
            expectedProps(), Set.of("window", "asOf"), new String[0], McpTools::expected));
        tools.put("trex_accounts", tool("trex_accounts",
            "The accounts overview: per-account coverage and the newest ingest",
            accountsProps(), Set.of("window", "granularity", "asOf"), new String[0], McpTools::accounts));
        tools.put("trex_opening", tool("trex_opening",
            "Where each account stood before trex saw anything",
            noProps(), Set.of(), new String[0], McpTools::opening));
        tools.put("trex_reconcile", tool("trex_reconcile",
            "The reconciliation run over derived state",
            noProps(), Set.of(), new String[0], McpTools::reconcile));
        tools.put("trex_transfers", tool("trex_transfers",
            "The derived transfer pairs",
            noProps(), Set.of(), new String[0], McpTools::transfers));
        tools.put("trex_workbook", tool("trex_workbook",
            "The categorisation workbook report: rules, pins and suggestions",
            noProps(), Set.of(), new String[0], McpTools::workbook));
        tools.put("trex_notes", tool("trex_notes",
            "The note thread on one external id, oldest first",
            notesProps(), Set.of("externalId"), new String[] {"externalId"}, McpTools::notes));
        tools.put("trex_chains", tool("trex_chains",
            "The chain health per account, optionally narrowed to one account",
            chainsProps(), Set.of("account"), new String[0], McpTools::chains));
        tools.put("trex_since", tool("trex_since",
            "What the log appended after line n, plus the month's headroom",
            sinceProps(), Set.of("n", "user"), new String[] {"n"}, McpTools::since));
        tools.put("trex_ingests", tool("trex_ingests",
            "The ingest history, newest first, or everything after a batch n",
            ingestsProps(), Set.of("sinceN"), new String[0], McpTools::ingests));
        tools.put("trex_config_drift", tool("trex_config_drift",
            "The shipped/base/current drift of every seeded config file",
            noProps(), Set.of(), new String[0], McpTools::configDrift));
        tools.put("trex_projection", tool("trex_projection",
            "The projection-state accelerator rows",
            noProps(), Set.of(), new String[0], McpTools::projection));
        tools.put("trex_get", tool("trex_get",
            "A read-only GET passthrough to an allow-listed hub path, with its query params",
            getProps(), getArgs(), new String[] {"path"}, McpTools::get));
        return java.util.Collections.unmodifiableMap(tools);
    }

    // ---- handlers (one per HubApi read) ------------------------------------------------------

    static JsonNode status(HubApi api, JsonNode args) {
        ObjectNode data = Json.mapper().createObjectNode();
        data.set("status", Json.mapper().valueToTree(api.status()));
        data.set("head", Json.mapper().valueToTree(api.head()));
        return data;
    }

    static JsonNode units(HubApi api, JsonNode args) {
        return Json.mapper().valueToTree(api.units());
    }

    static JsonNode ledger(HubApi api, JsonNode args) {
        return Json.mapper().valueToTree(api.ledger(ledgerQuery(args)));
    }

    static JsonNode review(HubApi api, JsonNode args) {
        return rows(api.review(McpArgs.optionalText(args, "kind"), McpArgs.optionalText(args, "account")));
    }

    static JsonNode commitments(HubApi api, JsonNode args) {
        return rows(api.commitments());
    }

    static JsonNode activity(HubApi api, JsonNode args) {
        return rows(api.activity(McpArgs.requiredText(args, "id")));
    }

    static JsonNode expected(HubApi api, JsonNode args) {
        return Json.mapper().valueToTree(
            api.expected(McpArgs.optionalText(args, "window"), McpArgs.optionalDate(args, "asOf")));
    }

    static JsonNode accounts(HubApi api, JsonNode args) {
        return Json.mapper().valueToTree(api.accounts(
            McpArgs.optionalText(args, "window"),
            McpArgs.optionalText(args, "granularity"),
            McpArgs.optionalDate(args, "asOf")));
    }

    static JsonNode opening(HubApi api, JsonNode args) {
        return Json.mapper().valueToTree(api.opening());
    }

    static JsonNode reconcile(HubApi api, JsonNode args) {
        return Json.mapper().valueToTree(api.reconcile());
    }

    static JsonNode transfers(HubApi api, JsonNode args) {
        return rows(api.transfers());
    }

    static JsonNode workbook(HubApi api, JsonNode args) {
        return Json.mapper().valueToTree(api.workbook());
    }

    static JsonNode notes(HubApi api, JsonNode args) {
        return rows(api.notes(McpArgs.requiredText(args, "externalId")));
    }

    static JsonNode chains(HubApi api, JsonNode args) {
        return Json.mapper().valueToTree(api.chains(McpArgs.optionalText(args, "account")));
    }

    static JsonNode since(HubApi api, JsonNode args) {
        return Json.mapper().valueToTree(
            api.since(McpArgs.requiredLong(args, "n"), McpArgs.optionalText(args, "user")));
    }

    static JsonNode ingests(HubApi api, JsonNode args) {
        return Json.mapper().valueToTree(api.ingests(McpArgs.optionalLong(args, "sinceN")));
    }

    static JsonNode configDrift(HubApi api, JsonNode args) {
        return rows(api.configDrift());
    }

    static JsonNode projection(HubApi api, JsonNode args) {
        return Json.mapper().valueToTree(api.projection());
    }



    /** The allow-listed passthrough: only a known read path, only that path's query params. */
    static JsonNode get(HubApi api, JsonNode args) {
        String path = McpArgs.requiredText(args, "path");
        Route route = ROUTES.get(path);
        if (route == null) {
            throw new McpArgs.BadArgs("unknown read path '" + path + "'");
        }
        Set<String> allowed = new LinkedHashSet<>(route.params());
        allowed.add("path");
        McpArgs.rejectUnknown(args, allowed);
        return route.handler().handle(api, args);
    }

    // ---- query building ----------------------------------------------------------------------

    /**
     * Build the ledger's query string and let {@link BlotterQuery#parse} validate and clamp it, so
     * {@code trex_ledger} keeps exactly the hub's rules (sort whitelist, {@code 1..1000} limit).
     */
    private static BlotterQuery ledgerQuery(JsonNode args) {
        StringBuilder qs = new StringBuilder();
        append(qs, "account", McpArgs.optionalText(args, "account"));
        append(qs, "category", McpArgs.optionalText(args, "category"));
        append(qs, "leg", McpArgs.optionalText(args, "leg"));
        append(qs, "role", McpArgs.optionalText(args, "role"));
        append(qs, "direction", McpArgs.optionalText(args, "direction"));
        append(qs, "from", dateText(McpArgs.optionalDate(args, "from")));
        append(qs, "to", dateText(McpArgs.optionalDate(args, "to")));
        append(qs, "q", McpArgs.optionalText(args, "q"));
        append(qs, "minAmount", longText(McpArgs.optionalLong(args, "minAmount")));
        append(qs, "maxAmount", longText(McpArgs.optionalLong(args, "maxAmount")));
        Boolean hasReview = McpArgs.optionalBool(args, "hasReview");
        if (hasReview != null) {
            append(qs, "hasReview", hasReview.toString());
        }
        append(qs, "sort", McpArgs.optionalText(args, "sort"));
        append(qs, "order", McpArgs.optionalText(args, "order"));
        append(qs, "limit", intText(McpArgs.optionalInt(args, "limit")));
        append(qs, "offset", intText(McpArgs.optionalInt(args, "offset")));
        return BlotterQuery.parse(qs.toString());
    }

    private static void append(StringBuilder qs, String key, String value) {
        if (value == null) {
            return;
        }
        if (!qs.isEmpty()) {
            qs.append('&');
        }
        qs.append(key).append('=').append(URLEncoder.encode(value, StandardCharsets.UTF_8));
    }

    private static String dateText(LocalDate date) {
        return date == null ? null : date.toString();
    }

    private static String longText(Long value) {
        return value == null ? null : value.toString();
    }

    private static String intText(Integer value) {
        return value == null ? null : value.toString();
    }

    /** A list read, wrapped so {@code structuredContent} is the object the MCP spec expects. */
    private static JsonNode rows(Object list) {
        ObjectNode out = Json.mapper().createObjectNode();
        out.set("rows", Json.mapper().valueToTree(list));
        return out;
    }

    // ---- schemas -----------------------------------------------------------------------------

    private static Tool tool(String name, String description, ObjectNode properties,
                             Set<String> args, String[] required, Handler handler) {
        ObjectNode schema = Json.mapper().createObjectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        if (required.length > 0) {
            ArrayNode req = schema.putArray("required");
            for (String field : required) {
                req.add(field);
            }
        }
        schema.put("additionalProperties", false);
        return new Tool(name, description, schema, args, handler);
    }

    private static ObjectNode noProps() {
        return Json.mapper().createObjectNode();
    }

    private static ObjectNode ledgerProps() {
        ObjectNode p = Json.mapper().createObjectNode();
        string(p, "account", "category", "leg", "role", "direction", "q", "sort", "order");
        date(p, "from", "to");
        integer(p, "minAmount", "maxAmount", "limit", "offset");
        bool(p, "hasReview");
        return p;
    }

    private static ObjectNode reviewProps() {
        ObjectNode p = Json.mapper().createObjectNode();
        string(p, "kind", "account");
        return p;
    }

    private static ObjectNode activityProps() {
        ObjectNode p = Json.mapper().createObjectNode();
        string(p, "id");
        return p;
    }

    private static ObjectNode expectedProps() {
        ObjectNode p = Json.mapper().createObjectNode();
        string(p, "window");
        date(p, "asOf");
        return p;
    }

    private static ObjectNode accountsProps() {
        ObjectNode p = Json.mapper().createObjectNode();
        string(p, "window", "granularity");
        date(p, "asOf");
        return p;
    }

    private static ObjectNode notesProps() {
        ObjectNode p = Json.mapper().createObjectNode();
        string(p, "externalId");
        return p;
    }

    private static ObjectNode chainsProps() {
        ObjectNode p = Json.mapper().createObjectNode();
        string(p, "account");
        return p;
    }

    private static ObjectNode sinceProps() {
        ObjectNode p = Json.mapper().createObjectNode();
        integer(p, "n");
        string(p, "user");
        return p;
    }

    private static ObjectNode ingestsProps() {
        ObjectNode p = Json.mapper().createObjectNode();
        integer(p, "sinceN");
        return p;
    }

    /** The union of every route's params plus {@code path}: {@code trex_get} validates the rest. */
    private static ObjectNode getProps() {
        ObjectNode p = Json.mapper().createObjectNode();
        string(p, "path", "account", "category", "leg", "role", "direction", "q", "sort", "order",
            "kind", "id", "window", "granularity", "externalId", "user");
        date(p, "from", "to", "asOf");
        integer(p, "minAmount", "maxAmount", "limit", "offset", "n", "sinceN");
        bool(p, "hasReview");
        return p;
    }

    private static Set<String> getArgs() {
        Set<String> allowed = new LinkedHashSet<>();
        allowed.add("path");
        for (Route route : ROUTES.values()) {
            allowed.addAll(route.params());
        }
        return Set.copyOf(allowed);
    }

    private static void string(ObjectNode props, String... names) {
        for (String name : names) {
            props.putObject(name).put("type", "string");
        }
    }

    private static void integer(ObjectNode props, String... names) {
        for (String name : names) {
            props.putObject(name).put("type", "integer");
        }
    }

    private static void bool(ObjectNode props, String... names) {
        for (String name : names) {
            props.putObject(name).put("type", "boolean");
        }
    }

    private static void date(ObjectNode props, String... names) {
        for (String name : names) {
            ObjectNode field = props.putObject(name);
            field.put("type", "string");
            field.put("format", "date");
        }
    }
}
