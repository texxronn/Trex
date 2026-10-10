package trex.v2.hub;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import trex.v2.log.Json;

import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The MCP read tools (V2-MCP-SERVER-PLAN.md §3.1, §4, Stage 2), called through the dispatcher. The
 * {@link HubApi} double is a {@link Proxy} that records the last call per method and returns a null
 * stub value, so a test can assert which hub read a tool reached and with what arguments.
 */
class McpToolsTest {

    private static final McpConfig READ_ONLY = new McpConfig(false);

    @Test
    void everyReadToolCallsTheHubApi() {
        record Case(String tool, String args, String method) {}
        Case[] cases = {
            new Case("trex_status", "{}", "status"),
            new Case("trex_units", "{}", "units"),
            new Case("trex_ledger", "{}", "ledger"),
            new Case("trex_review", "{}", "review"),
            new Case("trex_commitments", "{}", "commitments"),
            new Case("trex_commitment_activity", "{\"id\":\"cand|1\"}", "activity"),
            new Case("trex_expected", "{}", "expected"),
            new Case("trex_accounts", "{}", "accounts"),
            new Case("trex_opening", "{}", "opening"),
            new Case("trex_reconcile", "{}", "reconcile"),
            new Case("trex_transfers", "{}", "transfers"),
            new Case("trex_workbook", "{}", "workbook"),
            new Case("trex_notes", "{\"externalId\":\"id1\"}", "notes"),
            new Case("trex_chains", "{}", "chains"),
            new Case("trex_since", "{\"n\":5}", "since"),
            new Case("trex_ingests", "{}", "ingests"),
            new Case("trex_config_drift", "{}", "configDrift"),
            new Case("trex_projection", "{}", "projection"),
            new Case("trex_get", "{\"path\":\"/api/units\"}", "units"),
        };
        for (Case c : cases) {
            Recorder recorder = new Recorder();
            JsonNode result = callResult(recorder.mcp(), c.tool(), c.args());
            assertFalse(result.get("isError").asBoolean(), c.tool() + ": " + result);
            assertTrue(recorder.called(c.method()), c.tool() + " did not call " + c.method());
            if (c.method().equals("status")) {
                assertTrue(recorder.called("head"), "trex_status must also read head()");
            }
        }
    }

    @Test
    void aLedgerQueryKeepsItsFilters() {
        Recorder recorder = new Recorder();
        JsonNode result = callResult(recorder.mcp(), "trex_ledger",
            "{\"account\":\"acct:1\",\"category\":\"food\",\"leg\":\"MATCHED\",\"role\":\"transaction\","
                + "\"direction\":\"out\",\"from\":\"2026-01-01\",\"to\":\"2026-02-01\",\"q\":\"milk\","
                + "\"minAmount\":100,\"maxAmount\":5000,\"hasReview\":true,\"sort\":\"amount\","
                + "\"order\":\"asc\",\"limit\":50,\"offset\":10}");
        assertFalse(result.get("isError").asBoolean(), result.toString());
        BlotterQuery query = (BlotterQuery) recorder.last("ledger")[0];
        assertEquals("acct:1", query.account());
        assertEquals("food", query.category());
        assertEquals("MATCHED", query.leg());
        assertEquals("transaction", query.role());
        assertEquals("out", query.direction());
        assertEquals(java.time.LocalDate.of(2026, 1, 1), query.from());
        assertEquals(java.time.LocalDate.of(2026, 2, 1), query.to());
        assertEquals("milk", query.q());
        assertEquals(100L, query.minAmount());
        assertEquals(5000L, query.maxAmount());
        assertTrue(query.hasReview());
        assertEquals("amount", query.sort());
        assertEquals("asc", query.order());
        assertEquals(50, query.limit());
        assertEquals(10, query.offset());
    }

    @Test
    void limitsAreClampedLikeTheHub() {
        assertEquals(BlotterQuery.DEFAULT_LIMIT, ledgerLimit("{}"));
        assertEquals(1, ledgerLimit("{\"limit\":0}"));
        assertEquals(BlotterQuery.MAX_LIMIT, ledgerLimit("{\"limit\":5000}"));
    }

