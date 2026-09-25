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
import java.util.Map;

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

    /**
     * The balance each account must start from for Firefly to reconcile against the bank.
     * <p>
     * SPEC §0.1 permits exactly this use: {@code balance} is provenance and <em>reconciliation</em>
     * only, never identity and never transaction semantics, and making a downstream ledger
     * reconcile is reconciliation.
     * <p>
     * Derived <b>backwards</b> — the most recent line's balance minus every amount since the
     * beginning. Anchoring forwards, to the earliest line, seems more natural and is wrong: a
     * statement does not record the order of transactions within a day, so when two share the
     * earliest date the implied opening differs by one of their amounts depending on which you
     * pick. On this journal that was a $13,661 coin toss. Summing every amount removes the
     * question.
     * <p>
     * Both are still computed, because their disagreement is worth more than either figure alone:
     * it is exactly the value of the transactions the bank's running balance knows about and the
     * journal does not — an account ingested from a partial export. Where they agree, the figure
     * is sound; where they differ, the opening balance has to come from a statement.
     */
    public Map<String, Opening> openingBalances() throws IOException, InterruptedException {
        Map<String, List<CanonicalEvent>> byAccount = new java.util.LinkedHashMap<>();
        Map<String, CanonicalEvent> declaredAnchor = new java.util.LinkedHashMap<>();
        for (CanonicalEvent line : allRows()) {
            if (line.typeHint() == trex.core.TypeHint.TRANSFER) {
                continue;                     // a TRANSFER line carries no balance of its own
            }
            if (line.typeHint() == trex.core.TypeHint.ATTESTATION) {
                // The ONLY line on a declared account that carries a balance, and it is the most
                // authoritative figure available for it. Backward anchoring is already what this
                // method does, so the latest attestation is naturally the anchor — but the
                // forward/gap comparison below is meaningless on a chain with deliberate gaps,
                // and is suppressed for those accounts by the caller.
                declaredAnchor.merge(line.accountRef(), line, (a, b) -> b.n() > a.n() ? b : a);
            }
            byAccount.computeIfAbsent(line.accountRef(), _ -> new ArrayList<>()).add(line);
        }
        Map<String, Opening> out = new java.util.LinkedHashMap<>();
        // A declared account opens on what was last attested: there is no chain to walk, so the
        // gap is reported as 0 rather than computed from a forward pass that cannot mean anything.
        declaredAnchor.forEach((ref, last) ->
            out.put(ref, new Opening(last.balance(), last.date(), 0)));
        byAccount.forEach((ref, rows) -> {
            if (out.containsKey(ref)) {
                return;
            }
            java.util.Comparator<CanonicalEvent> order =
                java.util.Comparator.comparing(CanonicalEvent::date).thenComparingLong(CanonicalEvent::n);
            CanonicalEvent first = rows.stream().min(order).orElseThrow();
            CanonicalEvent last = rows.stream().max(order).orElseThrow();
            long moved = rows.stream().mapToLong(CanonicalEvent::amount).sum();
            long backward = last.balance() - moved;
            long forward = first.balance() - first.amount();
            out.put(ref, new Opening(backward, first.date().minusDays(1), forward - backward));
        });
        return out;
    }

    /**
     * What an account held before trex saw anything.
     *
     * @param cents  derived backwards from the most recent balance; order-independent
     * @param asOf   the day before the first transaction trex holds
     * @param gap    how far the forward derivation disagrees, in cents. Non-zero means the bank's
     *               running balance accounts for transactions the journal does not have, so this
     *               figure makes today's balance right while the history stays short. Take the
     *               opening from a statement instead.
     */
    public record Opening(long cents, java.time.LocalDate asOf, long gap) {

        public boolean confident() {
            return gap == 0;
        }
    }

    /** Every transaction the gateway holds, unfiltered — the population balances are read from. */
    private List<CanonicalEvent> allRows() throws IOException, InterruptedException {
        List<CanonicalEvent> out = new ArrayList<>();
        for (int page = 1; ; page++) {
            JsonNode body = get("/api/snapshot?view=transactions&sort=n:asc&size=" + PAGE + "&page=" + page);
            JsonNode rows = body.path("rows");
            for (JsonNode row : rows) {
                out.add(Json.mapper().treeToValue(row, CanonicalEvent.class));
            }
            if (rows.isEmpty() || (long) page * PAGE >= body.path("total").asInt()) {
                return out;
            }
        }
    }

    /** The declared categories, each with the first rule comment that explains it. */
    public Map<String, String> categoryNotes() throws IOException, InterruptedException {
        JsonNode body = get("/api/rules");
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (JsonNode c : body.path("categories")) {
            out.put(c.asText(), null);
        }
        for (JsonNode r : body.path("rules")) {
            String category = r.path("category").asText(null);
            String comment = r.path("comment").asText(null);
            if (category != null && comment != null && !comment.isBlank() && out.get(category) == null) {
                out.put(category, comment);
            }
        }
        return out;
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
