package trex.v2.sequencer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trex.v2.core.Observation;
import trex.v2.core.Provenance;
import trex.v2.log.Json;
import trex.v2.sequencer.api.FactBatch;
import trex.v2.sequencer.api.FactDraft;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The maintenance snapshot (V2-PROPOSAL.md §12.6): a consistent gzip copy, never a rotation. */
class SequencerSnapshotTest {

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
    void snapshotIsAGzipCopyOfTheLivePrefix(@TempDir Path dir) throws Exception {
        Path configDir = dir.resolve("config");
        Files.createDirectories(configDir);
        config(configDir);
        Path journal = dir.resolve("journal").resolve("trex.jsonl");
        Files.createDirectories(journal.getParent());
        Path archive = dir.resolve("archive");

        try (SequencerService service = SequencerService.start(journal, journal, configDir, "127.0.0.1", 0,
                Clock.fixed(Instant.parse("2026-09-29T08:00:00Z"), ZoneOffset.UTC), archive)) {
            HttpClient client = HttpClient.newHttpClient();
            URI base = URI.create("http://127.0.0.1:" + service.port());
            FactDraft draft = new FactDraft("ing-savings", LocalDate.of(2026, 9, 1), -1000L, 500L,
                "COLES 1234", null, Observation.POSTED, "ing-csv", Provenance.BANK, null, "ing-csv/1", null);
            byte[] json = Json.mapper().writeValueAsBytes(new FactBatch(false, List.of(draft)));
            client.send(HttpRequest.newBuilder(base.resolve("/facts"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(gzip(json))).build(),
                HttpResponse.BodyHandlers.ofByteArray());

            HttpResponse<String> snapshot = client.send(HttpRequest.newBuilder(base.resolve("/maintenance/snapshot"))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, snapshot.statusCode());
            assertTrue(snapshot.body().contains("\"path\""), snapshot.body());

            try (var files = Files.list(archive.resolve("journal"))) {
                Path gz = files.filter(p -> p.toString().endsWith(".gz")).findFirst().orElseThrow();
                byte[] decompressed;
                try (GZIPInputStream in = new GZIPInputStream(Files.newInputStream(gz))) {
                    decompressed = in.readAllBytes();
                }
                assertArrayEquals(Files.readAllBytes(journal), decompressed);
            }
        }
    }

    private static byte[] gzip(byte[] bytes) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(bytes);
        }
        return out.toByteArray();
    }
}
