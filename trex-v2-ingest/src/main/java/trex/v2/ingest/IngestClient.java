package trex.v2.ingest;

import trex.v2.log.Json;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The client for the sequencer's {@code POST /facts} (V2-PROPOSAL.md §6.5). gzip both ways, and
 * gzip never touches the journal. A transport failure is terminal: the run stops and reports it.
 */
public final class IngestClient {

    public record RowResult(String ref, String outcome, String externalId, Long n, String reason) {}
    public record BatchResponse(String batchHandle, String batchStatus, List<RowResult> results) {}

    public static final String APPENDED = "Appended";
    public static final String DUPLICATE = "Duplicate";
    public static final String FLAGGED = "Flagged";
    public static final String REJECTED = "Rejected";

    private record FactBatch(boolean allOrNone, String source, List<FactDraft> facts) {}

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final URI base;
    private final String source;

    public IngestClient(String baseUrl) {
        this(baseUrl, "ING_0001");
    }

    public IngestClient(String baseUrl, String source) {
        String trimmed = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.base = URI.create(trimmed);
        this.source = source;
    }

    /** Post decisions (the re-parse apply path); 4xx and transport failures are terminal. */
    public void postDecisions(List<Map<String, Object>> decisions) {
        try {
            byte[] json = Json.mapper().writeValueAsBytes(Map.of("decisions", decisions, "source", source));
            HttpRequest request = HttpRequest.newBuilder(base.resolve("/decisions"))
                .timeout(Duration.ofMinutes(2))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(json))
                .build();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                throw new IngestException("sequencer answered HTTP " + response.statusCode() + " on /decisions: "
                    + new String(response.body(), java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new IngestException("cannot reach the sequencer at " + base + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IngestException("interrupted posting to " + base, e);
        }
    }

    /** Emit one ingest event (V2-PROPOSAL.md §12.6); a transport failure is terminal, like /facts. */
    public void postIngest(java.util.Map<String, Object> event) {
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>(event);
        body.put("source", source);
        try {
            byte[] json = Json.mapper().writeValueAsBytes(body);
            HttpRequest request = HttpRequest.newBuilder(base.resolve("/ingest"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(json))
                .build();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                throw new IngestException("sequencer answered HTTP " + response.statusCode() + " on /ingest: "
                    + new String(response.body(), java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new IngestException("cannot reach the sequencer at " + base + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IngestException("interrupted posting to " + base, e);
        }
    }

    public BatchResponse postFacts(List<FactDraft> facts, boolean allOrNone) {
        try {
            byte[] json = Json.mapper().writeValueAsBytes(new FactBatch(allOrNone, source, facts));
            HttpRequest request = HttpRequest.newBuilder(base.resolve("/facts"))
                .timeout(Duration.ofMinutes(5))
                .header("Content-Type", "application/json")
                .header("Content-Encoding", "gzip")
                .header("Accept-Encoding", "gzip")
                .POST(HttpRequest.BodyPublishers.ofByteArray(gzip(json)))
                .build();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            byte[] body = response.body();
            String contentEncoding = response.headers().firstValue("Content-Encoding").orElse("");
            if (contentEncoding.contains("gzip") && body.length > 0) {
                try (GZIPInputStream in = new GZIPInputStream(new java.io.ByteArrayInputStream(body))) {
                    body = in.readAllBytes();
                }
            }
            if (response.statusCode() / 100 != 2) {
                throw new IngestException("sequencer answered HTTP " + response.statusCode() + ": "
                    + new String(body, java.nio.charset.StandardCharsets.UTF_8));
            }
            return Json.mapper().readValue(body, BatchResponse.class);
        } catch (IOException e) {
            throw new IngestException("cannot reach the sequencer at " + base + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IngestException("interrupted posting to " + base, e);
        }
    }

    private static byte[] gzip(byte[] bytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(bytes);
        }
        return out.toByteArray();
    }

    public static final class IngestException extends RuntimeException {
        public IngestException(String message, Throwable cause) {
            super(message, cause);
        }

        public IngestException(String message) {
            super(message);
        }
    }
}
