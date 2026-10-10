package trex.v2.hub;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import trex.v2.hub.api.DecisionRequest;
import trex.v2.hub.api.ErrorResponse;
import trex.v2.hub.api.RefdataResponse;
import trex.v2.log.Json;
import trex.v2.sequencer.api.DecisionDraft;

import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The opt-in MCP write tool (V2-MCP-SERVER-PLAN.md §3.2, §4, Stage 4): present only when writes are
 * enabled, attributed to the configured agent, and surfacing the hub's {@code 409} rather than
 * swallowing it. The {@link HubApi} double is a {@link Proxy} that answers {@code submitDecisions}
 * with a canned {@link DecisionOutcome} and records the request.
 */
class McpWriteToolsTest {

    private static final McpConfig READ_ONLY = new McpConfig(false);
    private static final McpConfig WRITES = new McpConfig(true);

    private static final String ONE_DECISION =
        "{\"asOfN\":42,\"decisions\":[{\"action\":\"MARK_EXTERNAL\",\"externalId\":\"id1\"}]}";

    @Test
    void writeToolsAreHiddenUnlessOptedIn() {
        Stub off = new Stub();
        JsonNode offList = off.mcp().dispatch(request("tools/list", null), READ_ONLY)
            .get("result").get("tools");
        assertFalse(names(offList).contains("trex_submit_decisions"),
            "the write tool is absent from a read-only tools/list");
        assertFalse(names(offList).contains("trex_categorize"),
            "the action tools are absent from a read-only tools/list");
        assertFalse(names(offList).contains("trex_note"), "no action tool with writes off");
        assertFalse(names(offList).contains("trex_mark_paid"), "no action tool with writes off");

        JsonNode refused = off.mcp().dispatch(
            request("tools/call", call("trex_submit_decisions", ONE_DECISION)), READ_ONLY);
        assertTrue(refused.has("error"), refused.toString());
        assertEquals(-32602, refused.get("error").get("code").asInt(), refused.toString());
        assertFalse(off.submitted(), "a refused write must reach no hub write");

        Stub on = new Stub();
        JsonNode onList = on.mcp().dispatch(request("tools/list", null), WRITES)
            .get("result").get("tools");
        assertTrue(names(onList).contains("trex_submit_decisions"),
            "the write tool is present when writes are opted in");
        assertTrue(names(onList).containsAll(List.of("trex_categorize", "trex_note", "trex_mark_paid")),
            "the three action tools are present when writes are opted in");
        // The read tools are still all there, so the merge is additive.
        assertTrue(names(onList).containsAll(names(
            off.mcp().dispatch(request("tools/list", null), READ_ONLY).get("result").get("tools"))));
    }

    @Test
    void submitDecisionsRequiresAnAsOfToken() {
        Stub stub = new Stub();
        JsonNode noAsOf = callResult(stub.mcp(), WRITES,
            "{\"decisions\":[{\"action\":\"MARK_EXTERNAL\",\"externalId\":\"id1\"}]}");
        assertTrue(noAsOf.get("isError").asBoolean(), noAsOf.toString());
        assertFalse(stub.submitted(), "a draft without asOfN must not be posted");

        JsonNode wrongType = callResult(stub.mcp(), WRITES,
            "{\"asOfN\":\"not-a-number\",\"decisions\":[{\"action\":\"MARK_EXTERNAL\"}]}");
        assertTrue(wrongType.get("isError").asBoolean(), wrongType.toString());

        JsonNode noDecisions = callResult(stub.mcp(), WRITES, "{\"asOfN\":42}");
        assertTrue(noDecisions.get("isError").asBoolean(), noDecisions.toString());

        JsonNode empty = callResult(stub.mcp(), WRITES,
            "{\"asOfN\":42,\"decisions\":[]}");
        assertTrue(empty.get("isError").asBoolean(), empty.toString());
        assertFalse(stub.submitted(), "no invalid call may reach the hub");
    }

    @Test
    void aStaleAsOfSurfacesTheHubs409() {
        String hubBody = "view moved: the index is at n=9, the request was built at n=7";
        Stub stub = new Stub(new DecisionOutcome(409, new ErrorResponse(hubBody)));
        JsonNode result = callResult(stub.mcp(), WRITES, ONE_DECISION);
        assertTrue(result.get("isError").asBoolean(), result.toString());
        String text = result.get("content").get(0).get("text").asText();
        assertTrue(text.contains("view moved"), text);
        assertTrue(text.contains("re-read and rebuild the draft"), text);
        assertTrue(text.contains(hubBody), "the hub's own explanation is carried: " + text);
        assertEquals(hubBody, result.get("structuredContent").get("error").asText(),
            "the hub's body is structured content, not swallowed");
    }

