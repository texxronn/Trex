package trex.v2.hub;

import trex.v2.log.Json;
import trex.v2.sequencer.api.BatchResponse;
import trex.v2.sequencer.api.DecisionBatch;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * The hub's client for the sequencer (V2-PROPOSAL.md §6.6). The hub owns the decision experience —
 * precheck, staleness, attribution — but the sequencer remains the only writer of the journal, so
 * every accepted decision is forwarded over HTTP rather than appended here.
 */
final class SequencerClient {

    private final HttpClient client;
    private final URI base;
    private final String source;

    SequencerClient(String url) {
        this(url, "HUB_0001");
    }

    SequencerClient(String url, String source) {
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        String trimmed = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.base = URI.create(trimmed);
        this.source = source;
    }

    /** Forward a decision batch, stamped with the hub's source (§6); a transport failure is {@link Unavailable}. */
    BatchResponse postDecisions(DecisionBatch batch) {
        DecisionBatch stamped = new DecisionBatch(batch.allOrNone(), source, batch.target(), batch.decisions());
        try {
            byte[] json = Json.mapper().writeValueAsBytes(stamped);
            HttpRequest request = HttpRequest.newBuilder(base.resolve("/decisions"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(json))
                .build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                throw new Unavailable("sequencer answered HTTP " + response.statusCode() + ": "
                    + new String(response.body(), java.nio.charset.StandardCharsets.UTF_8));
            }
            return Json.mapper().readValue(response.body(), BatchResponse.class);
        } catch (IOException e) {
            throw new Unavailable("cannot reach the sequencer at " + base + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Unavailable("interrupted posting to " + base);
        }
    }

    /** A transport or non-2xx failure: the hub answers 502. */
    static final class Unavailable extends RuntimeException {
        Unavailable(String message) {
            super(message);
        }
    }
}
