package trex.v2.hub;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trex.v2.core.Fact;
import trex.v2.core.Observation;
import trex.v2.core.Provenance;
import trex.v2.log.Json;
import trex.v2.log.JsonlJournal;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code GET /api/config/drift} (V2-QOL-IMPROVEMENTS-PLAN.md §3): every seeded file in declaration order,
 * each with the state the pure comparison gives. The temp config dir carries {@code .shipped/} and
 * {@code .base/} beside the live files, with one file per state — repo-newer, edited-here,
 * both-changed, same and unknown (no shipped copy, and no base for a differing file).
 */
class HubConfigDriftApiTest {

    private static final Instant AT = Instant.parse("2026-10-01T00:00:00Z");

    @Test
    void reportsTheSeededFilesInOrderWithTheirStates(@TempDir Path dir) throws Exception {
        Path configDir = dir.resolve("config");
        config(configDir);
        Path shipped = Files.createDirectories(configDir.resolve(".shipped"));
        Path base = Files.createDirectories(configDir.resolve(".base"));

        // categories.yaml: the repo moved, the live file did not -> repo-newer.
        Files.writeString(shipped.resolve("categories.yaml"), "categories: [GROCERIES]\nrules: []\n");
        Files.copy(configDir.resolve("categories.yaml"), base.resolve("categories.yaml"));
        // pins.yaml: edited here, the repo did not move -> edited-here.
        Files.writeString(shipped.resolve("pins.yaml"), "pins: []\n");
        Files.writeString(base.resolve("pins.yaml"), "pins: []\n");
        Files.writeString(configDir.resolve("pins.yaml"), "pins: [noop]\n");
        // accounts.yaml: all three differ -> both-changed.
        Files.writeString(shipped.resolve("accounts.yaml"), "accounts: []\n");
        Files.writeString(base.resolve("accounts.yaml"), "accounts: [old]\n");
        // transfers.yaml: shipped == current, no base needed -> same.
        Files.copy(configDir.resolve("transfers.yaml"), shipped.resolve("transfers.yaml"));
        // profiles.yaml: no live file, no base -> unknown (a deletion with no base).
        Files.writeString(shipped.resolve("profiles.yaml"), "profiles: {}\n");

        Path journal = dir.resolve("trex.jsonl");
        try (JsonlJournal j = new JsonlJournal(journal)) {
            j.appendBatch(List.of(fact(1, "a", "ing-savings")));
        }
        try (HubService hub = HubService.start(new HubConfig(journal, dir.resolve("trex.sqlite"), configDir,
                "127.0.0.1", 0, 50))) {
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + hub.port() + "/api/config/drift"))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), response.body());
            JsonNode body = Json.mapper().readTree(response.body());
            assertTrue(body.isArray(), response.body());
            assertEquals(11, body.size(), response.body());

            List<String> files = new ArrayList<>();
            Map<String, String> states = new HashMap<>();
            for (JsonNode node : body) {
                files.add(node.get("file").asText());
                states.put(node.get("file").asText(), node.get("state").asText());
            }
            assertEquals(ConfigDrift.FILES, files, "declaration order");
            assertEquals(List.of("unknown", "both-changed", "unknown", "repo-newer", "same",
                "edited-here", "unknown", "unknown", "unknown", "unknown", "unknown"),
                ConfigDrift.FILES.stream().map(states::get).toList(), response.body());
        }
    }

    private static Fact fact(long n, String id, String account) {
        return new Fact(n, id, account, LocalDate.of(2026, 9, 1), -1000, 0, "COLES " + id, null, 0,
            Observation.POSTED, "ing-csv", Provenance.BANK, null, "ing-csv/1", AT);
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
}
