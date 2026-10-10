package trex.v2.hub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import trex.v2.hub.api.ErrorResponse;
import trex.v2.log.Json;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * The Streamable HTTP binding for MCP (V2-MCP-SERVER-PLAN.md §1): one JSON-RPC request per
 * {@code POST}, one {@code application/json} response. Sessionless and lenient — no session id, no
 * SSE; {@code GET}/{@code DELETE} are refused because v1 has no server-initiated stream (D1).
 *
 * <p>Failures never escape the handler: a JSON-RPC error is answered {@code 200}, and anything the
 * core cannot classify is a {@code -32603}/500.
 */
final class McpHttp {

    private static final Logger log = LoggerFactory.getLogger(McpHttp.class);

    /** The same cap the rest of the hub applies to a request body. */
    private static final int MAX_BODY_BYTES = 10 * 1024 * 1024;

    private McpHttp() {}

    static void handle(HttpExchange ex, HubApi api, McpConfig config) {
        try {
            if (!"POST".equals(ex.getRequestMethod())) {
                ex.getResponseHeaders().set("Allow", "POST");
                send(ex, 405, Json.mapper().valueToTree(new ErrorResponse("method not allowed")));
                return;
            }
            String body;
            try {
                body = readBody(ex);
            } catch (IOException e) {
                send(ex, 413, Json.mapper().valueToTree(new ErrorResponse("request body too large")));
                return;
            }
            JsonNode response = new McpApi(api).dispatch(body, config);
            if (response == null) {
                // A notification: accepted, no body.
                ex.sendResponseHeaders(202, -1);
                return;
            }
            send(ex, 200, response);
        } catch (Exception e) {
            log.error("MCP request failed", e);
            try {
                send(ex, 500, internalError());
            } catch (Exception ignored) {
                // Headers already sent; there is nothing left to do.
            }
        } finally {
            ex.close();
        }
    }

    private static String readBody(HttpExchange ex) throws IOException {
        byte[] bytes = ex.getRequestBody().readAllBytes();
        if (bytes.length > MAX_BODY_BYTES) {
            throw new IOException("request body too large");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void send(HttpExchange ex, int status, JsonNode body) throws IOException {
        byte[] bytes = Json.mapper().writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** {@code -32603} Internal error, shaped as a JSON-RPC error response. */
    private static ObjectNode internalError() {
        ObjectNode node = Json.mapper().createObjectNode();
        node.put("jsonrpc", "2.0");
        node.putNull("id");
        ObjectNode error = node.putObject("error");
        error.put("code", -32603);
        error.put("message", "Internal error");
        return node;
    }
}