    @Test
    void attributionForcesTheAgentUser() {
        Stub stub = new Stub();
        JsonNode result = callResult(stub.mcp(), WRITES, ONE_DECISION);
        assertFalse(result.get("isError").asBoolean(), result.toString());
        assertTrue(stub.submitted());
        DecisionRequest request = stub.lastRequest();
        assertNotNull(request);
        assertEquals(42L, request.asOfN());
        assertEquals(1, request.decisions().size());
        DecisionDraft draft = request.decisions().get(0);
        assertEquals("agent", draft.user(), "the configured agent user is stamped");
        assertEquals("user", draft.actor(), "a missing actor defaults to the decision actor");

        // A supplied agent user is accepted unchanged, and a custom agent is honoured.
        Stub custom = new Stub();
        JsonNode customResult = callResult(custom.mcp(), new McpConfig(true, "trex-agent"), ONE_DECISION);
        assertFalse(customResult.get("isError").asBoolean(), customResult.toString());
        assertEquals("trex-agent", custom.lastRequest().decisions().get(0).user());
    }

    @Test
    void aSuppliedActorIsForcedToUser() {
        // The sequencer nulls the user for any actor other than "user"; the tool must force it, or a
        // client could erase the attribution this gate exists to keep.
        Stub stub = new Stub();
        JsonNode result = callResult(stub.mcp(), WRITES,
            "{\"asOfN\":42,\"decisions\":[{\"action\":\"MARK_EXTERNAL\",\"actor\":\"system\","
                + "\"externalId\":\"id1\"}]}");
        assertFalse(result.get("isError").asBoolean(), result.toString());
        DecisionDraft draft = stub.lastRequest().decisions().get(0);
        assertEquals("user", draft.actor(), "a client-supplied actor must be forced to user");
        assertEquals("agent", draft.user(), "and the agent user must survive the write");
    }

    @Test
    void aDraftNamingAnotherUserIsRejected() {
        Stub stub = new Stub();
        JsonNode result = callResult(stub.mcp(), WRITES,
            "{\"asOfN\":42,\"decisions\":[{\"action\":\"MARK_EXTERNAL\",\"externalId\":\"id1\","
                + "\"user\":\"ron\"}]}");
        assertTrue(result.get("isError").asBoolean(), result.toString());
        String text = result.get("content").get(0).get("text").asText();
        assertTrue(text.contains("decisions may only be attributed to agent"), text);
        assertFalse(stub.submitted(), "a draft naming another user must not be posted");
    }

    @Test
    void anActingUserMustBeDeclared() {
        Stub stub = new Stub();
        JsonNode unknown = callResult(stub.mcp(), WRITES, "trex_submit_decisions",
            "{\"asOfN\":42,\"actingUser\":\"mallory\","
                + "\"decisions\":[{\"action\":\"MARK_EXTERNAL\",\"externalId\":\"id1\"}]}");
        assertTrue(unknown.get("isError").asBoolean(), unknown.toString());
        String text = unknown.get("content").get(0).get("text").asText();
        assertTrue(text.contains("agent") && text.contains("ron") && text.contains("mel"),
            "the tool error names the allowed set: " + text);
        assertFalse(stub.submitted(), "an undeclared acting user must not be posted");

        // A declared-but-inactive user is not a valid acting user either.
        JsonNode inactive = callResult(stub.mcp(), WRITES, "trex_submit_decisions",
            "{\"asOfN\":42,\"actingUser\":\"former\","
                + "\"decisions\":[{\"action\":\"MARK_EXTERNAL\",\"externalId\":\"id1\"}]}");
        assertTrue(inactive.get("isError").asBoolean(), inactive.toString());
        assertFalse(stub.submitted(), "an inactive user must not be posted");
    }

