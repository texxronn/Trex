package trex.v2.hub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import trex.v2.log.Json;

import java.util.List;

/**
 * The Model Context Protocol core (V2-MCP-SERVER-PLAN.md §1, §3.1, Stage 1): a hand-rolled JSON-RPC
 * 2.0 dispatcher over the hub's own read surface. It is transport-agnostic — {@link McpHttp} is the
 * Streamable HTTP binding — and <b>stateless</b>: a request that never sent {@code initialize} is
 * answered normally, which is what lets one dispatcher serve both the 2025 handshake era and the
 * 2026-07-28 stateless era (D1).
 *
 * <p>It wraps {@link HubApi} and adds no writer. Every Stage-1 method is a read.
 */
public final class McpApi {

    /**
     * The one protocol version advertised (plan §6). The dispatcher still answers an older
     * {@code initialize}, echoing the client's version when it is one we know.
     */
    static final String DEFAULT_PROTOCOL_VERSION = "2026-07-28";

    /** The protocol versions whose {@code initialize} handshake this server will echo. */
    static final List<String> SUPPORTED_PROTOCOL_VERSIONS =
        List.of("2026-07-28", "2025-11-25", "2025-06-18", "2025-03-26");

    /** Mirrors the reactor version (pom {@code 0.1.1-SNAPSHOT}); {@code serverInfo.version} is informational. */
    static final String SERVER_VERSION = "0.1.1";

    private final HubApi api;

    public McpApi(HubApi api) {
        this.api = api;
    }

    /** Parse one JSON-RPC message and dispatch it; a body that is not JSON is a {@code -32700}. */
    public JsonNode dispatch(String body, McpConfig config) {
        if (body == null || body.isBlank()) {
            return error(null, -32700, "Parse error");
        }
        JsonNode request;
        try {
            // One message per body: a trailing value or garbage must fail, not be ignored. The
            // shared mapper is left lenient for the journal; this reader is strict for the wire.
            request = Json.mapper().reader()
                .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .readTree(body);
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            return error(null, -32700, "Parse error");
        }
        if (request == null) {
            // Jackson parses a body with no JSON value (only whitespace) as null: still a syntax error.
            return error(null, -32700, "Parse error");
        }
        return dispatch(request, config);
    }

    /**
     * Dispatch one already-parsed JSON-RPC message. Returns the response envelope, or {@code null}
     * when the message is a notification (JSON-RPC object with no {@code id}), which gets no reply.
     */
    public JsonNode dispatch(JsonNode request, McpConfig config) {
        if (request == null || !request.isObject()) {
            return error(null, -32600, "Invalid Request");
        }
        JsonNode version = request.get("jsonrpc");
        if (version == null || !version.isTextual() || !"2.0".equals(version.asText())) {
            return error(request.get("id"), -32600, "Invalid Request");
        }
        JsonNode id = request.get("id");
        // An id, when present, is a string, a number or null (JSON-RPC 2.0); anything else is a
        // malformed request, never a notification, so it is validated before the notification check.
        if (id != null && !id.isNull() && !id.isTextual() && !id.isNumber()) {
            return error(null, -32600, "Invalid Request");
        }
        JsonNode methodNode = request.get("method");
        if (methodNode == null || !methodNode.isTextual()) {
            return error(id, -32600, "Invalid Request");
        }
        if (!request.has("id")) {
            // A notification: no response, whatever the method. notifications/initialized lands here.
            return null;
        }
        JsonNode params = request.get("params");
        // MCP params are an object, or omitted; an explicit null or a scalar is invalid.
        if (params != null && !params.isObject()) {
            return error(id, -32602, "Invalid params");
        }
        return switch (methodNode.asText()) {
            case "initialize" -> ok(id, initializeResult(params));
            case "server/discover" -> ok(id, initializeResult(params));
            case "ping" -> ok(id, Json.mapper().createObjectNode());
            case "tools/list" -> ok(id, toolsList(config));
            case "tools/call" -> toolCall(id, params, config);
            case "resources/list" -> ok(id, McpResources.list());
            case "resources/read" -> resourceRead(id, params);
            case "prompts/list" -> ok(id, McpPrompts.list());
            case "prompts/get" -> promptGet(id, params);
            default -> error(id, -32601, "Method not found");
        };
    }

