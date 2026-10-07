package trex.v2.sequencer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trex.v2.core.Observation;
import trex.v2.core.Provenance;
import trex.v2.log.Json;
import trex.v2.log.JsonlJournal;
import trex.v2.sequencer.api.BatchResponse;
import trex.v2.sequencer.api.DecisionBatch;
import trex.v2.sequencer.api.DecisionDraft;
import trex.v2.sequencer.api.FactBatch;
import trex.v2.sequencer.api.FactDraft;
import trex.v2.sequencer.api.HeadResponse;
import trex.v2.sequencer.api.RowResult;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The service end to end (V2-PROPOSAL.md §6.5): recovery, the writer lock and the HTTP API, with
 * gzip on the wire and the journal as the only record.
 */
class SequencerServiceTest {

    private static void config(Path dir) throws Exception {
        Files.writeString(dir.resolve("accounts.yaml"), """
            accounts:
              - ref: "ing-savings"
                currency: "AUD"
                balanceSource: statement
            """);
        Files.writeString(dir.resolve("users.yaml"), """
            users:
              - id: "ron"
                name: "Ron"
                active: true
                cadence: weekly
            """);
        Files.writeString(dir.resolve("categories.yaml"), """
            categories: [GROCERIES]
            rules:
              - category: GROCERIES
                when:
                  match: "COLES"
            """);
        Files.writeString(dir.resolve("transfers.yaml"), """
            windowDays: 4
            allowlist:
              - 'Transfer'
            """);
    }

