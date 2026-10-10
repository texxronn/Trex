package trex.v2.hub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import trex.v2.hub.api.DecisionRequest;
import trex.v2.log.Json;
import trex.v2.sequencer.api.DecisionDraft;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The MCP write tools (V2-MCP-SERVER-PLAN.md §3.2, §4, Stage 4): opt-in and attributed. The only
 * write is {@code trex_submit_decisions}, which posts a {@link DecisionRequest} through
 * {@link HubApi#submitDecisions} — the same path the UI uses — so prechecks, the {@code 409}
 * staleness check and the one-writer rule are untouched. This class adds no writer and reaches
 * neither the journal nor the index.
 *
 * <p><b>Attribution (D4).</b> A draft may not name a user other than the configured agent: the
 * whole call is rejected, so an agent can never silently post as the person. Every accepted draft
 * is stamped {@code user = agentUser} and defaults {@code actor = "user"}.
 *
 * <p>The tool is present only when {@link McpConfig#allowWrites()} — {@link McpApi} merges this
 * registry only then; with writes off the name is unknown and {@code tools/call} is {@code -32602}.
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
        return java.util.Collections.unmodifiableMap(tools);
    }

    private static Tool submitDecisionsTool() {
        ObjectNode schema = Json.mapper().createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("allOrNone").put("type", "boolean");
        properties.putObject("asOfN").put("type", "integer");
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
            "Submit decision drafts through the hub's decision path, attributed to the agent user",
            schema, Set.of("allOrNone", "asOfN", "decisions"), McpWriteTools::submitDecisions);
    }

    /**
     * The {@code trex_submit_decisions} handler. {@code asOfN} is required — the {@code n} the
     * caller's view was built at — so a stale write is the hub's {@code 409}, never a silent
     * mis-post (plan §4). The outcome is mapped to a tool result and never swallowed.
     */
    static JsonNode submitDecisions(HubApi api, JsonNode args, McpConfig config) {
        long asOfN = McpArgs.requiredLong(args, "asOfN");
        Boolean allOrNone = McpArgs.optionalBool(args, "allOrNone");
        JsonNode decisions = args.get("decisions");
        if (decisions == null || !decisions.isArray() || decisions.isEmpty()) {
            throw new McpArgs.BadArgs("decisions must be a non-empty array");
        }
        List<DecisionDraft> drafts = McpAttribution.attribute(decisions, config.agentUser());
        DecisionOutcome outcome = api.submitDecisions(
            new DecisionRequest(allOrNone, asOfN, drafts));
        return mapOutcome(outcome);
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