    @Test
    void aBlankActingUserIsRejected() {
        Stub stub = new Stub();
        JsonNode blank = callResult(stub.mcp(), WRITES, "trex_submit_decisions",
            "{\"asOfN\":42,\"actingUser\":\"  \","
                + "\"decisions\":[{\"action\":\"MARK_EXTERNAL\",\"externalId\":\"id1\"}]}");
        assertTrue(blank.get("isError").asBoolean(), blank.toString());
        assertFalse(stub.submitted(), "a blank acting user must not be silently defaulted");

        // An explicit null is malformed, not an omission.
        JsonNode nullUser = callResult(stub.mcp(), WRITES, "trex_submit_decisions",
            "{\"asOfN\":42,\"actingUser\":null,"
                + "\"decisions\":[{\"action\":\"MARK_EXTERNAL\",\"externalId\":\"id1\"}]}");
        assertTrue(nullUser.get("isError").asBoolean(), nullUser.toString());
        assertFalse(stub.submitted(), "an explicit null acting user must be rejected");

        // Omitted is fine, and means the agent.
        Stub omitted = new Stub();
        assertFalse(callResult(omitted.mcp(), WRITES, "trex_submit_decisions",
            "{\"asOfN\":42,\"decisions\":[{\"action\":\"MARK_EXTERNAL\",\"externalId\":\"id1\"}]}")
            .get("isError").asBoolean());
        assertEquals("agent", omitted.lastRequest().decisions().get(0).user());
    }

    @Test
    void actingUserIsStamped() {
        Stub stub = new Stub();
        JsonNode result = callResult(stub.mcp(), WRITES, "trex_submit_decisions",
            "{\"asOfN\":42,\"actingUser\":\"ron\","
                + "\"decisions\":[{\"action\":\"MARK_EXTERNAL\",\"externalId\":\"id1\"}]}");
        assertFalse(result.get("isError").asBoolean(), result.toString());
        DecisionDraft draft = only(stub);
        assertEquals("ron", draft.user(), "the declared acting user is stamped on the draft");
        assertEquals("user", draft.actor(), "the actor is still forced to user");
    }

    @Test
    void agentIsTheDefaultActingUser() {
        Stub stub = new Stub();
        JsonNode result = callResult(stub.mcp(), WRITES, ONE_DECISION);
        assertFalse(result.get("isError").asBoolean(), result.toString());
        assertEquals("agent", only(stub).user(), "an absent actingUser defaults to the agent");
    }

