package trex.ingest;

import trex.core.BatchStatus;
import trex.core.Candidate;
import trex.core.CandidateResult;
import trex.journal.Json;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** POST /candidates over java.net.http with manual gzip (SPEC §4, mirror of §3.6). */
public final class TrexClient {

    public record Response(String batchHandle, BatchStatus batchStatus, List<CandidateResult> results) {}

    public static final class TrexHttpException extends IOException {
        TrexHttpException(int status, String body) {
            super("trex returned HTTP " + status + ": " + body);
        }
    }

    private final URI candidatesUri;
    private final boolean gzip;
    private final HttpClient http = HttpClient.newHttpClient();

    public TrexClient(URI baseUrl, boolean gzip) {
        this.candidatesUri = baseUrl.resolve("/candidates");
        this.gzip = gzip;
    }

    public Response post(List<Candidate> batch) throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("allOrNone", false);
        body.put("batch", batch);
        byte[] json = Json.mapper().writeValueAsBytes(body);

        HttpRequest.Builder req = HttpRequest.newBuilder(candidatesUri)
            .header("Content-Type", "application/json")
            .header("Accept-Encoding", "gzip");
        if (gzip) {
            json = gzip(json);
            req.header("Content-Encoding", "gzip");
        }
        HttpResponse<byte[]> res = http.send(req.POST(HttpRequest.BodyPublishers.ofByteArray(json)).build(),
            HttpResponse.BodyHandlers.ofByteArray());
        byte[] payload = res.headers().firstValue("Content-Encoding").filter("gzip"::equalsIgnoreCase).isPresent()
            ? gunzip(res.body())
            : res.body();
        if (res.statusCode() != 200) {
            throw new TrexHttpException(res.statusCode(), new String(payload, java.nio.charset.StandardCharsets.UTF_8));
        }
        return Json.mapper().readValue(payload, Response.class);
    }

    private static byte[] gzip(byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(data);
        }
        return out.toByteArray();
    }

    private static byte[] gunzip(byte[] data) throws IOException {
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) {
            return in.readAllBytes();
        }
    }
}
