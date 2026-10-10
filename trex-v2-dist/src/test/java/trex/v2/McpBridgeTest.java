package trex.v2;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import trex.v2.cli.McpCommand;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The stdio bridge (V2-MCP-SERVER-PLAN.md §1, Transport B): newline-delimited JSON-RPC on stdin is
 * forwarded verbatim to the hub's {@code /mcp} and the response relayed to stdout. The server is an
 * ephemeral {@link HttpServer}; the bridge never touches a real hub.
 */
class McpBridgeTest {

    @Test
    void theStdioBridgeForwardsJsonRpcToTheHub() throws Exception {
        AtomicReference<String> forwarded = new AtomicReference<>();
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<String> accept = new AtomicReference<>();
        byte[] canned = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ok\":true}}"
            .getBytes(StandardCharsets.UTF_8);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", ex -> {
            method.set(ex.getRequestMethod());
            contentType.set(ex.getRequestHeaders().getFirst("Content-Type"));
            accept.set(ex.getRequestHeaders().getFirst("Accept"));
            forwarded.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, canned.length);
            ex.getResponseBody().write(canned);
            ex.close();
        });
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        try {
            String hubUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            byte[] request = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}\n"
                .getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();

            int exit = new McpCommand(hubUrl).run(
                new ByteArrayInputStream(request), out,
                new PrintStream(err, true, StandardCharsets.UTF_8));

            assertEquals(0, exit, err.toString(StandardCharsets.UTF_8));
            assertEquals("POST", method.get());
            assertNotNull(contentType.get(), "the forwarded request declares a JSON content type");
            assertTrue(contentType.get().contains("application/json"), contentType.get());
            assertTrue(accept.get().contains("application/json"), accept.get());
            assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}", forwarded.get(),
                "the bridge forwards the JSON-RPC line verbatim");
            assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ok\":true}}\n",
                out.toString(StandardCharsets.UTF_8), "the response body is relayed with a newline");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void theBridgeIsRegisteredAsTheMcpSubcommand() {
        assertTrue(Main.commandLine().getSubcommands().containsKey("mcp"),
            "trex mcp is a subcommand: " + Main.commandLine().getSubcommands().keySet());
    }
}
