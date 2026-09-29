package trex.v2.egress.firefly;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import trex.v2.egress.hub.HubClient;
import trex.v2.egress.hub.HubClient.HubUnit;
import trex.v2.egress.hub.HubClient.ProjectionState;

import java.io.IOException;
import java.io.PrintStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * One projection pass (V2-PROPOSAL.md §11). It projects resolved units, not log lines: legs are
 * skipped, an ATTESTATION is never posted, HELD/REVIEW and PENDING are withheld, and a retired fact
 * is gone. Nothing is deleted automatically: a de-projected unit is reported as an orphan and
 * removed only under {@code --remove-orphans}.
 */
public final class FireflyEgress {

    private static final Logger log = LoggerFactory.getLogger(FireflyEgress.class);

    public enum Mode { PLAN, APPLY, VERIFY }

    public record Outcome(int creates, int retags, int updates, int unchanged, int orphans, int removed,
                          int preserved) {

        public boolean empty() {
            return creates == 0 && retags == 0 && updates == 0 && orphans == 0;
        }
    }

    /** Firefly refused something, and no amount of retrying will change that (§11.7). */
    public static final class Refused extends RuntimeException {
        public Refused(String message) {
            super(message);
        }
    }

    private final HubClient hub;
    private final FireflyClient firefly;
    private final AccountMap accounts;
    private final Mode mode;
    private final boolean removeOrphans;
    private final PrintStream out;
    private final String deriveVersion;

    public FireflyEgress(HubClient hub, FireflyClient firefly, AccountMap accounts, Mode mode,
                         boolean removeOrphans, PrintStream out, String deriveVersion) {
        this.hub = hub;
        this.firefly = firefly;
        this.accounts = accounts;
        this.mode = mode;
        this.removeOrphans = removeOrphans;
        this.out = out;
        this.deriveVersion = deriveVersion;
    }

    public Outcome run() throws IOException, InterruptedException {
        HubClient.Units snapshot = hub.units(0);
        String revision = snapshot.configRevision();
        preflight(snapshot.units());

        final Map<String, ProjectionState> known = new TreeMap<>();
        for (ProjectionState row : hub.projection()) {
            known.put(row.unitId(), row);
        }
        if (mode == Mode.VERIFY) {
            // Recovery is a first-class path, not an emergency: rebuild the accelerator from Firefly.
            known.clear();
            known.putAll(rebuildFromFirefly());
            hub.record(true, new ArrayList<>(known.values()));
        }

        List<HubUnit> creates = new ArrayList<>();
        List<HubUnit> moves = new ArrayList<>();
        int unchanged = 0;
        TreeSet<String> currentIds = new TreeSet<>();
        for (HubUnit unit : snapshot.units()) {
            currentIds.add(unit.unitId());
            ProjectionState row = known.get(unit.unitId());
            if (row == null) {
                creates.add(unit);
            } else if (!unit.category().equals(row.category())) {
                moves.add(unit);
            } else if (row.stateHash() != null && !row.stateHash().isBlank()
                && !row.stateHash().equals(unit.unitHash())) {
                moves.add(unit);
            } else {
                unchanged++;
            }
        }
        List<ProjectionState> orphans = known.values().stream()
            .filter(r -> !currentIds.contains(r.unitId())).toList();

        out.printf("%s: %d create, %d retag/update, %d unchanged, %d orphan(s)%n",
            mode, creates.size(), moves.size(), unchanged, orphans.size());
        if (mode != Mode.APPLY) {
            creates.forEach(u -> out.println("  CREATE " + summarise(u)));
            moves.forEach(u -> out.println("  RETAG  " + u.unitId() + "  " + known.get(u.unitId()).category()
                + " -> " + u.category()));
            orphans.forEach(o -> out.println("  ORPHAN " + o.unitId() + " (group " + o.groupId()
                + ", category " + o.category() + ") — not deleted; rerun with --remove-orphans"));
            return new Outcome(creates.size(), moves.size(), 0, unchanged, orphans.size(), 0, 0);
        }

        int created = 0;
        int retagged = 0;
        int updated = 0;
        int preserved = 0;
        Progress progress = new Progress(creates.size() + moves.size(), out);
        for (HubUnit unit : creates) {
            progress.tick();
            created += create(unit, revision, known);
        }
        for (HubUnit unit : moves) {
            progress.tick();
            Step step = retag(unit, known.get(unit.unitId()), revision, known);
            if (step == Step.PRESERVED) {
                preserved++;
            } else {
                retagged++;
                updated++;
            }
        }
        progress.done();

        int removed = 0;
        if (removeOrphans && !orphans.isEmpty()) {
            for (ProjectionState orphan : orphans) {
                firefly.deleteTransaction(orphan.groupId());
                known.remove(orphan.unitId());
                removed++;
            }
            // The accelerator must match reality: replace it with the survivors.
            hub.record(true, new ArrayList<>(known.values()));
        }

        return new Outcome(created, retagged, updated, unchanged, orphans.size(), removed, preserved);
    }

    /** Refuse the whole pass if any unit names an account {@code firefly.yaml} does not map. */
    private void preflight(List<HubUnit> units) {
        List<String> refs = new ArrayList<>();
        units.forEach(u -> {
            refs.add(u.accountRef());
            refs.add(u.toAccountRef());
        });
        List<String> missing = accounts.missing(refs);
        if (!missing.isEmpty()) {
            throw new Refused("firefly.yaml maps no Firefly account for: " + String.join(", ", missing)
                + ". Add them (name and type) and rerun; nothing has been written.");
        }
    }

