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

    public record Outcome(int creates, int rekeys, int retags, int updates, int unchanged, int orphans,
                          int removed, int preserved) {

        public boolean empty() {
            return creates == 0 && rekeys == 0 && retags == 0 && updates == 0 && orphans == 0;
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
            } else if (reason(unit, row, revision) != null) {
                moves.add(unit);
            } else {
                unchanged++;
            }
        }
        List<ProjectionState> orphans = known.values().stream()
            .filter(r -> !currentIds.contains(r.unitId())).toList();

        int retags = 0;
        int updates = 0;
        for (HubUnit unit : moves) {
            if (reason(unit, known.get(unit.unitId()), revision) == Reason.RETAG) {
                retags++;
            } else {
                updates++;
            }
        }

        out.printf("%s: %d create, %d retag, %d update, %d unchanged, %d orphan(s)%n",
            mode, creates.size(), retags, updates, unchanged, orphans.size());
        if (mode != Mode.APPLY) {
            creates.forEach(u -> out.println("  CREATE " + summarise(u)));
            moves.forEach(u -> printMove(u, known.get(u.unitId()), revision));
            orphans.forEach(o -> out.println("  ORPHAN " + o.unitId() + " (group " + o.groupId()
                + ", category " + o.category() + ") — not deleted; rerun with --remove-orphans"));
            return new Outcome(creates.size(), 0, retags, updates, unchanged, orphans.size(), 0, 0);
        }

        Tally tally = new Tally();
        Progress progress = new Progress(creates.size() + moves.size(), out);
        for (HubUnit unit : creates) {
            progress.tick();
            tally.add(create(unit, revision, known));
        }
        for (HubUnit unit : moves) {
            progress.tick();
            tally.add(converge(unit, known.get(unit.unitId()).groupId(), revision, known));
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

        return new Outcome(tally.created, 0, tally.retagged, tally.updated,
            unchanged + tally.unchanged, orphans.size(), removed, tally.preserved);
    }

    /** Why a known unit no longer converges: a category move, an unverified hash, or content drift. */
    private enum Reason { RETAG, CHECK, UPDATE }

    /**
     * The reason a known unit moves, or null when it converges. A category move is a retag (the tag
     * moves with it); a blank or old hub hash is checked once without a write; a verified hash whose
     * fingerprint differs from the posting is a content move (F10). A hand-split group is never a
     * content move — its content is yours.
     */
    private Reason reason(HubUnit unit, ProjectionState row, String revision) {
        if (!unit.category().equals(row.category())) {
            return Reason.RETAG;
        }
        if (!Content.verified(row.stateHash())) {
            return Reason.CHECK;
        }
        if (!Content.HAND_SPLIT.equals(row.stateHash())
                && !row.stateHash().equals(expected(unit, revision).fingerprint())) {
            return Reason.UPDATE;
        }
        return null;
    }

    private void printMove(HubUnit unit, ProjectionState row, String revision) {
        switch (reason(unit, row, revision)) {
            case RETAG -> out.println("  RETAG  " + unit.unitId() + "  " + row.category()
                + " -> " + unit.category());
            case CHECK -> out.println("  CHECK  " + unit.unitId() + "  (content not verified)");
            case UPDATE -> out.println("  UPDATE " + unit.unitId() + "  (content moved)");
        }
    }

    private Content expected(HubUnit unit, String revision) {
        return Content.expected(Projection.of(unit, revision, accounts).split());
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

    private Step create(HubUnit unit, String revision, Map<String, ProjectionState> known)
            throws IOException, InterruptedException {
        Projection.Posting posting = Projection.of(unit, revision, accounts);
        FireflyClient.Result result = firefly.post(posting.body());
        return switch (result) {
            case FireflyClient.Result.Created c -> {
                record(unit, c.groupId(), expected(unit, revision).fingerprint(), revision, known);
                yield Step.CREATED;
            }
            // The state was lost but Firefly kept the group: converge on it rather than record blindly,
            // or a category trex has since moved would stay stale (F7).
            case FireflyClient.Result.Duplicate d -> converge(unit, d.groupId(), revision, known);
            case FireflyClient.Result.Failed f -> throw new Refused("Firefly refused " + unit.unitId()
                + " (" + unit.accountRef() + " " + unit.date() + "): " + f.status() + " " + f.message());
        };
    }

    private enum Step { CREATED, RETAGGED, UPDATED, PRESERVED, UNCHANGED, NOT_OURS }

    /**
     * Read the group, change only what we own and what moved, write back only if something did
     * (V2-SPEC.md §11.1). Read-modify-write is a rule: a body built from scratch collapses a split
     * group, and group_title must come back or Firefly refuses a multi-split group. On a single split
     * trex is the authority for content (the bank said so); a group you split by hand keeps its
     * content, and only its category (where untouched) and tags move.
     */
    @SuppressWarnings("unchecked")
    private Step converge(HubUnit unit, String groupId, String revision, Map<String, ProjectionState> known)
            throws IOException, InterruptedException {
        JsonNode found = firefly.groupOrNull(groupId);
        if (found == null) {
            // A missing group is a named recovery, never a raw 404 (D9/R2) and never a silent recreate.
            throw new Refused("group " + groupId + " for unit " + unit.unitId()
                + " is missing in Firefly — run --validate; to recreate it run --verify then --apply");
        }
        JsonNode group = found.path("data").path("attributes");
        JsonNode splits = group.path("transactions");
        if (splits.isEmpty() || !FireflyClient.isOurs(splits.get(0))) {
            out.println("  NOT OURS " + unit.unitId() + " (group " + groupId + ") — the trex tag is gone; left alone");
            return Step.NOT_OURS;
        }
        Map<String, Object> want = Projection.of(unit, revision, accounts).split();
        Content expected = Content.expected(want);
        boolean single = splits.size() == 1;
        boolean contentMoves = single && !expected.equals(Content.observed(splits.get(0), accounts.ids()));
        if (!single) {
            long sum = 0;
            for (JsonNode s : splits) {
                sum += Content.cents(s.path("amount").asText("0"));
            }
            if (sum != Content.cents(expected.amount())) {
                out.println("  HAND-SPLIT " + unit.unitId() + " (group " + groupId + ") — the bank now says "
                    + expected.amount() + "; your splits total " + Projection.amount(sum)
                    + " and are left as you made them");
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        String title = group.path("group_title").asText("");
        if (!title.isEmpty()) {
            body.put("group_title", title);
        }
        List<Map<String, Object>> splitsOut = new ArrayList<>();
        boolean preserved = false;
        boolean changed = contentMoves;
        for (JsonNode split : splits) {
            Map<String, Object> map = trex.v2.log.Json.mapper().convertValue(split, Map.class);
            String tagCategory = FireflyClient.tagCategory(split);
            String current = split.path("category_name").asText(null);
            if (tagCategory != null && tagCategory.equals(current)) {
                if (!unit.category().equals(current)) {
                    map.put("category_name", unit.category());
                    // Firefly resolves category_id before category_name: a stale id makes the name a
                    // silent no-op — the tag moves and the category stays put.
                    map.remove("category_id");
                    changed = true;
                }
            } else {
                preserved = true;
            }
            List<String> tags = Projection.tags(split.path("tags"), unit.category());
            if (!tags.equals(trex.v2.log.Json.mapper().convertValue(split.path("tags"), List.class))) {
                changed = true;
            }
            map.put("tags", tags);
            if (splitsOut.isEmpty()) {
                // Our first line rides along with a write another change caused: notes are not part of
                // the fingerprint, so refreshing them must never force a write by itself (a hand-split
                // group's content is never rewritten, and unchanged rows keep a stale rules= by design).
                String notes = Projection.notes(split.path("notes").asText(""), unit, revision);
                if (!notes.equals(split.path("notes").asText(""))) {
                    map.put("notes", notes);
                }
            }
            if (contentMoves) {
                applyContent(map, want);
            }
            splitsOut.add(map);
        }
        String fingerprint = single ? expected.fingerprint() : Content.HAND_SPLIT;
        if (!changed) {
            record(unit, groupId, fingerprint, revision, known);
            return Step.UNCHANGED;
        }
        body.put("apply_rules", false);
        body.put("transactions", splitsOut);
        FireflyClient.Result result = firefly.put(groupId, body);
        if (result instanceof FireflyClient.Result.Failed f) {
            throw new Refused("Firefly refused the update of " + unit.unitId()
                + " (group " + groupId + "): " + f.status() + " " + f.message());
        }
        record(unit, groupId, fingerprint, revision, known);
        return contentMoves ? Step.UPDATED : preserved ? Step.PRESERVED : Step.RETAGGED;
    }

    /**
     * The content we own, from the posting. A side moved by name drops its id, and a side moved by
     * id drops its name: Firefly resolves the id first (measured for categories; Stage 0 A4 for
     * accounts), so a stale id would win silently.
     */
    private static void applyContent(Map<String, Object> map, Map<String, Object> want) {
        for (String key : List.of("type", "date", "amount", "currency_code", "description", "external_id")) {
            map.put(key, want.get(key));
        }
        map.remove("currency_id");
        for (String side : List.of("source", "destination")) {
            map.remove(side + "_id");
            map.remove(side + "_name");
            if (want.containsKey(side + "_id")) {
                map.put(side + "_id", want.get(side + "_id"));
            }
            if (want.containsKey(side + "_name")) {
                map.put(side + "_name", want.get(side + "_name"));
            }
        }
    }

    private void record(HubUnit unit, String groupId, String fingerprint, String revision,
                        Map<String, ProjectionState> known) {
        ProjectionState state = new ProjectionState(unit.unitId(), unit.unitKind(), groupId,
            unit.category(), fingerprint, revision, deriveVersion, Instant.now().toString());
        known.put(unit.unitId(), state);
        hub.record(false, List.of(state));
    }

    private Map<String, ProjectionState> rebuildFromFirefly() throws IOException, InterruptedException {
        Map<String, ProjectionState> out = new TreeMap<>();
        for (FireflyClient.Existing e : firefly.allTransactions()) {
            JsonNode splits = e.group().path("attributes").path("transactions");
            String fingerprint = splits.size() == 1
                ? Content.observed(splits.get(0), accounts.ids()).fingerprint() : Content.HAND_SPLIT;
            String kind = e.externalId().startsWith("TRF-") ? "TRANSFER" : "EXTERNAL";
            out.put(e.externalId(), new ProjectionState(e.externalId(), kind, e.groupId(),
                e.projectedCategory() == null ? "" : e.projectedCategory(), fingerprint, "", deriveVersion,
                Instant.now().toString()));
        }
        return out;
    }

    private static String summarise(HubUnit unit) {
        return "%-10s %-10s %10s  %-24s %s".formatted(unit.unitKind(), unit.date(),
            Projection.signedAmount(unit.amount()), unit.accountRef() + " -> "
                + (unit.toAccountRef() == null ? "(merchant)" : unit.toAccountRef()), unit.category());
    }

    /** Counts one apply's steps. {@code NOT_OURS} is printed inside {@link #converge}, never counted. */
    private static final class Tally {
        private int created;
        private int retagged;
        private int updated;
        private int preserved;
        private int unchanged;

        void add(Step step) {
            switch (step) {
                case CREATED -> created++;
                case RETAGGED -> retagged++;
                case UPDATED -> updated++;
                case PRESERVED -> preserved++;
                case UNCHANGED -> unchanged++;
                case NOT_OURS -> { }
            }
        }
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
