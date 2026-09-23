package trex.sequencer.http;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trex.journal.Json;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SPEC §7 test 10. */
class GzipTransparencyTest {

    @TempDir
    Path dir;

    static final String BATCH = """
        {"batch":[
          {"candidateRef":"row-2","accountRef":"ing-savings","date":"2026-06-28","amount":-50000,
           "rawDescription":"Fast Transfer to CBA","balance":1000,"receipt":null,"counterpartyBsb":null,
           "counterpartyAcct":null,"sourceType":"ing-csv","provenance":"BANK"},
          {"candidateRef":"row-3","accountRef":"ing-savings","date":"2026-06-28","amount":-450,
           "rawDescription":"COFFEE","balance":550,"sourceType":"ing-csv","provenance":"BANK"}
        ]}
        """;

    @Test
    void fourPathsRoundTrip() throws Exception {
        record Path4(boolean gzipIn, boolean gzipOut) {}
        int i = 0;
        for (Path4 p : List.of(new Path4(false, false), new Path4(true, false), new Path4(false, true), new Path4(true, true))) {
            try (ApiServer s = new ApiServer(dir.resolve("j" + i++ + ".jsonl"), 1 << 20)) {
                ApiServer.Reply r = s.post("/candidates", BATCH, p.gzipIn(), p.gzipOut());
                assertEquals(200, r.status(), r.body());
                if (p.gzipOut()) {
                    assertEquals("gzip", r.contentEncoding());
                } else {
                    assertNull(r.contentEncoding());
                }
                JsonNode body = Json.mapper().readTree(r.body());
                assertEquals("COMMITTED", body.get("batchStatus").asText(), p.toString());
                assertEquals("Held", body.get("results").get(0).get("type").asText());
                assertEquals("Resolved", body.get("results").get(1).get("type").asText());
            }
        }
    }

    @Test
    void gzippedAndPlainPostsYieldIdenticalJournalBytes() throws Exception {
        Path plain = dir.resolve("plain.jsonl");
        Path gz = dir.resolve("gzip.jsonl");
        try (ApiServer s = new ApiServer(plain, 1 << 20)) {
            assertEquals(200, s.post("/candidates", BATCH, false, false).status());
        }
        try (ApiServer s = new ApiServer(gz, 1 << 20)) {
            assertEquals(200, s.post("/candidates", BATCH, true, true).status());
        }
        assertTrue(Files.size(plain) > 0);
        assertArrayEquals(Files.readAllBytes(plain), Files.readAllBytes(gz));
    }

    @Test
    void overCapDecompressedBodyIs413AndAppendsNothing() throws Exception {
        Path j = dir.resolve("j.jsonl");
        String huge = "{\"batch\":[],\"pad\":\"" + "x".repeat(200_000) + "\"}";
        byte[] compressed = ApiServer.gzip(huge.getBytes(StandardCharsets.UTF_8));
        assertTrue(compressed.length < 64_000, "compressed body is under the cap");
        try (ApiServer s = new ApiServer(j, 64_000)) {
            assertEquals(413, s.postRaw("/candidates", compressed, "gzip").status());
            assertEquals(413, s.postRaw("/candidates", huge.getBytes(StandardCharsets.UTF_8), null).status());
        }
        assertEquals(0, Files.size(j));
    }
}
