package trex.v2.hub;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import trex.v2.log.Json;
import trex.v2.sequencer.api.DecisionDraft;

import java.util.ArrayList;
import java.util.List;

/**
 * MCP decision attribution (V2-MCP-SERVER-PLAN.md D4): an agent acting for the person is a distinct,
 * permanent user, never the person. A draft naming any other user is rejected outright, so an agent
 * can never silently post as {@code ron}; every accepted draft is stamped with the configured agent
 * and defaults {@code actor = "user"} (the same actor the UI writes in {@code decisions.js}).
 *
 * <p>The copies are bound to {@link DecisionDraft} by the same strict mapper the rest of the hub
 * uses; a malformed draft (an unknown field, a wrong type) raises a {@link RuntimeException} the
 * dispatcher turns into a tool error, never a crash.
 */
final class McpAttribution {

    private McpAttribution() {}

    /**
     * Copy each decision object, reject one that names a different user, force {@code user =
     * agentUser}, default a missing {@code actor}, and bind the result to {@link DecisionDraft}.
     */
    static List<DecisionDraft> attribute(JsonNode decisions, String agentUser) {
        List<JsonNode> attributed = new ArrayList<>();
        for (JsonNode decision : decisions) {
            if (decision == null || !decision.isObject()) {
                throw new McpArgs.BadArgs("each decision must be an object");
            }
            ObjectNode copy = decision.deepCopy();
            JsonNode user = copy.get("user");
            if (user != null && !user.isNull() && !agentUser.equals(user.asText())) {
                throw new McpArgs.BadArgs("decisions may only be attributed to " + agentUser);
            }
            copy.put("user", agentUser);
            if (!copy.hasNonNull("actor")) {
                copy.put("actor", "user");
            }
            attributed.add(copy);
        }
        return Json.mapper().convertValue(attributed,
            new TypeReference<List<DecisionDraft>>() {});
    }
}
