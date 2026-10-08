package trex.v2.hub;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trex.v2.core.Fact;
import trex.v2.core.Observation;
import trex.v2.core.Provenance;
import trex.v2.log.Json;
import trex.v2.log.JsonlJournal;
import trex.v2.sequencer.api.DecisionDraft;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The decision path (V2-PROPOSAL.md §6.6, §6.8): the hub prechecks for a friendly 422, rejects a
 * stale view with a 409, and forwards accepted decisions to the only writer — never appending to
 * the journal itself.
 */
class HubDecisionPathTest {

    private static final Instant AT = Instant.parse("2026-09-30T00:00:00Z");

    @Test
    void prechecksForwardsAndRejectsStaleAndUnknown(@TempDir Path dir) throws Exception {
        Path configDir = dir.resolve("config");
        config(configDir);
        Path journal = dir.resolve("trex.jsonl");
        try (JsonlJournal j = new JsonlJournal(journal)) {
            j.appendBatch(List.of(new Fact(1, "a", "ing-savings", LocalDate.of(2026, 9, 1), -1000, 0,
                "COLES 1234", null, 0, Observation.POSTED, "ing-csv", Provenance.BANK, null, "ing-csv/1", AT)));
        }

        AtomicReference<String> forwarded = new AtomicReference<>();
        HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/decisions", ex -> {
            forwarded.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"batchHandle\":\"stub\",\"batchStatus\":\"COMMITTED\",\"results\":[]}"
                .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        });
        stub.start();
        String stubUrl = "http://127.0.0.1:" + stub.getAddress().getPort();

        Path index = dir.resolve("trex.sqlite");
        try (HubService hub = HubService.start(new HubConfig(journal, index, configDir, "127.0.0.1", 0, 50, stubUrl))) {
            await(() -> hub.status().counts().getOrDefault("txn_current", 0L) == 1L);
            HttpClient client = HttpClient.newHttpClient();
            URI base = URI.create("http://127.0.0.1:" + hub.port());
            long n = hub.status().n();

            DecisionDraft ok = markExternal("a");
            assertEquals(200, post(client, base, new DecisionRequestJson().asOf(n).add(ok).json()).statusCode());
            assertTrue(forwarded.get().contains("\"action\":\"MARK_EXTERNAL\""));

            assertEquals(409, post(client, base, new DecisionRequestJson().asOf(n + 1).add(ok).json()).statusCode());
            assertEquals(422, post(client, base, new DecisionRequestJson().asOf(n).add(markExternal("ghost")).json())
                .statusCode());
            assertEquals(422, post(client, base, new DecisionRequestJson().asOf(n).add(pin("a", "NOPE")).json())
                .statusCode());

            // A BALANCE_BREAK's subject is an account ref, not a fact id (§6.9).
            DecisionDraft dismissAccount = draft("DISMISS", "user", "ron", null, null, "BALANCE_BREAK",
                List.of("ing-savings"));
            assertEquals(200, post(client, base, new DecisionRequestJson().asOf(n).add(dismissAccount).json())
                .statusCode());
            DecisionDraft dismissGhost = draft("DISMISS", "user", "ron", null, null, "BALANCE_BREAK",
                List.of("ghost"));
            assertEquals(422, post(client, base, new DecisionRequestJson().asOf(n).add(dismissGhost).json())
                .statusCode());
        }

        try (HubService readOnly = HubService.start(new HubConfig(journal, index, configDir, "127.0.0.1", 0, 50))) {
            await(() -> readOnly.status().counts().getOrDefault("txn_current", 0L) == 1L);
            HttpClient client = HttpClient.newHttpClient();
            URI base = URI.create("http://127.0.0.1:" + readOnly.port());
            assertEquals(503, post(client, base, new DecisionRequestJson().add(markExternal("a")).json()).statusCode());
        }
        stub.stop(0);
    }

    // ---- request body construction ----------------------------------------------------------

    private static final class DecisionRequestJson {
        private Long asOf;
        private final List<DecisionDraft> decisions = new java.util.ArrayList<>();

        DecisionRequestJson asOf(long value) {
            asOf = value;
            return this;
        }

        DecisionRequestJson add(DecisionDraft d) {
            decisions.add(d);
            return this;
        }

        String json() throws Exception {
            return Json.mapper().writeValueAsString(new Body(asOf, decisions));
        }

        private record Body(Long asOfN, List<DecisionDraft> decisions) {}
    }

    private static DecisionDraft markExternal(String id) {
        return draft("MARK_EXTERNAL", "user", "ron", id, null, null, List.of());
    }

    private static DecisionDraft pin(String id, String category) {
        return draft("PIN", "user", "ron", null, category, null, List.of(id));
    }

    private static DecisionDraft draft(String action, String actor, String user, String externalId,
                                       String category, String item, List<String> ids) {
        return new DecisionDraft(action, actor, user, AT, "test", null, null, externalId, null, null,
            item, ids.isEmpty() ? null : ids, category, null, null, null, null, null, null, null, null, null, null);
    }

    private static HttpResponse<String> post(HttpClient client, URI base, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(base.resolve("/api/decisions"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void config(Path configDir) throws Exception {
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("accounts.yaml"), """
            accounts:
              - ref: "ing-savings"
                currency: "AUD"
                balanceSource: statement
            """);
        Files.writeString(configDir.resolve("users.yaml"), """
            users:
              - id: "ron"
                name: "Ron"
                active: true
            """);
        Files.writeString(configDir.resolve("categories.yaml"), """
            categories: [GROCERIES]
            rules:
              - category: GROCERIES
                when:
                  match: "COLES"
            """);
        Files.writeString(configDir.resolve("transfers.yaml"), """
            windowDays: 4
            allowlist:
              - 'Transfer'
            """);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        assertTrue(condition.getAsBoolean(), "condition not met within 10s");
    }
}
