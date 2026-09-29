package trex.v2.ingest;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trex.v2.log.EvidenceStore;
import trex.v2.log.Json;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The ingest run (V2-PROPOSAL.md §6.5): evidence stored, whole-file validation, exit codes. */
class IngestRunnerTest {

    private static final String SAMPLE = """
        Date,Description,Credit,Debit,Balance
        01/07/2026,"COFFEE CART, SYDNEY",,-4.50,1745.50
        02/07/2026,Salary Deposit - Receipt No 998877,2500.00,,4061.00
        """;

    @Test
    void storesEvidenceAndPosts(@TempDir Path dir) throws Exception {
        try (FakeSequencer sequencer = new FakeSequencer("Appended")) {
            EvidenceStore evidence = new EvidenceStore(dir.resolve("evidence"));
            int exit = IngestRunner.run(Adapters.byType("ing-csv"), SAMPLE.getBytes(StandardCharsets.UTF_8),
                "ing-savings.csv", "ing-savings", evidence, new IngestClient(sequencer.url()),
                new java.io.PrintStream(OutputStream.nullOutputStream()));
            assertEquals(IngestRunner.OK, exit);
            assertTrue(sequencer.calls > 0);
            assertEquals(2, sequencer.factsSeen, "both rows reached the sequencer");
            assertEquals(1, evidence.list().size(), "the source bytes are stored");
        }
    }

    @Test
    void aBadRowSendsNothing(@TempDir Path dir) throws Exception {
        String bad = "Date,Description,Credit,Debit,Balance\n31/02/2026,Bad,,-2.00,0.00\n";
        try (FakeSequencer sequencer = new FakeSequencer("Appended")) {
            int exit = IngestRunner.run(Adapters.byType("ing-csv"), bad.getBytes(StandardCharsets.UTF_8),
                "bad.csv", "ing-savings", new EvidenceStore(dir.resolve("e")),
                new IngestClient(sequencer.url()), new java.io.PrintStream(OutputStream.nullOutputStream()));
            assertEquals(IngestRunner.BAD_ROWS, exit);
            assertEquals(0, sequencer.calls, "a bad row sends nothing");
        }
    }

    @Test
    void aRejectedBatchIsExitThree(@TempDir Path dir) throws Exception {
        try (FakeSequencer sequencer = new FakeSequencer("Rejected")) {
            int exit = IngestRunner.run(Adapters.byType("ing-csv"), SAMPLE.getBytes(StandardCharsets.UTF_8),
                "f.csv", "ing-savings", new EvidenceStore(dir.resolve("e")),
                new IngestClient(sequencer.url()), new java.io.PrintStream(OutputStream.nullOutputStream()));
            assertEquals(IngestRunner.REJECTED, exit);
        }
    }

    @Test
    void anUnreachableSequencerIsExitTwo(@TempDir Path dir) throws Exception {
        int exit = IngestRunner.run(Adapters.byType("ing-csv"), SAMPLE.getBytes(StandardCharsets.UTF_8),
            "f.csv", "ing-savings", new EvidenceStore(dir.resolve("e")),
            new IngestClient("http://127.0.0.1:1"), new java.io.PrintStream(OutputStream.nullOutputStream()));
        assertEquals(IngestRunner.TRANSPORT, exit);
    }

    /** A minimal sequencer that answers one outcome for every fact. */
    private static final class FakeSequencer implements AutoCloseable {
        private final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        volatile int calls;
        volatile int factsSeen;
        private final String outcome;

        FakeSequencer(String outcome) throws Exception {
            this.outcome = outcome;
            server.createContext("/facts", exchange -> {
                calls++;
                byte[] raw = exchange.getRequestBody().readAllBytes();
                String encoding = exchange.getRequestHeaders().getFirst("Content-Encoding");
                if (encoding != null && encoding.contains("gzip")) {
                    try (GZIPInputStream in = new GZIPInputStream(new java.io.ByteArrayInputStream(raw))) {
                        raw = in.readAllBytes();
                    }
                }
                Map<String, Object> body = Json.mapper().readValue(raw, Map.class);
                List<?> facts = (List<?>) body.get("facts");
                factsSeen += facts.size();
                List<Map<String, Object>> results = new ArrayList<>();
                for (int i = 0; i < facts.size(); i++) {
                    results.add(map("ref", "fact[" + i + "]", "outcome", outcome, "externalId", "x" + i,
                        "n", i + 1L, "reason", null));
                }
                respond(exchange, 200, Json.mapper().writeValueAsString(
                    map("batchHandle", "h", "batchStatus", "COMMITTED", "results", results)));
            });
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private static Map<String, Object> map(Object... kv) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (int i = 0; i < kv.length; i += 2) {
                out.put((String) kv[i], kv[i + 1]);
            }
            return out;
        }

        private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
                throws java.io.IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