    @Test
    void factsAndDecisionsOverHttp(@TempDir Path dir) throws Exception {
        Path configDir = dir.resolve("config");
        Files.createDirectories(configDir);
        config(configDir);
        Path journal = dir.resolve("journal").resolve("trex.jsonl");
        Files.createDirectories(journal.getParent());

        try (SequencerService service = SequencerService.start(journal, configDir, "127.0.0.1", 0,
                Clock.fixed(Instant.parse("2026-09-29T08:00:00Z"), ZoneOffset.UTC))) {
            HttpClient client = HttpClient.newHttpClient();
            URI base = URI.create("http://127.0.0.1:" + service.port());

            FactDraft draft = new FactDraft("ing-savings", LocalDate.of(2026, 9, 1), -1000L, 500L,
                "COLES 1234", null, Observation.POSTED, "ing-csv", Provenance.BANK, null, "ing-csv/1", null);
            HttpResponse<byte[]> facts = postGzip(client, base.resolve("/facts"),
                new FactBatch(false, List.of(draft)));
            assertEquals(200, facts.statusCode());
            assertTrue(facts.headers().firstValue("Content-Encoding").orElse("").contains("gzip"));
            BatchResponse factResponse = Json.mapper().readValue(body(facts), BatchResponse.class);
            assertEquals(RowResult.APPENDED, factResponse.results().getFirst().outcome());
            String id = factResponse.results().getFirst().externalId();

            HttpResponse<byte[]> decisions = postGzip(client, base.resolve("/decisions"),
                new DecisionBatch(List.of(new DecisionDraft("MARK_EXTERNAL", "user", "ron",
                    Instant.parse("2026-09-29T08:00:00Z"), "ordinary", null, null, id, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null))));
            assertEquals(200, decisions.statusCode());
            assertEquals(RowResult.RESOLVED,
                Json.mapper().readValue(body(decisions), BatchResponse.class).results().getFirst().outcome());

            HttpResponse<String> head = client.send(HttpRequest.newBuilder(base.resolve("/head")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(new HeadResponse(2, Files.size(journal)), Json.mapper().readValue(head.body(), HeadResponse.class));
        }

        // The journal is the record: two lines, re-openable and re-foldable.
        try (JsonlJournal journalFile = new JsonlJournal(journal)) {
            assertEquals(2, journalFile.replayFrom(0).count());
        }
    }

    @Test
    void materializesAnAlternativeJournalAndWritesOnlyTheCopy(@TempDir Path dir) throws Exception {
        Path configDir = dir.resolve("config");
        Files.createDirectories(configDir);
        config(configDir);
        Path source = dir.resolve("source.jsonl");
        Path target = dir.resolve("copy.jsonl");
        byte[] sourceLine;
        try (JsonlJournal src = new JsonlJournal(source)) {
            src.appendBatch(List.of(new trex.v2.core.Fact(1, "src1", "ing-savings", LocalDate.of(2026, 9, 1),
                -1000, 0, "COLES 1234", null, 0, Observation.POSTED, "ing-csv", Provenance.BANK, null,
                "ing-csv/1", Instant.parse("2026-09-30T00:00:00Z"))));
        }
        sourceLine = Files.readAllBytes(source);

        try (SequencerService service = SequencerService.start(source, target, configDir, "127.0.0.1", 0,
                Clock.fixed(Instant.parse("2026-09-29T08:00:00Z"), ZoneOffset.UTC))) {
            // The target is a verified byte-copy of the authoritative source.
            assertArrayEquals(sourceLine, Files.readAllBytes(target));
            assertEquals(1, service.sequencer().headN());

            HttpClient client = HttpClient.newHttpClient();
            URI base = URI.create("http://127.0.0.1:" + service.port());
            FactDraft draft = new FactDraft("ing-savings", LocalDate.of(2026, 9, 2), -2000L, 0L,
                "COLES 5678", null, Observation.POSTED, "ing-csv", Provenance.BANK, null, "ing-csv/1", null);
            HttpResponse<byte[]> response = postGzip(client, base.resolve("/facts"),
                new FactBatch(false, List.of(draft)));
            assertEquals(200, response.statusCode());
            assertEquals(2, service.sequencer().headN());

            // Writes went to the copy; the authoritative original is untouched.
            assertArrayEquals(sourceLine, Files.readAllBytes(source));
            assertTrue(Files.size(target) > sourceLine.length);
        }
    }

    @Test
    void aSecondSequencerCannotTakeTheLock(@TempDir Path dir) throws Exception {
        Path configDir = dir.resolve("config");
        Files.createDirectories(configDir);
        config(configDir);
        Path journal = dir.resolve("trex.jsonl");
        try (SequencerService first = SequencerService.start(journal, configDir, "127.0.0.1", 0, Clock.systemUTC())) {
            assertTrue(first.port() > 0);
            try {
                SequencerService.start(journal, configDir, "127.0.0.1", 0, Clock.systemUTC());
                throw new AssertionError("a second writer must be refused");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage().contains("locked"));
            }
        }
    }

    @Test
    void rejectsUnsupportedEncodingAndUnknownPaths(@TempDir Path dir) throws Exception {
        Path configDir = dir.resolve("config");
        Files.createDirectories(configDir);
        config(configDir);
        Path journal = dir.resolve("trex.jsonl");
        try (SequencerService service = SequencerService.start(journal, configDir, "127.0.0.1", 0,
                Clock.systemUTC())) {
            HttpClient client = HttpClient.newHttpClient();
            URI base = URI.create("http://127.0.0.1:" + service.port());

            HttpResponse<byte[]> unsupported = client.send(HttpRequest.newBuilder(base.resolve("/facts"))
                .header("Content-Encoding", "br")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(415, unsupported.statusCode());

            HttpResponse<byte[]> unknown = client.send(HttpRequest.newBuilder(base.resolve("/head/extra"))
                .GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(404, unknown.statusCode());

            HttpResponse<byte[]> wrongMethod = client.send(HttpRequest.newBuilder(base.resolve("/facts"))
                .GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(405, wrongMethod.statusCode());
        }
    }

    private static HttpResponse<byte[]> postGzip(HttpClient client, URI uri, Object body) throws Exception {
        byte[] json = Json.mapper().writeValueAsBytes(body);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(json);
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
            .header("Content-Type", "application/json")
            .header("Content-Encoding", "gzip")
            .header("Accept-Encoding", "gzip")
            .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()))
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    /** The response body, gunzipped when the server honoured Accept-Encoding (HttpClient does not). */
    private static byte[] body(HttpResponse<byte[]> response) throws Exception {
        byte[] bytes = response.body();
        String encoding = response.headers().firstValue("Content-Encoding").orElse("");
        if (encoding.contains("gzip")) {
            try (java.util.zip.GZIPInputStream in = new java.util.zip.GZIPInputStream(
                    new java.io.ByteArrayInputStream(bytes))) {
                return in.readAllBytes();
            }
        }
        return bytes;
    }
}
