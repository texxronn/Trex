package trex.v2.importer;

import trex.v2.log.Json;
import trex.v2.sequencer.api.BatchResponse;
import trex.v2.sequencer.api.DecisionBatch;
import trex.v2.sequencer.api.FactBatch;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** A {@link V1Importer.Sink} that posts to a running sequencer's API (V2-PROPOSAL.md §6.5). */
public final class HttpSink implements V1Importer.Sink {

    private final HttpClient client;
    private final URI base;

    public HttpSink(String baseUrl) {
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.base = URI.create(baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl);
    }

    @Override
    public BatchResponse facts(FactBatch batch) {
        return post("/facts", batch, BatchResponse.class);
    }

    @Override
    public BatchResponse decisions(DecisionBatch batch) {
        return post("/decisions", batch, BatchResponse.class);
    }

    private <T> T post(String path, Object body, Class<T> type) {
        try {
            byte[] json = Json.mapper().writeValueAsBytes(body);
            HttpRequest request = HttpRequest.newBuilder(base.resolve(path))
                .timeout(Duration.ofMinutes(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(json))
                .build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("sequencer " + path + " answered HTTP " + response.statusCode()
                    + ": " + new String(response.body(), java.nio.charset.StandardCharsets.UTF_8));
            }
            return Json.mapper().readValue(response.body(), type);
        } catch (IOException e) {
            throw new IllegalStateException("cannot reach the sequencer at " + base, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted posting to " + base, e);
        }
    }
}
