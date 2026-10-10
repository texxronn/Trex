package trex.v2.hub;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import trex.v2.hub.api.HeadResponse;
import trex.v2.hub.api.RefdataResponse;
import trex.v2.hub.api.StatusResponse;
import trex.v2.log.Json;

import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The MCP resources (V2-MCP-SERVER-PLAN.md §3.3, §4, Stage 3). The {@link HubApi} double is a
 * {@link Proxy} that serves {@link #configFile} from a map and answers the status/refdata reads, so
 * the test can assert what a resource carries without a hub or a config dir.
 */
class McpResourcesTest {

    private static final McpConfig READ_ONLY = new McpConfig(false);

    private static final StatusResponse STATUS = new StatusResponse(42, 42, 42, 0,
        Map.of("txn_current", 3L), Map.of(), "cfg/1", "derive/2", "hash/1",
        LocalDate.of(2026, 10, 1), List.of());
    private static final HeadResponse HEAD = new HeadResponse(42, 42, 42, 0);
    private static final RefdataResponse REFDATA = new RefdataResponse(
        List.of(new RefdataResponse.AccountJson("acct:1", "EUR", "statement", 3, "#fff")),
        List.of(new RefdataResponse.UserJson("ron", "Ron", true, "weekly")),
        List.of("food"), "cfg/1", "derive/2", "hash/1");

    @Test
    void configResourcesAreReadFromTheConfigDir() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("accounts.yaml", "account: acct:1\n");
        HubApi stub = stub(files);

        JsonNode read = dispatch(stub, "resources/read",
            "{\"uri\":\"trex://config/accounts\"}").get("result");
        JsonNode content = read.get("contents").get(0);
        assertEquals("trex://config/accounts", content.get("uri").asText());
        assertEquals("text/yaml", content.get("mimeType").asText());
        assertEquals("account: acct:1\n", content.get("text").asText());

        // Every exposed config URI is listed with the seeded file as its name.
        JsonNode resources = dispatch(stub, "resources/list", null).get("result").get("resources");
        Map<String, String> byUri = new LinkedHashMap<>();
        resources.forEach(r -> byUri.put(r.get("uri").asText(), r.get("name").asText()));
        assertEquals("accounts.yaml", byUri.get("trex://config/accounts"));
        assertEquals("categories.yaml", byUri.get("trex://config/categories"));
        assertEquals("transfers.yaml", byUri.get("trex://config/transfers"));
        assertEquals("firefly.yaml", byUri.get("trex://config/firefly"));
        assertEquals("profiles.yaml", byUri.get("trex://config/profiles"));
        assertTrue(byUri.containsKey("trex://status"), byUri.toString());
        assertTrue(byUri.containsKey("trex://reference"), byUri.toString());
    }

    @Test
    void statusAndReferenceAreJsonSnapshots() {
        HubApi stub = stub(Map.of());

        JsonNode status = dispatch(stub, "resources/read",
            "{\"uri\":\"trex://status\"}").get("result").get("contents").get(0);
        assertEquals("application/json", status.get("mimeType").asText());
        JsonNode snapshot = Json.readTree(status.get("text").asText());
        assertEquals(42, snapshot.get("status").get("n").asInt());
        assertEquals(42, snapshot.get("head").get("n").asInt());

        JsonNode reference = dispatch(stub, "resources/read",
            "{\"uri\":\"trex://reference\"}").get("result").get("contents").get(0);
        assertEquals("application/json", reference.get("mimeType").asText());
        assertTrue(reference.get("text").asText().contains("acct:1"));
    }

    @Test
    void aMissingConfigFileIsNotAnError() {
        // The file has not been seeded: still a well-formed, empty resource.
        JsonNode response = dispatch(stub(Map.of()), "resources/read",
            "{\"uri\":\"trex://config/transfers\"}");
        assertTrue(response.has("result"), response.toString());
        assertFalse(response.has("error"), response.toString());
        assertEquals("", response.get("result").get("contents").get(0).get("text").asText());
    }

    @Test
    void anUnknownResourceIsNotFound() {
        JsonNode response = dispatch(stub(Map.of()), "resources/read",
            "{\"uri\":\"trex://nope\"}");
        assertTrue(response.has("error"), response.toString());
        assertEquals(-32002, response.get("error").get("code").asInt());
    }

    // ---- helpers -----------------------------------------------------------------------------

    private static JsonNode dispatch(HubApi api, String method, String paramsJson) {
        String json = paramsJson == null
            ? "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\"}"
            : "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\",\"params\":" + paramsJson + "}";
        return new McpApi(api).dispatch(Json.readTree(json), READ_ONLY);
    }

    /** A {@link HubApi} that serves config files from {@code files} and the status/refdata reads. */
    private static HubApi stub(Map<String, String> files) {
        return (HubApi) Proxy.newProxyInstance(
            HubApi.class.getClassLoader(), new Class<?>[] {HubApi.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "configFile" -> Optional.ofNullable(files.get((String) args[0]));
                case "status" -> STATUS;
                case "head" -> HEAD;
                case "refdata" -> REFDATA;
                case "toString" -> "HubApiStub";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }
}
