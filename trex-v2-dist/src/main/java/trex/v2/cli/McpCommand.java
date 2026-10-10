package trex.v2.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Callable;

/**
 * {@code trex mcp}: the stdio bridge to the hub's MCP endpoint (V2-MCP-SERVER-PLAN.md §1,
 * Transport B). stdio-only clients (Claude Desktop) cannot POST to the hub's Streamable HTTP
 * binding, so this forwards each newline-delimited JSON-RPC message to {@code <hub-url>/mcp} and
 * relays the answer. It holds no protocol logic — the hub is the server, and everything the bridge
 * sends is already a complete JSON-RPC message.
 */
@Command(name = "mcp", mixinStandardHelpOptions = true,
    description = "Bridge newline-delimited JSON-RPC on stdin to the hub's MCP endpoint.")
public final class McpCommand implements Callable<Integer> {

    /** The hub base URL; {@code TREX_HUB_URL} overrides the loopback default (§1 Transport B). */
    @Option(names = "--hub-url",
        description = "The hub base URL (default: TREX_HUB_URL, else http://127.0.0.1:8090).")
    String hubUrl = defaultHubUrl();

    public McpCommand() {}

    /** Test seam: a bridge pinned to one hub URL without going through the CLI. */
    public McpCommand(String hubUrl) {
        this.hubUrl = hubUrl;
    }

    @Override
    public Integer call() throws Exception {
        return run(System.in, System.out, System.err);
    }

    /**
     * Read newline-delimited JSON-RPC from {@code in}, POST each non-blank line to the hub's
     * {@code /mcp}, and write each response body followed by a newline to {@code out}. A
     * {@code 202} (a notification the hub accepted) or an empty body writes nothing. A hub that
     * cannot be reached is reported on {@code err} and ends the loop with exit code 1.
     */
    public int run(InputStream in, OutputStream out, PrintStream err) throws Exception {
        String base = hubUrl.endsWith("/") ? hubUrl.substring(0, hubUrl.length() - 1) : hubUrl;
        URI uri = URI.create(base + "/mcp");
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            HttpResponse<String> response;
            try {
                response = http.send(request(uri, line),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (IOException e) {
                err.println("mcp: hub unreachable at " + uri + ": " + e.getMessage());
                return 1;
            }
            if (response.statusCode() == 202) {
                continue;
            }
            String body = response.body();
            if (body == null || body.isEmpty()) {
                continue;
            }
            out.write(body.getBytes(StandardCharsets.UTF_8));
            out.write('\n');
            out.flush();
        }
        return 0;
    }

    private static HttpRequest request(URI uri, String body) {
        return HttpRequest.newBuilder(uri)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .timeout(Duration.ofMinutes(5))
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
    }

    /** {@code ${TREX_HUB_URL:-http://127.0.0.1:8090}}: the env var if set, else loopback. */
    private static String defaultHubUrl() {
        String env = System.getenv("TREX_HUB_URL");
        return env == null || env.isBlank() ? "http://127.0.0.1:8090" : env;
    }
}
