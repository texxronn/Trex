package trex.v2.hub;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import trex.v2.hub.api.HeadResponse;
import trex.v2.hub.api.StatusResponse;
import trex.v2.log.Json;

import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The MCP JSON-RPC core (V2-MCP-SERVER-PLAN.md §1, §3.1, Stage 1), called directly with no HTTP in
 * the way. The {@link HubApi} double is a {@link Proxy} so the test does not have to implement the
 * whole read surface — it answers {@code status()} and {@code head()} and refuses everything else.
 */
class McpApiTest {

    private static final McpConfig READ_ONLY = new McpConfig(false);

    private static final HubApi STUB = stub();

    private static McpApi api() {
        return new McpApi(STUB);
    }

    @Test
    void initializeNegotiatesAProtocolVersion() {
        JsonNode known = api().dispatch(request("initialize",
            "{\"protocolVersion\":\"2025-06-18\"}"), READ_ONLY);
        assertEquals("2.0", known.get("jsonrpc").asText());
        assertEquals(1, known.get("id").asInt());
        assertEquals("2025-06-18", known.get("result").get("protocolVersion").asText(),
            "a known client version is echoed");
        assertEquals("trex-hub", known.get("result").get("serverInfo").get("name").asText());
        assertTrue(known.get("result").get("capabilities").has("tools"));
        assertTrue(known.get("result").get("capabilities").has("resources"));
        assertTrue(known.get("result").get("capabilities").has("prompts"));

        JsonNode unknown = api().dispatch(request("initialize",
            "{\"protocolVersion\":\"1999-01-01\"}"), READ_ONLY);
        assertEquals(McpApi.DEFAULT_PROTOCOL_VERSION,
            unknown.get("result").get("protocolVersion").asText(),
            "an unknown version falls back to the one advertised");

        // server/discover is the stateless 2026-07-28 discovery: the same capabilities shape.
        JsonNode discover = api().dispatch(request("server/discover", null), READ_ONLY);
        assertEquals(known.get("result").get("capabilities"),
            discover.get("result").get("capabilities"));
    }

    @Test
    void aStatelessRequestIsAnsweredWithoutInitialize() {
        // No initialize was ever sent; tools/list and tools/call still answer normally.
        JsonNode list = api().dispatch(request("tools/list", null), READ_ONLY);
        assertTrue(list.get("result").get("tools").isArray());
        assertTrue(list.has("id"));

        JsonNode call = api().dispatch(
            request("tools/call", "{\"name\":\"trex_status\",\"arguments\":{}}"), READ_ONLY);
        assertFalse(call.get("result").get("isError").asBoolean(), call.toString());
        assertEquals(42, call.get("result").get("structuredContent").get("status").get("n").asInt());
        assertEquals(42, call.get("result").get("structuredContent").get("head").get("n").asInt());
        assertTrue(call.get("result").get("content").get(0).get("text").asText().contains("\"status\""));
    }

    @Test
    void anUnknownMethodIsMethodNotFound() {
        JsonNode response = api().dispatch(request("trex/nope", null), READ_ONLY);
        assertTrue(response.has("error"), response.toString());
        assertEquals(-32601, response.get("error").get("code").asInt());
        assertEquals(1, response.get("id").asInt(), "the id is echoed on an error");
    }

    @Test
    void malformedJsonIsParseError() {
        JsonNode response = api().dispatch("this is not JSON", READ_ONLY);
        assertTrue(response.has("error"), response.toString());
        assertEquals(-32700, response.get("error").get("code").asInt());
        assertEquals("Parse error", response.get("error").get("message").asText());
        assertTrue(response.get("id").isNull(), "a parse error has no id to echo");

        // A message that parses but is not a JSON-RPC request object is a -32600, not a -32700.
        JsonNode invalid = api().dispatch(Json.readTree("[1,2,3]"), READ_ONLY);
        assertEquals(-32600, invalid.get("error").get("code").asInt());
    }

    @Test
    void toolsListIsStable() {
        JsonNode tools = api().dispatch(request("tools/list", null), READ_ONLY)
            .get("result").get("tools");
        java.util.Set<String> names = new java.util.TreeSet<>();
        tools.forEach(t -> names.add(t.get("name").asText()));
        assertEquals(java.util.Set.of("trex_accounts", "trex_chains", "trex_commitment_activity",
            "trex_commitments", "trex_config_drift", "trex_expected", "trex_get", "trex_ingests",
            "trex_ledger", "trex_notes", "trex_opening", "trex_projection", "trex_reconcile",
            "trex_review", "trex_since", "trex_status", "trex_transfers", "trex_units",
            "trex_workbook"), names, tools.toString());
        JsonNode tool = null;
        for (JsonNode candidate : tools) {
            if ("trex_status".equals(candidate.get("name").asText())) {
                tool = candidate;
            }
        }
        assertTrue(tool != null, tools.toString());
        assertEquals("Trex hub status and head (asOfN, deriveVersion, counts)",
            tool.get("description").asText());
        JsonNode schema = tool.get("inputSchema");
        assertEquals("object", schema.get("type").asText());
        assertTrue(schema.get("properties").isObject());
        assertEquals(0, schema.get("properties").size());
        assertFalse(schema.get("additionalProperties").asBoolean());
    }