    private int create(HubUnit unit, String revision, Map<String, ProjectionState> known)
            throws IOException, InterruptedException {
        Projection.Posting posting = Projection.of(unit, revision, accounts);
        FireflyClient.Result result = firefly.post(posting.body());
        return switch (result) {
            case FireflyClient.Result.Created c -> {
                record(unit, c.groupId(), revision, known);
                yield 1;
            }
            case FireflyClient.Result.Duplicate d -> {
                record(unit, d.groupId(), revision, known);
                yield 0;
            }
            case FireflyClient.Result.Failed f -> throw new Refused("Firefly refused " + unit.unitId()
                + " (" + unit.accountRef() + " " + unit.date() + "): " + f.status() + " " + f.message());
        };
    }

    private enum Step { RETAGGED, PRESERVED }

    /**
     * Read, change only the tag and (if you have not touched it) the category, write back.
     * Read-modify-write is a rule: a body built from scratch collapses a split group and destroys
     * the work silently, and {@code group_title} must come back or Firefly refuses a multi-split group.
     */
    @SuppressWarnings("unchecked")
    private Step retag(HubUnit unit, ProjectionState row, String revision, Map<String, ProjectionState> known)
            throws IOException, InterruptedException {
        JsonNode group = firefly.group(row.groupId()).path("data").path("attributes");
        Map<String, Object> body = new LinkedHashMap<>();
        String title = group.path("group_title").asText("");
        if (!title.isEmpty()) {
            body.put("group_title", title);
        }
        List<Map<String, Object>> splits = new ArrayList<>();
        boolean preserved = false;
        for (JsonNode split : group.path("transactions")) {
            Map<String, Object> map = trex.v2.log.Json.mapper().convertValue(split, Map.class);
            String tagCategory = FireflyClient.tagCategory(split);
            String current = split.path("category_name").asText(null);
            boolean untouched = tagCategory != null && tagCategory.equals(current);
            if (untouched) {
                map.put("category_name", unit.category());
                // Firefly resolves category_id before category_name, so a stale id makes the name a
                // silent no-op: the tag moves and the category stays put. Clear it.
                map.remove("category_id");
            } else {
                preserved = true;
            }
            map.put("tags", List.of(Projection.TAG, Projection.CATEGORY_TAG_PREFIX + unit.category()));
            splits.add(map);
        }
        body.put("apply_rules", false);
        body.put("transactions", splits);

        FireflyClient.Result result = firefly.put(row.groupId(), body);
        if (result instanceof FireflyClient.Result.Failed f) {
            throw new Refused("Firefly refused the re-tag of " + unit.unitId()
                + " (group " + row.groupId() + "): " + f.status() + " " + f.message());
        }
        record(unit, row.groupId(), revision, known);
        return preserved ? Step.PRESERVED : Step.RETAGGED;
    }

    private void record(HubUnit unit, String groupId, String revision, Map<String, ProjectionState> known) {
        ProjectionState state = new ProjectionState(unit.unitId(), unit.unitKind(), groupId,
            unit.category(), unit.unitHash(), revision, deriveVersion, Instant.now().toString());
        known.put(unit.unitId(), state);
        hub.record(false, List.of(state));
    }

    private Map<String, ProjectionState> rebuildFromFirefly() throws IOException, InterruptedException {
        Map<String, ProjectionState> out = new TreeMap<>();
        for (FireflyClient.Existing e : firefly.allTransactions()) {
            // stateHash is not recoverable from Firefly, so it is left blank (advisory only).
            out.put(e.externalId(), new ProjectionState(e.externalId(), "UNKNOWN", e.groupId(),
                e.projectedCategory() == null ? "" : e.projectedCategory(), "", "", deriveVersion,
                Instant.now().toString()));
        }
        return out;
    }

    private static String summarise(HubUnit unit) {
        return "%-10s %-10s %10s  %-24s %s".formatted(unit.unitKind(), unit.date(),
            Projection.signedAmount(unit.amount()), unit.accountRef() + " -> "
                + (unit.toAccountRef() == null ? "(merchant)" : unit.toAccountRef()), unit.category());
    }

    private static final class Progress {
        private static final int EVERY = 50;
        private final int total;
        private final PrintStream out;
        private final long startedAt = System.nanoTime();
        private long windowAt = System.nanoTime();
        private int seen;

        Progress(int total, PrintStream out) {
            this.total = total;
            this.out = out;
        }

        void tick() {
            seen++;
            if (seen % EVERY != 0) {
                return;
            }
            long now = System.nanoTime();
            double windowSeconds = (now - windowAt) / 1e9;
            double rate = EVERY / Math.max(windowSeconds, 0.001);
            long remaining = Math.round((total - seen) / Math.max(rate, 0.001));
            windowAt = now;
            out.printf("  %d/%d  %.1f/s  about %d:%02d left%n", seen, total, rate,
                remaining / 60, remaining % 60);
            out.flush();
        }

        void done() {
            if (seen >= EVERY) {
                out.printf("  %d/%d in %.0fs%n", seen, total, (System.nanoTime() - startedAt) / 1e9);
            }
        }
    }
}
