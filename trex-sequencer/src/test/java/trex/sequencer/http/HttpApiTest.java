package trex.sequencer.http;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trex.journal.Json;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** API contract, SPEC §3.5, including the HTTP variant of test 12 and the resolution workflow. */
class HttpApiTest {

    @TempDir
    Path dir;

    private static JsonNode json(ApiServer.Reply r) throws Exception {
        return Json.mapper().readTree(r.body());
    }

    private static final String ROW = """
        {"candidateRef":"%s","accountRef":"%s","date":"%s","amount":%s,"rawDescription":"%s","balance":%s,"sourceType":"ing-csv","provenance":"BANK"}""";

    private static String row(String ref, String account, String date, String amount, String raw, String balance) {
        return ROW.formatted(ref, account, date, amount, raw, balance);
    }

    @Test
    void allOrNoneOverHttp() throws Exception {
        String good = row("row-2", "ing-savings", "2026-06-01", "-100", "Coffee", "900");
        String badAmount = row("row-3", "ing-savings", "2026-06-01", "-1.5", "Lunch", "700");
        String badDate = row("row-4", "ing-savings", "01/06/2026", "-300", "Dinner", "400");
        Path j = dir.resolve("j.jsonl");
        try (ApiServer s = new ApiServer(j, 1 << 20)) {
            JsonNode rejected = json(s.post("/candidates",
                "{\"allOrNone\":true,\"batch\":[" + good + "," + badAmount + "," + badDate + "]}", false, false));
            assertEquals("REJECTED", rejected.get("batchStatus").asText());
            assertEquals(2, rejected.get("results").size());
            assertEquals(0, Files.size(j));

            JsonNode partial = json(s.post("/candidates",
                "{\"batch\":[" + good + "," + badAmount + ",42]}", false, false));
            assertEquals("PARTIAL", partial.get("batchStatus").asText());
            assertEquals("Resolved", partial.get("results").get(0).get("type").asText());
            assertEquals("Rejected", partial.get("results").get(1).get("type").asText());
            assertEquals("row-3", partial.get("results").get(1).get("candidateRef").asText());
            assertEquals("idx-2", partial.get("results").get(2).get("candidateRef").asText());
            assertTrue(json(s.get("/head", false)).get("offset").asLong() > 0);
            assertEquals(1, json(s.get("/head", false)).get("n").asLong());
        }
    }

    @Test
    void structuralFailuresAreNon2xx() {
        try (ApiServer s = new ApiServer(dir.resolve("j.jsonl"), 1 << 20)) {
            assertEquals(400, s.post("/candidates", "{not json", false, false).status());
            assertEquals(400, s.post("/candidates", "[]", false, false).status());
            assertEquals(400, s.post("/candidates", "{\"allOrNone\":\"yes\",\"batch\":[]}", false, false).status());
            assertEquals(400, s.post("/candidates", "{\"items\":[]}", false, false).status());
            assertEquals(400, s.postRaw("/candidates", "{\"batch\":[]}".getBytes(StandardCharsets.UTF_8), "gzip").status());
            assertEquals(415, s.postRaw("/candidates", "{\"batch\":[]}".getBytes(StandardCharsets.UTF_8), "br").status());
            assertEquals(405, s.get("/candidates", false).status());
            assertEquals(404, s.get("/nope", false).status());
            assertEquals(404, s.get("/heldx", false).status());
        }
    }

