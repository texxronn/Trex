package trex.v2.egress.hub;

import trex.v2.log.Json;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * The egress's only feed (V2-PROPOSAL.md §11.6): it reads the projectable units and the projection
 * state from the hub, and records state back. It never touches the index or the sequencer.
 */
public final class HubClient {

    /** One projectable unit, as the hub publishes it. */
    public record HubUnit(String unitId, String unitKind, long n, String accountRef, String toAccountRef,
                          LocalDate date, long amount, String currency, String category, String origin,
                          String pairing, boolean retired, boolean ineffective, String rawDescription,
                          String unitHash) {}

    public record Units(long asOfN, String configRevision, String deriveVersion, String hashVersion,
                        List<HubUnit> units) {}

    /** One projection-state row, matching the hub's shape. */
    public record ProjectionState(String unitId, String unitKind, String groupId, String category,
                                  String stateHash, String configRevision, String deriveVersion,
                                  String verifiedAt) {}

    private record ProjectionStateList(List<ProjectionState> rows) {}

    private record ProjectionWrite(boolean replace, List<ProjectionState> rows) {}

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final URI base;

    public HubClient(String baseUrl) {
        String trimmed = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.base = URI.create(trimmed);
    }

    /** The units current at the hub's instant, optionally only those at or after {@code sinceN}. */
    public Units units(long sinceN) {
        return get("/api/units?sinceN=" + sinceN, Units.class);
    }

    public List<ProjectionState> projection() {
        return get("/api/projection", ProjectionStateList.class).rows();
    }

    /** One current transaction, as the blotter API publishes it. */
    public record LedgerRow(String externalId, long n, String accountRef, LocalDate date, long amount,
                            long balance, String rawDescription, String leg, String role, String transferId,
                            String category, String categoryOrigin, String ruleId, boolean hasReview) {}

    public record LedgerPage(long total, List<LedgerRow> rows) {}

    /** A page of current transactions (the export reads these). */
    public LedgerPage ledger(int limit, int offset) {
        return get("/api/ledger?limit=" + limit + "&offset=" + offset, LedgerPage.class);
    }

    /** Every current transaction, paged. */
    public List<LedgerRow> allLedger() {
        List<LedgerRow> all = new java.util.ArrayList<>();
        int offset = 0;
        while (true) {
            LedgerPage page = ledger(1000, offset);
            all.addAll(page.rows());
            if (all.size() >= page.total() || page.rows().isEmpty()) {
                return all;
            }
            offset += page.rows().size();
        }
    }

    /** The account ref -> currency map, from the hub's reference data. */
    public Map<String, String> currencyByAccount() {
        com.fasterxml.jackson.databind.JsonNode node = get("/api/refdata",
            com.fasterxml.jackson.databind.JsonNode.class);
        Map<String, String> out = new java.util.TreeMap<>();
        for (com.fasterxml.jackson.databind.JsonNode account : node.path("accounts")) {
            out.put(account.path("ref").asText(), account.path("currency").asText());
        }
        return out;
    }

    /** Per-account openings, for creating missing accounts (V2-PROPOSAL.md §11.3). */
    public List<OpeningState> opening() {
        return get("/api/opening", OpeningList.class).accounts();
    }

    /** Matches the hub's Opening.PerAccount shape. */
    public record OpeningState(String accountRef, String currency, boolean declared, long latestBalance,
                               long backwardOpening, long forwardOpening, long gap, LocalDate openedAt) {}

    private record OpeningList(List<OpeningState> accounts) {}

    /** Record state as a write lands. {@code replace} is the {@code --verify} rebuild path. */
    public void record(boolean replace, List<ProjectionState> rows) {
        post("/api/projection", new ProjectionWrite(replace, rows));
    }

    private <T> T get(String path, Class<T> type) {
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(base.resolve(path))
                .timeout(Duration.ofMinutes(2)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("hub GET " + path + " answered " + response.statusCode()
                    + ": " + response.body());
            }
            return Json.mapper().readValue(response.body(), type);
        } catch (IOException e) {
            throw new IllegalStateException("cannot reach the hub at " + base, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted talking to " + base, e);
        }
    }

    private void post(String path, Object body) {
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(base.resolve(path))
                .timeout(Duration.ofMinutes(2))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(Json.mapper().writeValueAsBytes(body))).build(),
                HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("hub POST " + path + " answered " + response.statusCode()
                    + ": " + response.body());
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot reach the hub at " + base, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted talking to " + base, e);
        }
    }
}