    // ---- initialize and discovery (plan §3.1) ------------------------------------------------

    /** The {@code initialize} / {@code server/discover} result: version, capabilities, server info. */
    private ObjectNode initializeResult(JsonNode params) {
        ObjectNode result = Json.mapper().createObjectNode();
        result.put("protocolVersion", negotiate(params));
        result.set("capabilities", capabilities());
        ObjectNode serverInfo = Json.mapper().createObjectNode();
        serverInfo.put("name", "trex-hub");
        serverInfo.put("version", SERVER_VERSION);
        result.set("serverInfo", serverInfo);
        return result;
    }

    /** Echo a known {@code params.protocolVersion}, otherwise advertise the one supported version. */
    private static String negotiate(JsonNode params) {
        JsonNode requested = params == null ? null : params.get("protocolVersion");
        if (requested != null && requested.isTextual()
            && SUPPORTED_PROTOCOL_VERSIONS.contains(requested.asText())) {
            return requested.asText();
        }
        return DEFAULT_PROTOCOL_VERSION;
    }

    /** The three empty capability objects this server declares (Stage 1 has tools only, all reads). */
    private static ObjectNode capabilities() {
        ObjectNode caps = Json.mapper().createObjectNode();
        caps.set("tools", Json.mapper().createObjectNode());
        caps.set("resources", Json.mapper().createObjectNode());
        caps.set("prompts", Json.mapper().createObjectNode());
        return caps;
    }

    // ---- tools (plan §3.1) -------------------------------------------------------------------

    /**
     * The {@code tools/list} result: the read tools always, plus the write tools only when the
     * operator opted in (plan §3.2). With writes off {@code trex_submit_decisions} is absent.
     */
    private ObjectNode toolsList(McpConfig config) {
        ObjectNode result = Json.mapper().createObjectNode();
        ArrayNode tools = result.putArray("tools");
        for (McpTools.Tool tool : McpTools.tools().values()) {
            addTool(tools, tool.name(), tool.description(), tool.inputSchema());
        }
        if (config.allowWrites()) {
            for (McpWriteTools.Tool tool : McpWriteTools.tools().values()) {
                addTool(tools, tool.name(), tool.description(), tool.inputSchema());
            }
        }
        return result;
    }

    private static void addTool(ArrayNode tools, String name, String description, JsonNode schema) {
        ObjectNode node = Json.mapper().createObjectNode();
        node.put("name", name);
        node.put("description", description);
        node.set("inputSchema", schema);
        tools.add(node);
    }

    /**
     * A {@code tools/call}: an unknown tool name is a JSON-RPC {@code -32602}, but a known tool with
     * bad arguments is a successful result carrying {@code isError:true} — a tool error is data for
     * the model, not a transport failure (plan §1). The name is looked up in {@link McpTools}, and
     * in {@link McpWriteTools} only when writes are enabled, so with the gate off a write tool is
     * simply unknown. The handler receives an arguments object and any {@link RuntimeException} it
     * raises (a missing required field, a wrong type, a rejected filter) becomes that tool error.
     */
    private JsonNode toolCall(JsonNode id, JsonNode params, McpConfig config) {
        if (params == null || !params.isObject()) {
            return error(id, -32602, "Invalid params");
        }
        JsonNode nameNode = params.get("name");
        if (nameNode == null || !nameNode.isTextual()) {
            return error(id, -32602, "Invalid params");
        }
        String name = nameNode.asText();
        JsonNode arguments = params.get("arguments");
        // The schema is an object: an explicit non-object (including null) is a tool error. An
        // omitted arguments node is an empty object, so a no-argument tool is callable either way.
        if (arguments == null) {
            arguments = Json.mapper().createObjectNode();
        } else if (!arguments.isObject()) {
            return ok(id, toolError("The tool '" + name + "' takes an object of arguments"));
        }
        McpTools.Tool read = McpTools.find(name);
        if (read != null) {
            try {
                McpArgs.rejectUnknown(arguments, read.args());
                return ok(id, toolResult(read.handler().handle(api, arguments)));
            } catch (RuntimeException e) {
                return ok(id, toolError(name + ": " + message(e)));
            }
        }
        if (config.allowWrites()) {
            McpWriteTools.Tool write = McpWriteTools.find(name);
            if (write != null) {
                try {
                    McpArgs.rejectUnknown(arguments, write.args());
                    // A write handler returns the complete tool result: a hub 409 is a tool error,
                    // so it cannot be wrapped as a success the way a read always is.
                    return ok(id, write.handler().handle(api, arguments, config));
                } catch (RuntimeException e) {
                    return ok(id, toolError(name + ": " + message(e)));
                }
            }
        }
        return error(id, -32602, "Unknown tool: " + name);
    }