    @Test
    void aMissingRequiredArgumentIsAToolError() {
        assertToolError("trex_notes", "{}");
        assertToolError("trex_since", "{}");
        assertToolError("trex_commitment_activity", "{}");
        // A present field with the wrong type is the same tool error, never a crash.
        assertToolError("trex_notes", "{\"externalId\":5}");
        assertToolError("trex_since", "{\"n\":\"not-a-number\"}");
        // The schema types are strict: an integer is not a numeric string, a boolean is not "true".
        assertToolError("trex_since", "{\"n\":\"5\"}");
        assertToolError("trex_ledger", "{\"limit\":\"50\"}");
        assertToolError("trex_ledger", "{\"hasReview\":\"true\"}");
    }

    @Test
    void theGetPassthroughIsAllowListed() {
        Recorder allowed = new Recorder();
        JsonNode ok = callResult(allowed.mcp(), "trex_get",
            "{\"path\":\"/api/ledger\",\"account\":\"acct:1\"}");
        assertFalse(ok.get("isError").asBoolean(), ok.toString());
        assertEquals("acct:1", ((BlotterQuery) allowed.last("ledger")[0]).account());

        Recorder unknown = new Recorder();
        JsonNode bad = callResult(unknown.mcp(), "trex_get", "{\"path\":\"/api/secret\"}");
        assertTrue(bad.get("isError").asBoolean(), bad.toString());
        assertFalse(unknown.called("status"), "an unknown path must reach no hub read");

        // A query param the chosen route does not declare is rejected too.
        Recorder stray = new Recorder();
        JsonNode extra = callResult(stray.mcp(), "trex_get",
            "{\"path\":\"/api/units\",\"account\":\"acct:1\"}");
        assertTrue(extra.get("isError").asBoolean(), extra.toString());
    }

    @Test
    void anUnknownToolIsAnInvalidParamsError() {
        JsonNode response = dispatch(new Recorder().mcp(), "trex_absent", "{}");
        assertTrue(response.has("error"), response.toString());
        assertEquals(-32602, response.get("error").get("code").asInt());
    }

    // ---- helpers -----------------------------------------------------------------------------

    private int ledgerLimit(String args) {
        Recorder recorder = new Recorder();
        JsonNode result = callResult(recorder.mcp(), "trex_ledger", args);
        assertFalse(result.get("isError").asBoolean(), result.toString());
        return ((BlotterQuery) recorder.last("ledger")[0]).limit();
    }

    private void assertToolError(String tool, String args) {
        JsonNode result = callResult(new Recorder().mcp(), tool, args);
        assertTrue(result.get("isError").asBoolean(), tool + " " + args + " -> " + result);
    }

    private static JsonNode dispatch(McpApi api, String name, String argsJson) {
        return api.dispatch(request(name, argsJson), READ_ONLY);
    }

    private static JsonNode callResult(McpApi api, String name, String argsJson) {
        return dispatch(api, name, argsJson).get("result");
    }

    private static JsonNode request(String name, String argsJson) {
        String params = "{\"name\":\"" + name + "\",\"arguments\":" + argsJson + "}";
        return Json.readTree(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":" + params + "}");
    }

    /** A {@link HubApi} that records the last call per method and answers every read with null. */
    private static final class Recorder {
        private final Map<String, Object[]> calls = new LinkedHashMap<>();
        private final McpApi mcp;

        Recorder() {
            HubApi api = (HubApi) Proxy.newProxyInstance(
                HubApi.class.getClassLoader(), new Class<?>[] {HubApi.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "toString":
                            return "HubApiRecorder";
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        default:
                            calls.put(method.getName(), args == null ? new Object[0] : args);
                            return null;
                    }
                });
            this.mcp = new McpApi(api);
        }

        McpApi mcp() {
            return mcp;
        }

        boolean called(String method) {
            return calls.containsKey(method);
        }

        Object[] last(String method) {
            return calls.get(method);
        }
    }
}
