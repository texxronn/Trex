package trex.v2.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import trex.v2.ingest.Adapters;
import trex.v2.log.Json;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * The runner's loopback trigger API (V2-PROPOSAL.md §5.5): jobs, runs, staging. It binds loopback,
 * is never published, and — when a token is configured — answers only the hub. The hub is the one
 * origin the browser talks to; the runner is headless.
 */
final class RunnerHttpApi {

    private static final Logger log = LoggerFactory.getLogger(RunnerHttpApi.class);
    private static final long MAX_UPLOAD = 50L * 1024 * 1024;
    private static final long MAX_JSON = 2L * 1024 * 1024;

    private RunnerHttpApi() {}

    static HttpServer start(RunnerConfig config, JobRunnerService runner, StagingStore staging,
                            Map<String, JobSpec> jobs, StatementsMap statements) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(config.host(), config.port()), 0);
        server.createContext("/", ex -> {
            try {
                dispatch(ex, config, runner, staging, jobs, statements);
            } catch (IllegalArgumentException e) {
                sendError(ex, 400, e.getMessage());
            } catch (Exception e) {
                log.error("500 {} {}", ex.getRequestMethod(), ex.getRequestURI(), e);
                sendError(ex, 500, "internal error: " + e.getMessage());
            } finally {
                ex.close();
            }
        });
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        return server;
    }

    private static void dispatch(HttpExchange ex, RunnerConfig config, JobRunnerService runner,
                                 StagingStore staging, Map<String, JobSpec> jobs, StatementsMap statements)
            throws Exception {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        if (path.equals("/health")) {
            writeJson(ex, 200, Map.of("ok", true));
            return;
        }
        if (!authorized(config, ex)) {
            sendError(ex, 401, "missing or bad runner token");
            return;
        }
        if (path.equals("/jobs") && method.equals("GET")) {
            listJobs(ex, runner, jobs);
            return;
        }
        if (path.equals("/adapters") && method.equals("GET")) {
            writeJson(ex, 200, Map.of("types", Adapters.types()));
            return;
        }
        if (path.equals("/staging")) {
            if (method.equals("GET")) {
                listStaging(ex, staging, statements);
            } else if (method.equals("POST")) {
                upload(ex, staging, statements);
            } else {
                sendError(ex, 405, "method not allowed");
            }
            return;
        }
        if (path.equals("/staging/clear") && method.equals("POST")) {
            String name = queryParam(ex, "name");
            boolean ok = name != null && staging.markDone(name);
            writeJson(ex, ok ? 200 : 404, Map.of("done", ok));
            return;
        }
        if (path.startsWith("/jobs/")) {
            jobsRoute(ex, path, method, runner);
            return;
        }
        sendError(ex, 404, "not found");
    }

    // ---- jobs -------------------------------------------------------------------------------

    private static void listJobs(HttpExchange ex, JobRunnerService runner, Map<String, JobSpec> jobs)
            throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        for (JobSpec spec : jobs.values()) {
            RunRecord active = runner.active(spec.name());
            RunRecord last = runner.last(spec.name());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", spec.name());
            m.put("title", spec.title());
            m.put("description", spec.description());
            m.put("capability", spec.capability());
            m.put("params", spec.params());
            m.put("running", active != null);
            m.put("activeRun", active == null ? null : active.id());
            m.put("lastRun", last == null ? null : summary(last));
            out.add(m);
        }
        writeJson(ex, 200, out);
    }

    private static void jobsRoute(HttpExchange ex, String path, String method, JobRunnerService runner)
            throws Exception {
        String rest = path.substring("/jobs/".length());
        String[] seg = rest.split("/");
        if (seg.length == 1 && seg[0].equals("runs")) {
            if (!method.equals("GET")) {
                sendError(ex, 405, "method not allowed");
                return;
            }
            List<Map<String, Object>> out = new ArrayList<>();
            for (RunRecord run : runner.history()) {
                out.add(summary(run));
            }
            writeJson(ex, 200, out);
            return;
        }
        if (seg.length >= 2 && seg[0].equals("runs")) {
            RunRecord run = runner.find(seg[1]);
            if (run == null) {
                sendError(ex, 404, "no such run: " + seg[1]);
                return;
            }
            if (seg.length == 2 && method.equals("GET")) {
                Map<String, Object> m = summary(run);
                m.put("output", run.linesFrom(0));
                writeJson(ex, 200, m);
                return;
            }
            if (seg.length == 3 && seg[2].equals("events") && method.equals("GET")) {
                streamEvents(ex, run);
                return;
            }
            if (seg.length == 3 && seg[2].equals("cancel") && method.equals("POST")) {
                writeJson(ex, 200, Map.of("cancelled", runner.cancel(run.id())));
                return;
            }
            sendError(ex, 404, "not found");
            return;
        }
        if (seg.length == 2 && seg[1].equals("runs") && method.equals("POST")) {
            String body = new String(readCapped(ex.getRequestBody(), MAX_JSON), StandardCharsets.UTF_8);
            JsonNode root = body.isBlank() ? JsonNodeFactory.instance.objectNode() : Json.mapper().readTree(body);
            JsonNode params = root.path("params");
            RunRecord run = runner.submit(seg[0], params.isMissingNode() ? null : params);
            writeJson(ex, 202, Map.of("runId", run.id(), "state", run.state().name()));
            return;
        }
        sendError(ex, 404, "not found");
    }

    private static Map<String, Object> summary(RunRecord run) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", run.id());
        m.put("job", run.job());
        m.put("state", run.state().name());
        m.put("queuedAt", run.queuedAt());
        m.put("startedAt", run.startedAt());
        m.put("finishedAt", run.finishedAt());
        m.put("exit", run.exit());
        m.put("stepExits", run.stepExits());
        return m;
    }

    /** SSE: the retained output, then a terminal {@code done} event (V2-PROPOSAL.md §5.5). */
    private static void streamEvents(HttpExchange ex, RunRecord run) throws IOException, InterruptedException {
        ex.getResponseHeaders().set("Content-Type", "text/event-stream");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, 0);
        try (OutputStream out = ex.getResponseBody()) {
            sse(out, "state", Json.mapper().writeValueAsString(summary(run)));
            long cursor = 0;
            long idle = 0;
            while (true) {
                long first = run.firstIndex();
                if (cursor < first) {
                    cursor = first;
                }
                List<String> lines = run.linesFrom(cursor);
                for (String line : lines) {
                    sse(out, "line", line);
                    cursor++;
                }
                if (terminal(run.state()) && cursor >= run.producedCount()) {
                    sse(out, "done", Json.mapper().writeValueAsString(summary(run)));
                    break;
                }
                Thread.sleep(200);
                if (++idle % 75 == 0) {
                    out.write(": ping\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            }
        }
    }

    private static boolean terminal(RunRecord.State state) {
        return state == RunRecord.State.SUCCEEDED || state == RunRecord.State.FAILED
            || state == RunRecord.State.CANCELLED;
    }

    private static void sse(OutputStream out, String event, String data) throws IOException {
        out.write(("event: " + event + "\n").getBytes(StandardCharsets.UTF_8));
        out.write(("data: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    // ---- staging ----------------------------------------------------------------------------

    private static void listStaging(HttpExchange ex, StagingStore staging, StatementsMap statements)
            throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        for (StagingStore.Staged s : staging.list()) {
            out.add(stagingView(s, statements));
        }
        writeJson(ex, 200, out);
    }

    private static void upload(HttpExchange ex, StagingStore staging, StatementsMap statements)
            throws IOException {
        String name = queryParam(ex, "name");
        byte[] bytes = readCapped(ex.getRequestBody(), MAX_UPLOAD);
        StagingStore.Staged staged = staging.put(bytes, name == null ? "upload" : name);
        writeJson(ex, 200, stagingView(staged, statements));
    }

    private static Map<String, Object> stagingView(StagingStore.Staged staged, StatementsMap statements) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", staged.name());
        m.put("original", staged.original());
        m.put("size", staged.size());
        m.put("sha256", staged.sha256());
        m.put("evidenceId", "sha256:" + staged.sha256());
        m.put("state", staged.state());
        m.put("at", staged.at());
        var entry = statements.resolve(staged.original());
        m.put("sourceType", entry.map(StatementsMap.Entry::sourceType).orElse(null));
        m.put("account", entry.map(StatementsMap.Entry::account).orElse(null));
        return m;
    }

    // ---- helpers ----------------------------------------------------------------------------

    private static boolean authorized(RunnerConfig config, HttpExchange ex) {
        String token = config.token();
        if (token == null || token.isBlank()) {
            return true;
        }
        return token.equals(ex.getRequestHeaders().getFirst("X-Trex-Runner-Token"));
    }

    private static byte[] readCapped(InputStream in, long max) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[65536];
        long total = 0;
        int n;
        while ((n = in.read(chunk)) != -1) {
            total += n;
            if (total > max) {
                throw new IllegalArgumentException("body too large (max " + (max / (1024 * 1024)) + " MB)");
            }
            buffer.write(chunk, 0, n);
        }
        return buffer.toByteArray();
    }

    private static String queryParam(HttpExchange ex, String key) {
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(key)) {
                String value = java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                return value.isBlank() ? null : value;
            }
        }
        return null;
    }

    private static void writeJson(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = Json.mapper().writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void sendError(HttpExchange ex, int status, String message) {
        try {
            writeJson(ex, status, Map.of("error", message == null ? "" : message));
        } catch (IOException | RuntimeException e) {
            log.debug("could not send {} response", status, e);
        }
    }
}
