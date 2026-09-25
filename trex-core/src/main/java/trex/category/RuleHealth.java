package trex.category;

import trex.core.CanonicalEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * What the rule set is actually doing. SPEC §9 (rule-health reports).
 * <p>
 * A rule set grows by accretion: a pattern written for one statement, another for a merchant that
 * has since closed, a third made redundant the day a broader rule landed above it. Nothing in the
 * file says which is which — the file only says what the rules <em>are</em>, and the journal is the
 * only thing that knows what they <em>do</em>. This puts the two together.
 * <p>
 * All of it is derived, so it costs nothing to be wrong and nothing to recompute: change a rule and
 * ask again.
 */
public final class RuleHealth {

    /** How often a merchant must be pinned before it has earned a rule instead. */
    private static final int PROMOTION_THRESHOLD = 3;

    private RuleHealth() {}

    /**
     * One rule's record.
     *
     * @param index     its position, 1-based, as the file and the error messages use
     * @param hits      rows it actually decides — it matched and no earlier rule got there first
     * @param merchants distinct merchant stems among those rows; a rule covering one merchant that
     *                  a broader rule could absorb reads differently from one covering forty
     * @param total     the summed amount of the rows it decides, in cents
     * @param shadowed  rows it matches but never sees, because an earlier rule already claimed them
     * @param shadowedBy the earliest rule doing that, or null
     */
    public record RuleStat(int index, String category, String comment, int hits, int merchants,
                           long total, int shadowed, Integer shadowedBy) {

        /** Fires for nothing at all: either the pattern is wrong or its subject is gone. */
        public boolean dead() {
            return hits == 0 && shadowed == 0;
        }

        /** Fires for nothing because something above it always wins — a different problem. */
        public boolean fullyShadowed() {
            return hits == 0 && shadowed > 0;
        }
    }

    /**
     * One pin's record. A pin naming an id the journal does not contain is the usual stale case:
     * the statement was re-ingested under a changed description, so the id moved.
     */
    public record PinStat(int index, String category, String comment, int hits, int missingIds) {

        public boolean stale() {
            return hits == 0;
        }
    }

    /**
     * A merchant pinned often enough to deserve a rule. Pinning the same shop three times is the
     * file telling you it wants one line instead of three.
     */
    public record Promotion(String stem, String category, int pinned, List<String> externalIds) {}

    public record Report(List<RuleStat> rules, List<PinStat> pins, List<Promotion> promotions,
                         int categorized, int uncategorized, int structural) {}

    public static Report of(Categorizer categorizer, List<CanonicalEvent> latestLines) {
        Set<String> transfers = Transfers.ids(latestLines);
        List<Rule> rules = rulesOf(categorizer, false);
        List<Rule> pins = rulesOf(categorizer, true);

        Map<Integer, Integer> hits = new TreeMap<>();
        Map<Integer, Long> totals = new TreeMap<>();
        Map<Integer, Set<String>> merchants = new TreeMap<>();
        Map<Integer, Integer> pinHits = new TreeMap<>();
        Map<String, List<CanonicalEvent>> pinnedByStem = new LinkedHashMap<>();
        Map<String, String> pinnedCategory = new LinkedHashMap<>();
        Set<String> present = new LinkedHashSet<>();
        int categorized = 0;
        int uncategorized = 0;
        int structural = 0;

        for (CanonicalEvent line : latestLines) {
            present.add(line.externalId());
            Categorized c = categorizer.categorize(line, transfers);
            switch (c.origin()) {
                case STRUCTURAL -> structural++;
                case NONE -> uncategorized++;
                case PIN -> {
                    categorized++;
                    pinHits.merge(c.rule().index(), 1, Integer::sum);
                    String stem = Merchant.stem(line.rawDescription());
                    pinnedByStem.computeIfAbsent(stem, _ -> new ArrayList<>()).add(line);
                    pinnedCategory.putIfAbsent(stem, c.category());
                }
                case RULE -> {
                    categorized++;
                    int index = c.rule().index();
                    hits.merge(index, 1, Integer::sum);
                    totals.merge(index, line.amount(), Long::sum);
                    merchants.computeIfAbsent(index, _ -> new LinkedHashSet<>())
                        .add(Merchant.stem(line.rawDescription()));
                }
            }
        }

        // Shadowing needs a second look: a rule's hits say what it decided, not what it would have
        // decided on its own. A rule can match hundreds of rows and still be dead weight.
        Map<Integer, Integer> shadowed = new TreeMap<>();
        Map<Integer, Integer> shadowedBy = new TreeMap<>();
        for (CanonicalEvent line : latestLines) {
            if (transfers.contains(line.externalId())) {
                continue;
            }
            Categorized c = categorizer.categorize(line, transfers);
            Integer winner = c.origin() == Categorized.Origin.RULE ? c.rule().index() : null;
            for (Rule rule : rules) {
                if (winner != null && rule.index() > winner && rule.matches(line)) {
                    shadowed.merge(rule.index(), 1, Integer::sum);
                    shadowedBy.merge(rule.index(), winner, Math::min);
                }
            }
        }

        List<RuleStat> ruleStats = rules.stream()
            .map(r -> new RuleStat(r.index(), r.category(), r.comment(),
                hits.getOrDefault(r.index(), 0),
                merchants.getOrDefault(r.index(), Set.of()).size(),
                totals.getOrDefault(r.index(), 0L),
                shadowed.getOrDefault(r.index(), 0),
                shadowedBy.get(r.index())))
            .toList();

        List<PinStat> pinStats = pins.stream()
            .map(p -> new PinStat(p.index(), p.category(), p.comment(),
                pinHits.getOrDefault(p.index(), 0), missingIds(p, present)))
            .toList();

        List<Promotion> promotions = pinnedByStem.entrySet().stream()
            .filter(e -> e.getValue().size() >= PROMOTION_THRESHOLD)
            .map(e -> new Promotion(e.getKey(), pinnedCategory.get(e.getKey()), e.getValue().size(),
                e.getValue().stream().map(CanonicalEvent::externalId).toList()))
            .sorted(Comparator.comparingInt(Promotion::pinned).reversed())
            .toList();

        return new Report(ruleStats, pinStats, promotions, categorized, uncategorized, structural);
    }

    /** Ids a pin names that the journal does not contain — usually a re-ingest that moved them. */
    private static int missingIds(Rule pin, Set<String> present) {
        if (!(pin.when() instanceof Condition.ExternalId(Set<String> ids))) {
            return 0;
        }
        return (int) ids.stream().filter(id -> !present.contains(id)).count();
    }

    /**
     * Listing entries is a property of the YAML engine, not of the {@link Categorizer} seam (§5.6):
     * another engine would have nothing to list, and gets an empty report rather than an error.
     */
    private static List<Rule> rulesOf(Categorizer categorizer, boolean pins) {
        if (categorizer instanceof RuleCategorizer engine) {
            return pins ? engine.pins() : engine.rules();
        }
        return List.of();
    }
}
