package trex.sequencer.http;

import trex.sequencer.ingest.Harness;
import trex.sequencer.journal.JsonlJournal;
import trex.sequencer.journal.Recovery;
import trex.sequencer.ingest.Sequencer;
import trex.sequencer.state.Fold;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** A sequencer + HTTP API on an ephemeral port over a temp journal, with a raw client. */
final class ApiServer implements AutoCloseable {

    record Reply(int status, String contentEncoding, String body) {}

    private final JsonlJournal journal;
    private final HttpApi api;
    private final HttpClient client = HttpClient.newHttpClient();

    ApiServer(Path journalPath, long maxBodyBytes) {
        Recovery.recover(journalPath, journalPath);
        journal = new JsonlJournal(journalPath);
        Sequencer sequencer = new Sequencer(journal, Fold.fold(journal), Harness.REGISTRY, Harness.RULES, Harness.FIXED_CLOCK);
        api = new HttpApi(sequencer, 0, maxBodyBytes).start();
    }

    Reply post(String path, String json, boolean gzipRequest, boolean acceptGzip) {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri(path))
            .header("Content-Type", "application/json");
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        if (gzipRequest) {
            body = gzip(body);
            b.header("Content-Encoding", "gzip");
        }
        if (acceptGzip) {
            b.header("Accept-Encoding", "gzip");
        }
        return send(b.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build());
    }

    Reply get(String path, boolean acceptGzip) {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri(path));
        if (acceptGzip) {
            b.header("Accept-Encoding", "gzip");
        }
        return send(b.GET().build());
    }

    Reply postRaw(String path, byte[] body, String contentEncoding) {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri(path));
        if (contentEncoding != null) {
            b.header("Content-Encoding", contentEncoding);
        }
        return send(b.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + api.port() + path);
    }

    private Reply send(HttpRequest request) {
        try {
            HttpResponse<byte[]> r = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            String encoding = r.headers().firstValue("Content-Encoding").orElse(null);
            byte[] body = "gzip".equals(encoding) ? gunzip(r.body()) : r.body();
            return new Reply(r.statusCode(), encoding, new String(body, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    static byte[] gzip(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(data);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    static byte[] gunzip(byte[] data) {
        try (InputStream in = new GZIPInputStream(new java.io.ByteArrayInputStream(data))) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        api.close();
        journal.close();
    }
}
