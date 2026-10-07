package trex.v2.hub;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * The hub's client for the job runner (V2-PROPOSAL.md §5.5). The hub is the one origin the browser
 * talks to; everything the Jobs view and the staging inbox do is proxied here, so the runner stays
 * loopback. The hub never stores an uploaded file.
 */
final class RunnerClient {

    record Resp(int status, String contentType, byte[] body) {}

    private final String baseUrl;
    private final String token;
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    RunnerClient(String baseUrl, String token) {
        String trimmed = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.baseUrl = trimmed;
        this.token = token;
    }

    Resp get(String path) throws IOException, InterruptedException {
        return send(req(path).GET().build());
    }

    Resp postJson(String path, byte[] body) throws IOException, InterruptedException {
        return send(req(path).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build());
    }

    Resp postUpload(String path, Supplier<InputStream> body) throws IOException, InterruptedException {
        return send(req(path).header("Content-Type", "application/octet-stream")
            .POST(HttpRequest.BodyPublishers.ofInputStream(body)).build());
    }

    /** A streaming GET for the run SSE; the caller pipes the body to the browser. */
    HttpResponse<InputStream> stream(String path) throws IOException, InterruptedException {
        return http.send(req(path).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
    }

    private Resp send(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        String type = response.headers().firstValue("Content-Type").orElse("application/json");
        return new Resp(response.statusCode(), type, response.body());
    }

    private HttpRequest.Builder req(String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(Duration.ofMinutes(10));
        if (token != null && !token.isBlank()) {
            builder.header("X-Trex-Runner-Token", token);
        }
        return builder;
    }
}