    private static String message(RuntimeException e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    /** The {@code tools/call} success result: compact text plus the same data as structured content. */
    static ObjectNode toolResult(JsonNode data) {
        ObjectNode result = Json.mapper().createObjectNode();
        ArrayNode content = result.putArray("content");
        ObjectNode text = content.addObject();
        text.put("type", "text");
        text.put("text", compact(data));
        result.put("isError", false);
        result.set("structuredContent", data);
        return result;
    }

    /** The {@code tools/call} error result: {@code isError:true} and a human-readable explanation. */
    static ObjectNode toolError(String message) {
        ObjectNode result = Json.mapper().createObjectNode();
        ArrayNode content = result.putArray("content");
        ObjectNode text = content.addObject();
        text.put("type", "text");
        text.put("text", message);
        result.put("isError", true);
        return result;
    }

    /**
     * The {@code tools/call} error result with the hub's body attached as structured content; the
     * hub's {@code 409}/{@code 422} explanation is then data the model can act on, not just prose.
     */
    static ObjectNode toolError(String message, JsonNode structuredContent) {
        ObjectNode result = toolError(message);
        if (structuredContent != null) {
            result.set("structuredContent", structuredContent);
        }
        return result;
    }

    // ---- resources and prompts (plan §3.3, §3.4, Stage 3) ------------------------------------

    /**
     * A {@code resources/read}: the URI is required and must be a string. An unknown URI is MCP's
     * {@code -32002} (resource not found), not a crash; a known resource always answers.
     */
    private JsonNode resourceRead(JsonNode id, JsonNode params) {
        if (params == null || !params.isObject()) {
            return error(id, -32602, "Invalid params");
        }
        JsonNode uri = params.get("uri");
        if (uri == null || !uri.isTextual()) {
            return error(id, -32602, "Invalid params");
        }
        try {
            return ok(id, McpResources.read(api, uri.asText()));
        } catch (McpResources.NotFound e) {
            return error(id, -32002, "Resource not found");
        }
    }

    /**
     * A {@code prompts/get}: the name is required and must be a string; an unknown name is a
     * {@code -32602}. The arguments are optional — the prompt text is static — but a present
     * non-object is malformed.
     */
    private JsonNode promptGet(JsonNode id, JsonNode params) {
        if (params == null || !params.isObject()) {
            return error(id, -32602, "Invalid params");
        }
        JsonNode name = params.get("name");
        if (name == null || !name.isTextual()) {
            return error(id, -32602, "Invalid params");
        }
        JsonNode arguments = params.get("arguments");
        if (arguments != null && !arguments.isObject() && !arguments.isNull()) {
            return error(id, -32602, "Invalid params");
        }
        try {
            return ok(id, McpPrompts.get(name.asText(), arguments));
        } catch (McpPrompts.UnknownPrompt e) {
            return error(id, -32602, e.getMessage());
        }
    }

    // ---- JSON-RPC envelopes ------------------------------------------------------------------

    private static ObjectNode ok(JsonNode id, JsonNode result) {
        return envelope(id, "result", result);
    }

    private static ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode detail = Json.mapper().createObjectNode();
        detail.put("code", code);
        detail.put("message", message);
        return envelope(id, "error", detail);
    }

    private static ObjectNode envelope(JsonNode id, String field, JsonNode value) {
        ObjectNode node = Json.mapper().createObjectNode();
        node.put("jsonrpc", "2.0");
        if (id == null || id.isNull() || id.isMissingNode()) {
            node.putNull("id");
        } else {
            node.set("id", id);
        }
        node.set(field, value);
        return node;
    }

    /** The compact JSON rendering of a value, as the {@code content.text} an MCP client sees. */
    private static String compact(JsonNode data) {
        try {
            return Json.mapper().writeValueAsString(data);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("cannot serialise " + data, e);
        }
    }
}
