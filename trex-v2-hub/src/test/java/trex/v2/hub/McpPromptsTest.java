package trex.v2.hub;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import trex.v2.log.Json;

import java.lang.reflect.Proxy;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The MCP prompts (V2-MCP-SERVER-PLAN.md §3.4, Stage 3). Prompts are static text, so no hub read is
 * needed; the {@link HubApi} double exists only because {@link McpApi} is constructed with one.
 */
class McpPromptsTest {

    private static final McpConfig READ_ONLY = new McpConfig(false);

    @Test
    void promptsListIsStable() {
        JsonNode prompts = dispatch("prompts/list", null).get("result").get("prompts");
        Set<String> names = new LinkedHashSet<>();
        prompts.forEach(p -> names.add(p.get("name").asText()));
        assertEquals(Set.of("monthly_review", "classify_transaction", "reconcile_account", "mark_paid"),
            names, prompts.toString());

        JsonNode classify = null;
        for (JsonNode prompt : prompts) {
            if ("classify_transaction".equals(prompt.get("name").asText())) {
                classify = prompt;
            }
        }
        assertTrue(classify != null, prompts.toString());
        assertTrue(classify.get("description").asText().length() > 0);
        JsonNode args = classify.get("arguments");
        assertEquals(1, args.size());
        assertEquals("externalId", args.get(0).get("name").asText());
        assertTrue(args.get(0).get("required").asBoolean(), args.toString());
    }

    @Test
    void aPromptReturnsItsMessages() {
        JsonNode result = dispatch("prompts/get",
            "{\"name\":\"reconcile_account\",\"arguments\":{\"account\":\"acct:1\"}}").get("result");
        assertTrue(result.get("description").asText().length() > 0, result.toString());
        JsonNode message = result.get("messages").get(0);
        assertEquals("user", message.get("role").asText());
        assertEquals("text", message.get("content").get("type").asText());
        String text = message.get("content").get("text").asText();
        assertTrue(text.contains("trex_submit_decisions"), text);
        assertTrue(text.contains("trex_reconcile"), text);

        // mark_paid is the other prompt that proposes a decision.
        JsonNode markPaid = dispatch("prompts/get",
            "{\"name\":\"mark_paid\",\"arguments\":{}}").get("result");
        assertTrue(markPaid.get("messages").get(0).get("content").get("text").asText()
            .contains("trex_submit_decisions"), markPaid.toString());
    }

    @Test
    void anUnknownPromptIsInvalidParams() {
        JsonNode response = dispatch("prompts/get", "{\"name\":\"nope\",\"arguments\":{}}");
        assertTrue(response.has("error"), response.toString());
        assertEquals(-32602, response.get("error").get("code").asInt());
    }

    // ---- helpers -----------------------------------------------------------------------------

    private static JsonNode dispatch(String method, String paramsJson) {
        String json = paramsJson == null
            ? "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\"}"
            : "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\",\"params\":" + paramsJson + "}";
        return new McpApi(stub()).dispatch(Json.readTree(json), READ_ONLY);
    }

    /** A {@link HubApi} that refuses every read; the prompt tests never reach one. */
    private static HubApi stub() {
        return (HubApi) Proxy.newProxyInstance(
            HubApi.class.getClassLoader(), new Class<?>[] {HubApi.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "toString" -> "HubApiStub";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }
}
