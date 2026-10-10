package trex.v2.hub;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import trex.v2.hub.api.HeadResponse;
import trex.v2.hub.api.StatusResponse;
import trex.v2.log.Json;

import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Streamable HTTP binding (V2-MCP-SERVER-PLAN.md §1, Stage 1): {@code POST} only, one
 * {@code application/json} response, notifications {@code 202}, everything else {@code 405}. The
 * server is ephemeral and never touches the journal or the index.
 */
class McpHttpTest {

    private static final McpConfig READ_ONLY = new McpConfig(false);

    @Test
    void getAndDeleteAreNotAllowed() throws Exception {
        try (Bound bound = start()) {
            HttpResponse<String> get = bound.send(
                HttpRequest.newBuilder(bound.uri()).GET().build());
            assertEquals(405, get.statusCode(), get.body());
            assertEquals("POST", get.headers().firstValue("Allow").orElse(null));

            HttpResponse<String> delete = bound.send(
                HttpRequest.newBuilder(bound.uri()).DELETE().build());
            assertEquals(405, delete.statusCode(), delete.body());
            assertEquals("POST", delete.headers().firstValue("Allow").orElse(null));
        }
    }

    @Test
    void postReturnsAJsonRpcResponse() throws Exception {
        try (Bound bound = start()) {
            HttpResponse<String> response = bound.send(HttpRequest.newBuilder(bound.uri())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                    "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"ping\"}"))
                .build());
            assertEquals(200, response.statusCode(), response.body());
            assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("application/json"));
            JsonNode body = Json.mapper().readTree(response.body());
            assertEquals("2.0", body.get("jsonrpc").asText());
            assertEquals(7, body.get("id").asInt());
            assertTrue(body.has("result"), response.body());
        }
    }

    @Test
    void aNotificationPostReturnsAccepted() throws Exception {
        try (Bound bound = start()) {
            HttpResponse<String> response = bound.send(HttpRequest.newBuilder(bound.uri())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                    "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"))
                .build());
            assertEquals(202, response.statusCode(), response.body());
            assertEquals("", response.body(), "a notification has no body");
        }
    }

    // ---- helpers -----------------------------------------------------------------------------

    /** A running ephemeral hub with only the {@code /mcp} context. */
    private static Bound start() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        HubApi api = stub();
        server.createContext("/mcp", ex -> McpHttp.handle(ex, api, READ_ONLY));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");
        return new Bound(server, uri);
    }

    private record Bound(HttpServer server, URI uri) implements AutoCloseable {
        HttpResponse<String> send(HttpRequest request) throws Exception {
            return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        }

        @Override
        public void close() {
            server.stop(0);
        }
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
