package trex.egress.firefly;

import com.fasterxml.jackson.databind.JsonNode;
import trex.journal.Json;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintStream;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One projection pass. SPEC §5.8.
 * <p>
 * Post what is new, re-tag what a rules change moved, and leave everything else alone. Nothing is
 * ever deleted: decisions are final (§3.5), so a projected unit cannot stop being projectable, and
 * a delete path would exist only to act on a bug.
 */
public final class FireflyEgress {

    private static final Logger log = LoggerFactory.getLogger(FireflyEgress.class);

    private final GatewayClient gateway;
    private final FireflyClient firefly;
    private final ProjectionCache cache;
    private final AccountMap accounts;
    private final boolean dryRun;

    public FireflyEgress(GatewayClient gateway, FireflyClient firefly, ProjectionCache cache,
                         AccountMap accounts, boolean dryRun) {
        this.gateway = gateway;
        this.firefly = firefly;
        this.cache = cache;
        this.accounts = accounts;
        this.dryRun = dryRun;
    }

    public record Outcome(int posted, int retagged, int duplicates, int unchanged, int failed, int preserved) {

        public String describe() {
            return "posted %d, re-tagged %d, already there %d, unchanged %d, your edits preserved %d, failed %d"
                .formatted(posted, retagged, duplicates, unchanged, preserved, failed);
        }
    }

    /**
     * Rebuild the cache from Firefly. The same code path as a deleted cache, so it runs whenever
     * anyone verifies rather than being an emergency path that has never executed (§5.8).
     */
    public int rebuildCache() throws IOException, InterruptedException, SQLException {
        List<FireflyClient.Existing> existing = firefly.allTransactions();
        List<ProjectionCache.Row> rows = new ArrayList<>();
        for (FireflyClient.Existing e : existing) {
            rows.add(new ProjectionCache.Row(e.externalId(), e.journalN(), e.groupId(),
                projectedCategory(e), ""));
        }
        cache.replaceAll(rows);
        return rows.size();
    }

    /** What trex last said, read off our own tag — not Firefly's category, which you may have edited. */
    static String projectedCategory(FireflyClient.Existing e) {
        return e.tags().stream()
            .filter(t -> t.startsWith(Projection.CATEGORY_TAG_PREFIX))
            .map(t -> t.substring(Projection.CATEGORY_TAG_PREFIX.length()))
            .findFirst()
            .orElse("");
    }

    public Outcome run(PrintStream out) throws IOException, InterruptedException, SQLException {
        Map<String, ProjectionCache.Row> known = cache.all();
        long since = cache.highWater();
        String lastRevision = cache.rulesRevision();

        GatewayClient.Snapshot snapshot = gateway.since(since);
        String revision = snapshot.rulesRevision();

        // A rules change can move any row, including ones long since projected, so it widens the
        // pass from "what is new" to "everything". Without this a retune never reaches Firefly.
        List<GatewayClient.Unit> units = snapshot.units();
        boolean rulesMoved = revision != null && !revision.equals(lastRevision);
        if (rulesMoved && since > 0) {
            log.info("rules moved {} -> {}; re-checking every projected transaction", lastRevision, revision);
            units = gateway.since(0).units();
        }

        int posted = 0, retagged = 0, duplicates = 0, unchanged = 0, failed = 0, preserved = 0;
        // The first projection is ~1751 sequential posts and takes tens of minutes against a
        // containerised Firefly. Silence for that long is indistinguishable from a hang, so the
        // pass reports where it is and what it thinks is left.
        Progress progress = new Progress(units.size(), out, dryRun);
        for (GatewayClient.Unit unit : units) {
            progress.tick();
            ProjectionCache.Row row = known.get(unit.line().externalId());
            if (row == null) {
                switch (post(unit, revision, out)) {
                    case CREATED -> posted++;
                    case DUPLICATE -> duplicates++;
                    case FAILED -> failed++;
                }
            } else if (row.category().equals(unit.category())) {
                unchanged++;
            } else {
                switch (retag(unit, row, revision, out)) {
                    case CREATED -> retagged++;
                    case PRESERVED -> preserved++;
                    case FAILED -> failed++;
                    default -> { }
                }
            }
        }

        progress.done();
        if (!dryRun) {
            // Only after the writes for this pass are recorded: at-least-once, and a re-delivery
            // is caught by external_id (§5.1).
            cache.highWater(snapshot.asOfN());
            cache.rulesRevision(revision);
        }
        return new Outcome(posted, retagged, duplicates, unchanged, failed, preserved);
    }

    /** A line every 50, with a rate and an estimate, so a long pass is legible while it runs. */
    private static final class Progress {
        private static final int EVERY = 50;

        private final int total;
        private final PrintStream out;
        private final boolean quiet;
        private final long startedAt = System.nanoTime();
        private long windowAt = System.nanoTime();
        private int seen;

        Progress(int total, PrintStream out, boolean quiet) {
            this.total = total;
            this.out = out;
            this.quiet = quiet;
        }

