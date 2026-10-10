package trex.v2.hub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import trex.v2.hub.api.DecisionRequest;
import trex.v2.hub.api.RefdataResponse;
import trex.v2.log.Json;
import trex.v2.sequencer.api.DecisionDraft;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The MCP write tools (V2-MCP-SERVER-PLAN.md §3.2, §4, Stage 4; V2-ASSISTANT-PLAN.md §3 Stage A):
 * opt-in and attributed. The low-level tool is {@code trex_submit_decisions}; the intent-shaped
 * tools {@code trex_categorize} ({@code PIN}), {@code trex_note} ({@code NOTE}) and
 * {@code trex_mark_paid} ({@code SETTLE_OCCURRENCE}) each build one draft mirroring
 * {@code decisions.js}. Every tool posts a {@link DecisionRequest} through
 * {@link HubApi#submitDecisions} — the same path the UI uses — so prechecks, the {@code 409}
 * staleness check and the one-writer rule are untouched. This class adds no writer and reaches
 * neither the journal nor the index.
 *
 * <p><b>Attribution (D4, A2/A3).</b> A write may be attributed to the configured agent or to a
 * declared active user ({@code api.refdata().users()}); any other name is a tool error. A draft may
 * not name a user other than the resolved one: the whole call is rejected, so an agent can never
 * silently post as the person. Every accepted draft is stamped {@code user = actingUser} and
 * {@code actor = "user"}.
 *
 * <p>The tools are present only when {@link McpConfig#allowWrites()} — {@link McpApi} merges this
 * registry only then; with writes off each name is unknown and {@code tools/call} is {@code -32602}.
 */
final class McpWriteTools {

    /** A write tool's behaviour: map validated arguments and the config to a complete tool result. */
    interface Handler {
        JsonNode handle(HubApi api, JsonNode args, McpConfig config);
    }

    /** A tool descriptor, shaped like {@link McpTools.Tool} so {@link McpApi} can render both. */
    record Tool(String name, String description, ObjectNode inputSchema, Set<String> args,
                Handler handler) {}

    private static final Map<String, Tool> TOOLS = build();

    private McpWriteTools() {}

    /** Every write tool, in the stable order {@code tools/list} publishes. */
    static Map<String, Tool> tools() {
        return TOOLS;
    }

    static Tool find(String name) {
        return TOOLS.get(name);
    }

    private static Map<String, Tool> build() {
        Map<String, Tool> tools = new LinkedHashMap<>();
        tools.put("trex_submit_decisions", submitDecisionsTool());
        tools.put("trex_categorize", categorizeTool());
        tools.put("trex_note", noteTool());
        tools.put("trex_mark_paid", markPaidTool());
        return java.util.Collections.unmodifiableMap(tools);
    }

    private static Tool submitDecisionsTool() {
        ObjectNode schema = Json.mapper().createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        addCommonProperties(properties);
        properties.putObject("allOrNone").put("type", "boolean");
        ObjectNode decisions = properties.putObject("decisions");
        decisions.put("type", "array");
        decisions.put("minItems", 1);
        ObjectNode items = decisions.putObject("items");
        items.put("type", "object");
        // A DecisionDraft is one flat shape carrying every action's fields; the common ones are named
        // and the rest pass through, so the model sees the field names without a 40-field fence.
        ObjectNode itemProps = items.putObject("properties");
        itemProps.putObject("action").put("type", "string")
            .put("description", "the decision action, e.g. PIN, NOTE, SETTLE, PAIR, USER_ACK");
        itemProps.putObject("externalId").put("type", "string");
        itemProps.putObject("category").put("type", "string");
        itemProps.putObject("text").put("type", "string");
        itemProps.putObject("comment").put("type", "string");
        itemProps.putObject("commitmentId").put("type", "string");
        itemProps.putObject("pendingId").put("type", "string");
        itemProps.putObject("postedId").put("type", "string");
        itemProps.putObject("fromId").put("type", "string");
        itemProps.putObject("toId").put("type", "string");
        itemProps.putObject("reason").put("type", "string");
        itemProps.putObject("externalIds").put("type", "array").putObject("items").put("type", "string");
        itemProps.putObject("dueDates").put("type", "array").putObject("items").put("type", "string");
        items.put("additionalProperties", true);
        items.putArray("required").add("action");
        ArrayNode required = schema.putArray("required");
        required.add("asOfN");
        required.add("decisions");
        schema.put("additionalProperties", false);
        return new Tool("trex_submit_decisions",
            "Submit decision drafts through the hub's decision path, attributed to the acting user",
            schema, Set.of("actingUser", "allOrNone", "asOfN", "decisions"), McpWriteTools::submitDecisions);
    }

    private static Tool categorizeTool() {
        ObjectNode schema = baseSchema();
        ObjectNode properties = (ObjectNode) schema.get("properties");
        properties.putObject("externalIds").put("type", "array").putObject("items").put("type", "string");
        properties.putObject("category").put("type", "string");
        require(schema, "externalIds", "category");
        return new Tool("trex_categorize",
            "Categorise transactions (PIN): assign a category to one or more external ids",
            schema, Set.of("actingUser", "asOfN", "externalIds", "category"), McpWriteTools::categorize);
    }

    private static Tool noteTool() {
        ObjectNode schema = baseSchema();
        ObjectNode properties = (ObjectNode) schema.get("properties");
        properties.putObject("externalId").put("type", "string");
        properties.putObject("text").put("type", "string");
        require(schema, "externalId", "text");
        return new Tool("trex_note",
            "Attach a note (NOTE) to a transaction by its external id",
            schema, Set.of("actingUser", "asOfN", "externalId", "text"), McpWriteTools::note);
    }

    private static Tool markPaidTool() {
        ObjectNode schema = baseSchema();
        ObjectNode properties = (ObjectNode) schema.get("properties");
        properties.putObject("commitmentId").put("type", "string");
        properties.putObject("dueDates").put("type", "array").putObject("items").put("type", "string");
        require(schema, "commitmentId", "dueDates");
        return new Tool("trex_mark_paid",
            "Mark commitment occurrences paid (SETTLE_OCCURRENCE) for the given due dates",
            schema, Set.of("actingUser", "asOfN", "commitmentId", "dueDates"), McpWriteTools::markPaid);
    }

    /** The schema shell every write tool shares, with {@code asOfN} already required. */
    private static ObjectNode baseSchema() {
        ObjectNode schema = Json.mapper().createObjectNode();
        schema.put("type", "object");
        addCommonProperties(schema.putObject("properties"));
        schema.putArray("required").add("asOfN");
        schema.put("additionalProperties", false);
        return schema;
    }

    /** {@code asOfN} (the staleness token) and {@code actingUser} (A2), on every write tool. */
    private static void addCommonProperties(ObjectNode properties) {
        properties.putObject("asOfN").put("type", "integer")
            .put("description", "the log position the caller's view was built at; a stale value is the hub's 409");
        properties.putObject("actingUser").put("type", "string")
            .put("description", "a declared active user id or 'agent'; defaults to the configured agent");
    }

    private static void require(ObjectNode schema, String... names) {
        ArrayNode required = (ArrayNode) schema.get("required");
        for (String name : names) {
            required.add(name);
        }
    }

    /**
     * The {@code trex_submit_decisions} handler. {@code asOfN} is required — the {@code n} the
     * caller's view was built at — so a stale write is the hub's {@code 409}, never a silent
     * mis-post (plan §4). The outcome is mapped to a tool result and never swallowed.
     */
    static JsonNode submitDecisions(HubApi api, JsonNode args, McpConfig config) {
        JsonNode decisions = args.get("decisions");
        if (decisions == null || !decisions.isArray() || decisions.isEmpty()) {
            throw new McpArgs.BadArgs("decisions must be a non-empty array");
        }
        return write(api, args, config, (ArrayNode) decisions);
    }

    /** {@code trex_categorize}: one {@code PIN} draft from {@code externalIds} and {@code category}. */
    static JsonNode categorize(HubApi api, JsonNode args, McpConfig config) {
        ArrayNode externalIds = requiredStringArray(args, "externalIds");
        String category = McpArgs.requiredText(args, "category");
        ObjectNode draft = draft("PIN");
        draft.set("externalIds", externalIds);
        draft.put("category", category);
        return write(api, args, config, one(draft));
    }

    /** {@code trex_note}: one {@code NOTE} draft from {@code externalId} and {@code text}. */
    static JsonNode note(HubApi api, JsonNode args, McpConfig config) {
        String externalId = McpArgs.requiredText(args, "externalId");
        String text = McpArgs.requiredText(args, "text");
        ObjectNode draft = draft("NOTE");
        draft.put("externalId", externalId);
        draft.put("text", text);
        return write(api, args, config, one(draft));
    }

    /** {@code trex_mark_paid}: one {@code SETTLE_OCCURRENCE} draft from {@code commitmentId}/{@code dueDates}. */
    static JsonNode markPaid(HubApi api, JsonNode args, McpConfig config) {
        String commitmentId = McpArgs.requiredText(args, "commitmentId");
        ArrayNode dueDates = requiredDateArray(args, "dueDates");
        ObjectNode draft = draft("SETTLE_OCCURRENCE");
        draft.put("commitmentId", commitmentId);
        draft.set("dueDates", dueDates);
        return write(api, args, config, one(draft));
    }

    /**
     * The one write path every tool funnels through: resolve the acting user, attribute the drafts,
     * post the batch through {@link HubApi#submitDecisions} and map the outcome. Keeping it in one
     * place means the low-level tool and the three actions cannot drift.
     */
    private static JsonNode write(HubApi api, JsonNode args, McpConfig config, ArrayNode decisions) {
        long asOfN = McpArgs.requiredLong(args, "asOfN");
        Boolean allOrNone = McpArgs.optionalBool(args, "allOrNone");
        String actingUser = resolveActingUser(api, args, config);
        List<DecisionDraft> drafts = McpAttribution.attribute(decisions, actingUser);
        DecisionOutcome outcome = api.submitDecisions(
            new DecisionRequest(allOrNone, asOfN, drafts));
        return mapOutcome(outcome);
    }

    /**
     * Resolve the acting user (A2): an absent or blank {@code actingUser} is the configured agent;
     * otherwise it must be a declared active user or the agent, and anything else is a tool error.
     * The declared users are read fresh from {@link HubApi#refdata()} on every call — never cached —
     * so a user disabled in config stops being accepted immediately.
     */
    private static String resolveActingUser(HubApi api, JsonNode args, McpConfig config) {
        String requested = McpArgs.optionalText(args, "actingUser");
        if (requested == null) {
            return config.agentUser();
        }
        Set<String> allowed = new TreeSet<>();
        allowed.add(config.agentUser());
        for (RefdataResponse.UserJson user : api.refdata().users()) {
            if (user.active()) {
                allowed.add(user.id());
            }
        }
        if (!allowed.contains(requested)) {
            throw new McpArgs.BadArgs("actingUser must be a declared active user or '"
                + config.agentUser() + "'; allowed: " + String.join(", ", allowed));
        }
        return requested;
    }

    private static ObjectNode draft(String action) {
        ObjectNode draft = Json.mapper().createObjectNode();
        draft.put("action", action);
        return draft;
    }

    private static ArrayNode one(ObjectNode draft) {
        ArrayNode decisions = Json.mapper().createArrayNode();
        decisions.add(draft);
        return decisions;
    }

    /** A non-empty array of strings; missing, empty or a non-string element is a tool error. */
    private static ArrayNode requiredStringArray(JsonNode args, String name) {
        JsonNode value = args.get(name);
        if (value == null || !value.isArray() || value.isEmpty()) {
            throw new McpArgs.BadArgs(name + " must be a non-empty array of strings");
        }
        ArrayNode strings = Json.mapper().createArrayNode();
        for (JsonNode item : value) {
            if (!item.isTextual()) {
                throw new McpArgs.BadArgs(name + " must be a non-empty array of strings");
            }
            strings.add(item.asText());
        }
        return strings;
    }

    /** A non-empty array of ISO dates ({@code yyyy-MM-dd}); a bad element is a tool error. */
    private static ArrayNode requiredDateArray(JsonNode args, String name) {
        JsonNode value = args.get(name);
        if (value == null || !value.isArray() || value.isEmpty()) {
            throw new McpArgs.BadArgs(name + " must be a non-empty array of ISO dates (yyyy-MM-dd)");
        }
        ArrayNode dates = Json.mapper().createArrayNode();
        for (JsonNode item : value) {
            if (!item.isTextual()) {
                throw new McpArgs.BadArgs(name + " must be a non-empty array of ISO dates (yyyy-MM-dd)");
            }
            try {
                LocalDate.parse(item.asText());
            } catch (DateTimeParseException e) {
                throw new McpArgs.BadArgs(name + " must be a non-empty array of ISO dates (yyyy-MM-dd)");
            }
            dates.add(item.asText());
        }
        return dates;
    }

    /**
     * Map the hub's outcome to a tool result: a 2xx is a normal result carrying the body as
     * structured content; a {@code 409} is surfaced with the hub's body and a re-read instruction;
     * any other non-2xx is surfaced with its status and body. Nothing is written here.
     */
    private static JsonNode mapOutcome(DecisionOutcome outcome) {
        JsonNode body = Json.mapper().valueToTree(outcome.body());
        int status = outcome.status();
        if (status >= 200 && status < 300) {
            return McpApi.toolResult(body);
        }
        if (status == 409) {
            return McpApi.toolError(
                "view moved; re-read and rebuild the draft: " + hubMessage(body), body);
        }
        return McpApi.toolError(
            "submit_decisions: the hub answered " + status + ": " + hubMessage(body), body);
    }

    /** The hub's human-readable error, or the whole body when it carries no {@code error} field. */
    private static String hubMessage(JsonNode body) {
        if (body != null && body.isObject() && body.hasNonNull("error")) {
            return body.get("error").asText();
        }
        return String.valueOf(body);
    }
}