    @Test
    void aToolErrorBecomesAToolResultNotACrash() {
        // A known tool with bad arguments: a successful result carrying isError:true, not an error.
        JsonNode badArgs = api().dispatch(request("tools/call",
            "{\"name\":\"trex_status\",\"arguments\":{\"account\":\"nope\"}}"), READ_ONLY);
        assertTrue(badArgs.has("result"), badArgs.toString());
        assertFalse(badArgs.has("error"), badArgs.toString());
        assertTrue(badArgs.get("result").get("isError").asBoolean());
        assertFalse(badArgs.get("result").get("content").get(0).get("text").asText().isBlank());

        // An unknown tool name is a JSON-RPC -32602.
        JsonNode unknown = api().dispatch(request("tools/call",
            "{\"name\":\"trex_absent\",\"arguments\":{}}"), READ_ONLY);
        assertTrue(unknown.has("error"), unknown.toString());
        assertEquals(-32602, unknown.get("error").get("code").asInt());
    }

    @Test
    void aRequestWithoutJsonRpcIsInvalid() {
        // No jsonrpc version and no id: a malformed request, never a silent notification.
        JsonNode response = api().dispatch("{\"method\":\"tools/list\"}", READ_ONLY);
        assertTrue(response != null && response.has("error"), String.valueOf(response));
        assertEquals(-32600, response.get("error").get("code").asInt());

        JsonNode wrongVersion = api().dispatch(
            "{\"jsonrpc\":\"1.0\",\"id\":1,\"method\":\"tools/list\"}", READ_ONLY);
        assertEquals(-32600, wrongVersion.get("error").get("code").asInt());
    }

    @Test
    void anEmptyBodyIsAParseError() {
        assertEquals(-32700, api().dispatch("", READ_ONLY).get("error").get("code").asInt());
        assertEquals(-32700, api().dispatch("   ", READ_ONLY).get("error").get("code").asInt());
    }

    @Test
    void trailingJsonIsAParseError() {
        JsonNode response = api().dispatch(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"} garbage", READ_ONLY);
        assertEquals(-32700, response.get("error").get("code").asInt(), response.toString());
    }

    @Test
    void aNonStringJsonRpcVersionIsInvalid() {
        JsonNode response = api().dispatch(
            "{\"jsonrpc\":2.0,\"id\":1,\"method\":\"ping\"}", READ_ONLY);
        assertEquals(-32600, response.get("error").get("code").asInt(), response.toString());
    }

    @Test
    void aScalarParamsIsInvalidParams() {
        JsonNode response = api().dispatch(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"params\":5}", READ_ONLY);
        assertEquals(-32602, response.get("error").get("code").asInt(), response.toString());
    }

    @Test
    void explicitNullArgumentsIsAToolError() {
        JsonNode nullArgs = api().dispatch(request("tools/call",
            "{\"name\":\"trex_status\",\"arguments\":null}"), READ_ONLY);
        assertTrue(nullArgs.has("result") && !nullArgs.has("error"), nullArgs.toString());
        assertTrue(nullArgs.get("result").get("isError").asBoolean(), nullArgs.toString());
    }

    // ---- helpers -----------------------------------------------------------------------------

    private static JsonNode request(String method, String paramsJson) {
        String json = paramsJson == null
            ? "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\"}"
            : "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\",\"params\":" + paramsJson + "}";
        return Json.readTree(json);
    }

    /** A {@link HubApi} that answers only the two reads the Stage-1 tool uses. */
    private static HubApi stub() {
        StatusResponse status = new StatusResponse(42, 42, 42, 0,
            Map.of("txn_current", 3L), Map.of(), "cfg/1", "derive/2", "hash/1",
            LocalDate.of(2026, 10, 1), List.of());
        HeadResponse head = new HeadResponse(42, 42, 42, 0);
        return (HubApi) Proxy.newProxyInstance(
            HubApi.class.getClassLoader(),
            new Class<?>[] {HubApi.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "status" -> status;
                case "head" -> head;
                case "toString" -> "HubApiStub";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }
}