        void tick() {
            seen++;
            if (quiet || seen % EVERY != 0) {
                return;
            }
            // Rate over the last window, not over the whole pass. A resumed run skips everything
            // already projected in microseconds, and averaging those in reports a rate that is
            // true of nothing and an estimate that is wildly short.
            long now = System.nanoTime();
            double windowSeconds = (now - windowAt) / 1e9;
            double rate = EVERY / Math.max(windowSeconds, 0.001);
            long remaining = Math.round((total - seen) / Math.max(rate, 0.001));
            windowAt = now;
            out.printf("  %d/%d  %.1f/s  about %d:%02d left%n",
                seen, total, rate, remaining / 60, remaining % 60);
            out.flush();
        }

        void done() {
            if (!quiet && seen >= EVERY) {
                out.printf("  %d/%d in %.0fs%n", seen, total, (System.nanoTime() - startedAt) / 1e9);
            }
        }
    }

    private enum Step { CREATED, DUPLICATE, PRESERVED, FAILED }

    private Step post(GatewayClient.Unit unit, String revision, PrintStream out)
            throws IOException, InterruptedException, SQLException {
        Projection.Posting posting = Projection.of(unit.line(), unit.category(), revision, accounts);
        if (dryRun) {
            out.println("  POST   " + summarise(posting));
            return Step.CREATED;
        }
        FireflyClient.Result result = firefly.post(posting);
        switch (result) {
            case FireflyClient.Result.Created c -> {
                cache.record(row(unit, c.groupId(), revision));
                return Step.CREATED;
            }
            case FireflyClient.Result.Duplicate d -> {
                // Already in Firefly, and the rejection named it — the path a lost cache takes.
                cache.record(row(unit, d.groupId(), revision));
                return Step.DUPLICATE;
            }
            case FireflyClient.Result.Failed f -> {
                log.error("post {} -> {}: {}", unit.line().externalId(), f.status(), f.message());
                return Step.FAILED;
            }
        }
    }

    /**
     * Read, change only the tag and (if you have not touched it) the category, write back.
     * <p>
     * Read-modify-write is a rule, not a preference: a body built from scratch collapses a split
     * group back to one line and destroys the work silently. {@code group_title} has to come back
     * too, or Firefly refuses a multi-split group.
     */
    @SuppressWarnings("unchecked")
    private Step retag(GatewayClient.Unit unit, ProjectionCache.Row row, String revision, PrintStream out)
            throws IOException, InterruptedException, SQLException {
        if (dryRun) {
            out.println("  RETAG  " + row.externalId() + "  " + row.category() + " -> " + unit.category());
            return Step.CREATED;
        }
        JsonNode group = firefly.group(row.groupId()).path("data").path("attributes");
        Map<String, Object> body = new LinkedHashMap<>();
        if (!group.path("group_title").isNull() && !group.path("group_title").asText("").isEmpty()) {
            body.put("group_title", group.path("group_title").asText());
        }
        List<Map<String, Object>> splits = new ArrayList<>();
        boolean anyPreserved = false;
        for (JsonNode s : group.path("transactions")) {
            Map<String, Object> split = Json.mapper().convertValue(s, Map.class);
            String tagCategory = tagCategory(s);
            String current = s.path("category_name").asText(null);
            // Compare and swap: your edit wins whenever the category no longer matches what we
            // last said, and so does an unreadable tag. When the evidence is unclear, the human.
            boolean untouched = tagCategory != null && tagCategory.equals(current);
            if (untouched) {
                split.put("category_name", unit.category());
            } else {
                anyPreserved = true;
            }
            split.put("tags", List.of(Projection.TAG, Projection.CATEGORY_TAG_PREFIX + unit.category()));
            splits.add(split);
        }
        body.put("apply_rules", false);
        body.put("transactions", splits);

        FireflyClient.Result result = firefly.put(row.groupId(), body);
        if (result instanceof FireflyClient.Result.Failed f) {
            log.error("re-tag {} -> {}: {}", row.externalId(), f.status(), f.message());
            return Step.FAILED;
        }
        cache.record(row(unit, row.groupId(), revision));
        return anyPreserved ? Step.PRESERVED : Step.CREATED;
    }

    private static String tagCategory(JsonNode split) {
        for (JsonNode t : split.path("tags")) {
            String v = t.asText();
            if (v.startsWith(Projection.CATEGORY_TAG_PREFIX)) {
                return v.substring(Projection.CATEGORY_TAG_PREFIX.length());
            }
        }
        return null;
    }

    private static ProjectionCache.Row row(GatewayClient.Unit unit, String groupId, String revision) {
        return new ProjectionCache.Row(unit.line().externalId(), unit.line().n(), groupId,
            unit.category(), revision);
    }

    @SuppressWarnings("unchecked")
    private static String summarise(Projection.Posting p) {
        Map<String, Object> s = ((List<Map<String, Object>>) p.body().get("transactions")).getFirst();
        return "%-10s %-10s %10s  %-22s %s".formatted(
            s.get("type"), s.get("date"), s.get("amount"),
            s.getOrDefault("destination_name", s.getOrDefault("source_name", "id=" + s.get("destination_id"))),
            s.get("category_name"));
    }
}