    @Test
    void everyActionToolBuildsItsDraft() {
        Stub pin = new Stub();
        JsonNode pinResult = callResult(pin.mcp(), WRITES, "trex_categorize",
            "{\"asOfN\":42,\"externalIds\":[\"id1\",\"id2\"],\"category\":\"Groceries\"}");
        assertFalse(pinResult.get("isError").asBoolean(), pinResult.toString());
        DecisionDraft pinDraft = only(pin);
        assertEquals("PIN", pinDraft.action());
        assertEquals(List.of("id1", "id2"), pinDraft.externalIds());
        assertEquals("Groceries", pinDraft.category());
        assertEquals("user", pinDraft.actor());
        assertEquals("agent", pinDraft.user());

        Stub note = new Stub();
        JsonNode noteResult = callResult(note.mcp(), WRITES, "trex_note",
            "{\"asOfN\":42,\"externalId\":\"id1\",\"text\":\"split this\"}");
        assertFalse(noteResult.get("isError").asBoolean(), noteResult.toString());
        DecisionDraft noteDraft = only(note);
        assertEquals("NOTE", noteDraft.action());
        assertEquals("id1", noteDraft.externalId());
        assertEquals("split this", noteDraft.text());

        Stub paid = new Stub();
        JsonNode paidResult = callResult(paid.mcp(), WRITES, "trex_mark_paid",
            "{\"asOfN\":42,\"commitmentId\":\"c1\",\"dueDates\":[\"2026-10-01\",\"2026-11-01\"]}");
        assertFalse(paidResult.get("isError").asBoolean(), paidResult.toString());
        DecisionDraft paidDraft = only(paid);
        assertEquals("SETTLE_OCCURRENCE", paidDraft.action());
        assertEquals("c1", paidDraft.commitmentId());
        assertEquals(List.of(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-11-01")),
            paidDraft.dueDates());
    }

    @Test
    void anActionToolRequiresAsOfN() {
        Stub stub = new Stub();
        String[][] calls = {
            {"trex_categorize", "{\"externalIds\":[\"id1\"],\"category\":\"Groceries\"}"},
            {"trex_note", "{\"externalId\":\"id1\",\"text\":\"hi\"}"},
            {"trex_mark_paid", "{\"commitmentId\":\"c1\",\"dueDates\":[\"2026-10-01\"]}"},
        };
        for (String[] call : calls) {
            JsonNode result = callResult(stub.mcp(), WRITES, call[0], call[1]);
            assertTrue(result.get("isError").asBoolean(), call[0] + ": " + result);
        }
        assertFalse(stub.submitted(), "a call without asOfN must not be posted");

        // A bad argument is a tool error, never a crash or a hub write.
        assertTrue(callResult(stub.mcp(), WRITES, "trex_categorize",
            "{\"asOfN\":42,\"externalIds\":[],\"category\":\"Groceries\"}")
            .get("isError").asBoolean(), "an empty externalIds is a tool error");
        assertTrue(callResult(stub.mcp(), WRITES, "trex_note",
            "{\"asOfN\":42,\"externalId\":\"id1\"}").get("isError").asBoolean(),
            "a missing text is a tool error");
        assertTrue(callResult(stub.mcp(), WRITES, "trex_mark_paid",
            "{\"asOfN\":42,\"commitmentId\":\"c1\",\"dueDates\":[\"not-a-date\"]}")
            .get("isError").asBoolean(), "a bad date is a tool error");
        assertFalse(stub.submitted(), "no invalid action may reach the hub");
    }

    @Test
    void egressIsNotATool() {
        Stub stub = new Stub();
        JsonNode tools = stub.mcp().dispatch(request("tools/list", null), WRITES)
            .get("result").get("tools");
        for (JsonNode tool : tools) {
            String name = tool.get("name").asText();
            assertFalse(name.toLowerCase().contains("egress"), "no egress tool: " + name);
            assertFalse(name.toLowerCase().contains("firefly"), "no firefly tool: " + name);
            assertFalse(name.toLowerCase().contains("job"), "no job tool (Stage 5): " + name);
        }
        JsonNode egress = stub.mcp().dispatch(
            request("tools/call", call("trex_egress_firefly", "{}")), WRITES);
        assertTrue(egress.has("error"), egress.toString());
        assertEquals(-32602, egress.get("error").get("code").asInt(), egress.toString());
    }

    // ---- helpers -----------------------------------------------------------------------------

    private static JsonNode callResult(McpApi api, McpConfig config, String argsJson) {
        return callResult(api, config, "trex_submit_decisions", argsJson);
    }

    private static JsonNode callResult(McpApi api, McpConfig config, String name, String argsJson) {
        return api.dispatch(request("tools/call", call(name, argsJson)), config).get("result");
    }

    /** The single draft the stub recorded; fails when nothing or more than one was posted. */
    private static DecisionDraft only(Stub stub) {
        assertNotNull(stub.lastRequest(), "a draft must have reached the hub");
        assertEquals(1, stub.lastRequest().decisions().size());
        return stub.lastRequest().decisions().get(0);
    }

    private static String call(String name, String argsJson) {
        return "{\"name\":\"" + name + "\",\"arguments\":" + argsJson + "}";
    }

    private static JsonNode request(String method, String paramsJson) {
        String json = paramsJson == null
            ? "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\"}"
            : "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\",\"params\":" + paramsJson + "}";
        return Json.readTree(json);
    }

    private static TreeSet<String> names(JsonNode tools) {
        TreeSet<String> names = new TreeSet<>();
        tools.forEach(t -> names.add(t.get("name").asText()));
        return names;
    }

    /** A {@link HubApi} that answers {@code submitDecisions}, {@code refdata} and records the request. */
    private static final class Stub {
        private static final RefdataResponse REFDATA = new RefdataResponse(
            List.of(),
            List.of(new RefdataResponse.UserJson("ron", "Ron", true, "daily"),
                new RefdataResponse.UserJson("mel", "Mel", true, "daily"),
                new RefdataResponse.UserJson("agent", "Agent", true, "daily"),
                new RefdataResponse.UserJson("former", "Former", false, "daily")),
            List.of(), "config@1", "derive@1", "hash@1");

        private final DecisionOutcome outcome;
        private DecisionRequest lastRequest;
        private boolean submitted;

        Stub() {
            this(new DecisionOutcome(200, Map.of("batchStatus", "applied")));
        }

        Stub(DecisionOutcome outcome) {
            this.outcome = outcome;
        }

        McpApi mcp() {
            HubApi api = (HubApi) Proxy.newProxyInstance(
                HubApi.class.getClassLoader(), new Class<?>[] {HubApi.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "submitDecisions" -> {
                        submitted = true;
                        lastRequest = (DecisionRequest) args[0];
                        yield outcome;
                    }
                    case "refdata" -> REFDATA;
                    case "toString" -> "HubApiStub";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
            return new McpApi(api);
        }

        boolean submitted() {
            return submitted;
        }

        DecisionRequest lastRequest() {
            return lastRequest;
        }
    }
}
