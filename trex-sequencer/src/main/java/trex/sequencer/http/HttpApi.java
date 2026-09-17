package trex.sequencer.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import trex.sequencer.ingest.BatchResponse;
import trex.sequencer.ingest.CandidateInput;
import trex.sequencer.ingest.DecisionInput;
import trex.sequencer.ingest.Sequencer;
import trex.core.state.LedgerView;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** JDK HttpServer API. SPEC §3.5. Mutations serialize inside {@link Sequencer}; reads use its snapshot. */
public final class HttpApi implements AutoCloseable {

    public static final long DEFAULT_MAX_BODY_BYTES = 100L * 1024 * 1024;

    private interface Handler {
        void handle(HttpExchange ex) throws Exception;
    }

    private final Sequencer sequencer;
    private final long maxBodyBytes;
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /** Loopback-only API (tests and local use). */
    public HttpApi(Sequencer sequencer, int port, long maxBodyBytes) {
        this(sequencer, "127.0.0.1", port, maxBodyBytes);
    }

    public HttpApi(Sequencer sequencer, String bindHost, int port, long maxBodyBytes) {
        this.sequencer = sequencer;
        this.maxBodyBytes = maxBodyBytes;
        try {
            this.server = HttpServer.create(new InetSocketAddress(bindHost, port), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        route("/candidates", "POST", this::candidates);
        route("/decisions", "POST", this::decisions);
        route("/held", "GET", ex -> Gzip.writeJson(ex, 200, sequencer.view().held()));
        route("/review", "GET", ex -> Gzip.writeJson(ex, 200, sequencer.view().review()));
        route("/head", "GET", this::head);
        server.createContext("/", ex -> send(ex, 404, "not found"));
        server.setExecutor(executor);
    }

    public HttpApi start() {
        server.start();
        return this;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private void route(String path, String method, Handler handler) {
        server.createContext(path, ex -> {
            try {
                if (!ex.getRequestURI().getPath().equals(path)) {
                    send(ex, 404, "not found");
                } else if (!ex.getRequestMethod().equals(method)) {
                    ex.getResponseHeaders().set("Allow", method);
                    send(ex, 405, "method not allowed");
                } else {
                    handler.handle(ex);
                }
            } catch (Gzip.PayloadTooLargeException e) {
                send(ex, 413, e.getMessage());
            } catch (Gzip.UnsupportedEncodingException e) {
                send(ex, 415, e.getMessage());
            } catch (Binding.BadRequestException | java.util.zip.ZipException e) {
                send(ex, 400, e.getMessage());
            } catch (Exception e) {
                send(ex, 500, "internal error: " + e.getMessage());
            } finally {
                ex.close();
            }
        });
    }

    private void candidates(HttpExchange ex) throws Exception {
        Binding.Request<CandidateInput> req = Binding.candidates(Gzip.readBody(ex, maxBodyBytes));
        BatchResponse response = sequencer.submitCandidates(req.allOrNone(), req.items());
        Gzip.writeJson(ex, 200, response);
    }

    private void decisions(HttpExchange ex) throws Exception {
        Binding.Request<DecisionInput> req = Binding.decisions(Gzip.readBody(ex, maxBodyBytes));
        BatchResponse response = sequencer.submitDecisions(req.allOrNone(), req.items());
        Gzip.writeJson(ex, 200, response);
    }

    private void head(HttpExchange ex) throws IOException {
        LedgerView v = sequencer.view();
        Gzip.writeJson(ex, 200, Map.of("offset", v.headOffset(), "n", v.highWaterN()));
    }

    private static void send(HttpExchange ex, int status, String message) {
        try {
            Gzip.writeJson(ex, status, Map.of("error", message == null ? "" : message));
        } catch (IOException | RuntimeException ignored) {
            // response already started or client gone; nothing more to do
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdown();
    }
}
