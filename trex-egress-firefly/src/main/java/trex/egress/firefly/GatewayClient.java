package trex.egress.firefly;

import com.fasterxml.jackson.databind.JsonNode;
import trex.core.CanonicalEvent;
import trex.journal.Json;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The consumer API (SPEC §5.7), read for what to project.
 * <p>
 * Not a journal follower. Categories come from the one owner, already derived, with the
 * {@code rulesRevision} that produced them — so the egress can never project under a rule set the
 * UI never showed (DECISIONS V1). This is the second consumer the gateway was split out for.
 */
public final class GatewayClient {

    /** The gateway caps a page at 500. */
    static final int PAGE = 500;

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    private final URI base;

    public GatewayClient(URI base) {
        this.base = base;
    }

    /** A row with the category the gateway derived for it. */
    public record Unit(CanonicalEvent line, String category) {}

    public record Snapshot(long asOfN, String rulesRevision, List<Unit> units) {}

    public record Head(long n, String rulesRevision, int transactions) {}

    public Head head() throws IOException, InterruptedException {
        JsonNode body = get("/api/head");
        return new Head(body.path("n").asLong(), body.path("rulesRevision").asText(null),
            body.path("transactions").asInt());
    }

    /**
     * Everything projectable whose latest line is newer than {@code sinceN}, paged.
     * <p>
     * {@code sinceN} is why the gateway grew a filter: without it this pages the whole journal on
     * every run to find the handful that moved. Filtering happens here rather than in the query so
     * the gateway stays unaware of what a "projectable unit" is — that is this module's rule.
     */
    public Snapshot since(long sinceN) throws IOException, InterruptedException {
        List<Unit> units = new ArrayList<>();
        long asOfN = 0;
        String revision = null;
        for (int page = 1; ; page++) {
            JsonNode body = get("/api/snapshot?view=transactions&sort=n:asc&size=" + PAGE
                + "&page=" + page + "&sinceN=" + sinceN);
            asOfN = body.path("asOfN").asLong();
            revision = body.path("rulesRevision").asText(null);
            JsonNode rows = body.path("rows");
            JsonNode categories = body.path("categories");
            for (JsonNode row : rows) {
                CanonicalEvent line = Json.mapper().treeToValue(row, CanonicalEvent.class);
                if (!Projection.projectable(line)) {
                    continue;
                }
                String category = categories.path(String.valueOf(line.n())).path("category").asText("UNCATEGORIZED");
                units.add(new Unit(line, category));
            }
            int total = body.path("total").asInt();
            if (rows.isEmpty() || (long) page * PAGE >= total) {
                return new Snapshot(asOfN, revision, List.copyOf(units));
            }
        }
    }

    private JsonNode get(String path) throws IOException, InterruptedException {
        HttpResponse<String> r = http.send(
            HttpRequest.newBuilder(base.resolve(path)).GET().timeout(Duration.ofSeconds(60)).build(),
            HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() / 100 != 2) {
            throw new IOException("GET " + path + " -> " + r.statusCode() + ": " + r.body());
        }
        return Json.mapper().readTree(r.body());
    }
}