    @Test
    void resolutionWorkflow() throws Exception {
        try (ApiServer s = new ApiServer(dir.resolve("j.jsonl"), 1 << 20)) {
            s.post("/candidates", "{\"batch\":[" + row("r1", "ing-savings", "2026-06-01", "-700", "Osko to Dave", "300") + ","
                + row("r2", "ing-savings", "2026-06-02", "-500", "Fast Transfer", "100") + ","
                + row("r3", "cba-everyday", "2026-06-20", "500", "Transfer from ING", "500") + "]}", false, false);

            JsonNode held = json(s.get("/held", true));
            assertEquals(3, held.size());
            String dave = held.get(0).get("externalId").asText();
            String out = held.get(1).get("externalId").asText();
            String in = held.get(2).get("externalId").asText();

            String decisions = """
                {"decisions":[
                  {"decisionRef":"d1","action":"MARK_EXTERNAL","externalId":"%s","comment":"Dave"},
                  {"decisionRef":"d2","action":"CONFIRM_TRANSFER","legA":"%s","legB":"%s","comment":"slow bank"},
                  {"decisionRef":"d3","action":"EXPLODE"}
                ]}""".formatted(dave, out, in);
            JsonNode r = json(s.post("/decisions", decisions, true, false));
            assertEquals("PARTIAL", r.get("batchStatus").asText());
            assertEquals("Resolved", r.get("results").get(0).get("type").asText());
            assertTrue(r.get("results").get(1).get("externalId").asText().startsWith("TRF-"));
            assertEquals("Rejected", r.get("results").get(2).get("type").asText());

            assertEquals(0, json(s.get("/held", false)).size());
            assertEquals(0, json(s.get("/review", false)).size());

            s.post("/candidates", "{\"batch\":[" + row("r1", "ing-savings", "2026-06-01", "-700", "Osko to Dave", "999") + "]}", false, false);
            JsonNode review = json(s.get("/review", false));
            assertEquals(1, review.size());
            assertEquals("POTENTIAL_DUP", review.get(0).get("flags").get(0).asText());
            assertEquals("EXTERNAL", review.get(0).get("state").asText());
        }
    }

    @Test
    void reconcileReportsPerAccountOverHttp() throws Exception {
        Path j = dir.resolve("reconcile.jsonl");
        try (ApiServer s = new ApiServer(j, 1 << 20)) {
            JsonNode empty = json(s.get("/reconcile", false));
            assertTrue(empty.get("ok").asBoolean(), "an empty journal has nothing that fails to balance");
            assertEquals(0, empty.get("accounts").size());

            String batch = "{\"batch\":["
                + row("row-2", "ing-savings", "2026-06-01", "-1999", "Woolworths", "98001") + ","
                + row("row-3", "ing-savings", "2026-06-01", "-1", "Bank fee", "98000") + ","
                + row("row-4", "ing-savings", "2026-06-02", "250037", "Salary", "348037") + "]}";
            assertEquals(200, s.post("/candidates", batch, false, false).status());

            JsonNode report = json(s.get("/reconcile", false));
            assertTrue(report.get("ok").asBoolean(), () -> "expected a balancing report, got " + report);
            assertEquals(1, report.get("accounts").size());
            JsonNode ing = report.get("accounts").get(0);
            assertEquals("ing-savings", ing.get("accountRef").asText());
            assertTrue(ing.get("reconcilable").asBoolean());
            assertTrue(ing.get("balances").asBoolean());
            assertEquals(100000, ing.get("opening").asLong());
            assertEquals(348037, ing.get("closing").asLong());
            assertEquals(248037, ing.get("sum").asLong());
            // The report names the journal point it was taken at.
            assertEquals(json(s.get("/head", false)).get("n").asLong(), report.get("n").asLong());
            assertEquals(json(s.get("/head", false)).get("offset").asLong(), report.get("offset").asLong());
            // Read-only: reconciling appended nothing.
            assertEquals(3, report.get("n").asLong());
        }
    }

    @Test
    void reconcileIsReadOnlyAndGzips() throws Exception {
        try (ApiServer s = new ApiServer(dir.resolve("ro.jsonl"), 1 << 20)) {
            ApiServer.Reply post = s.post("/reconcile", "{}", false, false);
            assertEquals(405, post.status());

            ApiServer.Reply gz = s.get("/reconcile", true);
            assertEquals(200, gz.status());
            assertEquals("gzip", gz.contentEncoding());
            assertTrue(json(gz).get("ok").asBoolean());
        }
    }
}
