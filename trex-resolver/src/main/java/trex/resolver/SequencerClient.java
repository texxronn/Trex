package trex.resolver;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Calls the sequencer's POST /decisions. The resolver never writes the journal itself. */
public final class SequencerClient {

    public record Reply(int status, String body) {}

    private final URI decisionsUri;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public SequencerClient(URI sequencerUrl) {
        this.decisionsUri = sequencerUrl.resolve("/decisions");
    }

    public Reply postDecisions(byte[] json) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(decisionsUri)
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(json))
            .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Reply(response.statusCode(), response.body());
    }
}
