package trex.v2.core.workbook;

import trex.v2.core.MerchantStem;
import trex.v2.core.config.DeriveConfig;
import trex.v2.core.config.Rule;
import trex.v2.core.config.RuleSet;
import trex.v2.core.config.RuleSubject;
import trex.v2.core.derive.CategoryRow;
import trex.v2.core.derive.CurrentFact;
import trex.v2.core.derive.Derivation;
import trex.v2.core.derive.PinRow;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * What the rule set is actually doing (V2-PROPOSAL.md §10.4): lint, coverage and suggestions,
 * computed from the current derivation. Pure and disposable — change a rule and ask again.
 *
 * <p>A rule set grows by accretion: a pattern for one statement, another for a merchant that has
 * closed, a third made redundant the day a broader rule landed above it. The file says what the
 * rules <em>are</em>; the derivation is the only thing that knows what they <em>do</em>. This puts
 * the two together. Suggestions are proposals; nothing here writes the log.
 */
public final class Workbook {

    /** How often a merchant must be pinned before it has earned a rule instead. */
    public static final int PROMOTION_THRESHOLD = 3;

    public record RuleStat(int index, String category, String comment, int hits, int merchants,
                           long total, int shadowed, Integer shadowedBy) {

        public boolean neverFires() {
            return hits == 0 && shadowed == 0;
        }

        public boolean fullyShadowed() {
            return hits == 0 && shadowed > 0;
        }
    }

    public record PinStat(String externalId, String category, long decisionN, boolean redundant, String ruleId) {}

    public enum FindingKind { SHADOWED, NEVER_FIRES, CATASTROPHIC_REGEX, ORPHAN_PIN, REDUNDANT_PIN }

    public record Finding(FindingKind kind, String subject, String detail) {}

    /** Where a suggested rule comes from: clustered pins, or a cluster of uncategorised rows. */
    public enum SuggestionSource { PIN, UNCATEGORISED }

    /**
     * A proposed rule with its measured effect on history (V2-PROPOSAL.md §10.4): the merchant stem,
     * the proposed regex, and how many current rows it matches, how many are currently
     * uncategorised (the gain) and how many already have a category (the risk). A proposal only.
     */
    public record Suggestion(SuggestionSource source, String stem, String category, int occurrences,
                             long total, String proposedRegex, int regexMatches, int regexNew,
                             int regexConflicts, java.time.LocalDate firstSeen, java.time.LocalDate lastSeen,
                             List<String> accounts, List<String> sampleIds) {}

    public record TrendPoint(String period, int rule, int pin, int uncategorized) {}

    public record Coverage(int total, int categorized, int pinned, int uncategorized, int structural,
                           Map<String, Long> amountByOrigin, List<TrendPoint> trend) {}

    public record Report(List<RuleStat> rules, List<PinStat> pins, List<Finding> findings,
                         List<Suggestion> suggestions, Coverage coverage) {}

    private record FactInfo(CurrentFact fact, String origin, String category, String stem) {}

    private Workbook() {}

    public static Report of(DeriveConfig config, Derivation derivation) {
        RuleSet rules = config.categories();
        Map<String, CurrentFact> current = derivation.currentById();
        Map<String, CategoryRow> categories = new TreeMap<>();
        for (CategoryRow c : derivation.categories()) {
            categories.put(c.externalId(), c);
        }

        Map<Integer, Integer> hits = new TreeMap<>();
        Map<Integer, Long> totals = new TreeMap<>();
        Map<Integer, java.util.Set<String>> merchants = new TreeMap<>();
        Map<String, Integer> pinHits = new TreeMap<>();
        Map<String, Long> amountByOrigin = new LinkedHashMap<>();
        Map<String, int[]> trend = new TreeMap<>();   // period -> [rule, pin, uncategorized]
        int categorized = 0;
        int pinned = 0;
        int uncategorized = 0;
        int structural = 0;

        for (CurrentFact fact : derivation.current()) {
            String origin = categories.get(fact.externalId()) == null
                ? "NONE" : categories.get(fact.externalId()).origin().name();
            amountByOrigin.merge(origin, fact.fact().amount(), Long::sum);
            String period = YearMonth.from(fact.fact().date()).toString();
            int[] counts = trend.computeIfAbsent(period, k -> new int[3]);

            switch (origin) {
                case "STRUCTURAL" -> structural++;
                case "RULE" -> {
                    categorized++;
                    counts[0]++;
                    int index = ruleIndex(categories.get(fact.externalId()).ruleId());
                    if (index > 0) {
                        hits.merge(index, 1, Integer::sum);
                        totals.merge(index, fact.fact().amount(), Long::sum);
                        merchants.computeIfAbsent(index, k -> new TreeSet<>())
                            .add(MerchantStem.stem(fact.fact().rawDescription()));
                    }
                }
                case "PIN" -> {
                    categorized++;
                    pinned++;
                    counts[1]++;
                    pinHits.merge(fact.externalId(), 1, Integer::sum);
                }
                default -> {
                    uncategorized++;
                    counts[2]++;
                }
            }
        }

        // Shadowing: a rule can match many rows and still be dead weight because something above wins.
        Map<Integer, Integer> shadowed = new TreeMap<>();
        Map<Integer, Integer> shadowedBy = new TreeMap<>();
        for (CurrentFact fact : derivation.current()) {
            CategoryRow row = categories.get(fact.externalId());
            if (row == null || row.origin() != trex.v2.core.derive.CategoryOrigin.RULE) {
                continue;
            }
            int winner = ruleIndex(row.ruleId());
            RuleSubject subject = RuleSubject.of(fact.externalId(), fact.fact().accountRef(),
                fact.fact().rawDescription(), fact.fact().amount());
            for (Rule rule : rules.rules()) {
                if (rule.index() > winner && rule.matches(subject)) {
                    shadowed.merge(rule.index(), 1, Integer::sum);
                    shadowedBy.merge(rule.index(), winner, Math::min);
                }
            }
        }

        List<RuleStat> ruleStats = new ArrayList<>();
        List<Finding> findings = new ArrayList<>();
        for (Rule rule : rules.rules()) {
            int hitCount = hits.getOrDefault(rule.index(), 0);
            RuleStat stat = new RuleStat(rule.index(), rule.category(), rule.comment(), hitCount,
                merchants.getOrDefault(rule.index(), java.util.Set.of()).size(),
                totals.getOrDefault(rule.index(), 0L),
                shadowed.getOrDefault(rule.index(), 0), shadowedBy.get(rule.index()));
            ruleStats.add(stat);
            if (stat.fullyShadowed()) {
                findings.add(new Finding(FindingKind.SHADOWED, "rule #" + rule.index(),
                    "fully shadowed by rule #" + stat.shadowedBy()));
            } else if (stat.neverFires()) {
                findings.add(new Finding(FindingKind.NEVER_FIRES, "rule #" + rule.index(),
                    "matched no current transaction"));
            }
            for (String regex : CatastrophicRegex.sources(rule.when())) {
                if (CatastrophicRegex.risky(regex)) {
                    findings.add(new Finding(FindingKind.CATASTROPHIC_REGEX, "rule #" + rule.index(),
                        "regex may backtrack catastrophically: " + regex));
                }
            }
        }

        // Pins: redundant (a rule already yields the same answer) and orphaned (the id is gone).
        List<PinStat> pinStats = new ArrayList<>();
        for (PinRow pin : derivation.pins()) {
            CurrentFact fact = current.get(pin.externalId());
            String ruleId = null;
            boolean redundant = false;
            if (fact != null) {
                Optional<Rule> rule = rules.firstMatch(RuleSubject.of(fact.externalId(),
                    fact.fact().accountRef(), fact.fact().rawDescription(), fact.fact().amount()));
                redundant = rule.isPresent() && rule.get().category().equals(pin.category());
                ruleId = rule.map(Rule::where).orElse(null);
            }
            pinStats.add(new PinStat(pin.externalId(), pin.category(), pin.decisionN(), redundant, ruleId));
            if (redundant) {
                findings.add(new Finding(FindingKind.REDUNDANT_PIN, pin.externalId(),
                    "rule " + ruleId + " already assigns " + pin.category() + " — consider UNPIN"));
            }
        }
        for (var ineffective : derivation.ineffective()) {
            if ("PIN".equals(ineffective.action())) {
                findings.add(new Finding(FindingKind.ORPHAN_PIN, "decision n=" + ineffective.decisionN(),
                    ineffective.reason()));
            }
        }

        // Suggestions: pin clusters and uncategorised clusters, each with a proposed regex and its
        // measured effect on history (V2-PROPOSAL.md §10.4). Proposals only; nothing decides here.
        List<FactInfo> infos = new ArrayList<>();
        for (CurrentFact fact : derivation.current()) {
            CategoryRow row = categories.get(fact.externalId());
            String origin = row == null ? "NONE" : row.origin().name();
            infos.add(new FactInfo(fact, origin, row == null ? null : row.category(),
                MerchantStem.stem(fact.fact().rawDescription())));
        }
        Map<String, List<FactInfo>> uncategorisedByStem = new LinkedHashMap<>();
        for (FactInfo info : infos) {
            if (info.origin().equals("NONE")) {
                uncategorisedByStem.computeIfAbsent(info.stem(), k -> new ArrayList<>()).add(info);
            }
        }
        Map<String, FactInfo> infoById = new LinkedHashMap<>();
        for (FactInfo info : infos) {
            infoById.put(info.fact().externalId(), info);
        }
        Map<String, List<FactInfo>> pinnedByStemCategory = new LinkedHashMap<>();
        for (PinRow pin : derivation.pins()) {
            FactInfo info = infoById.get(pin.externalId());
            if (info != null) {
                pinnedByStemCategory.computeIfAbsent(info.stem() + '\u0000' + pin.category(),
                    k -> new ArrayList<>()).add(info);
            }
        }

        List<Suggestion> suggestions = new ArrayList<>();
        uncategorisedByStem.forEach((stem, members) ->
            suggestions.add(suggestion(SuggestionSource.UNCATEGORISED, stem, null, members, infos)));
        pinnedByStemCategory.forEach((key, members) -> {
            if (members.size() >= PROMOTION_THRESHOLD) {
                int separator = key.indexOf('\u0000');
                suggestions.add(suggestion(SuggestionSource.PIN, key.substring(0, separator),
                    key.substring(separator + 1), members, infos));
            }
        });
        suggestions.sort(Comparator.comparing((Suggestion s) -> s.source().ordinal())
            .thenComparing(Comparator.comparingInt(Suggestion::occurrences).reversed())
            .thenComparing(Suggestion::stem));

        List<TrendPoint> trendPoints = trend.entrySet().stream()
            .map(e -> new TrendPoint(e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2]))
            .toList();

        Coverage coverage = new Coverage(derivation.current().size(), categorized, pinned, uncategorized,
            structural, amountByOrigin, trendPoints);

        findings.sort(Comparator.comparing((Finding f) -> f.kind().name()).thenComparing(Finding::subject));
        return new Report(ruleStats, pinStats, findings, suggestions, coverage);
    }

    private static Suggestion suggestion(SuggestionSource source, String stem, String category,
                                         List<FactInfo> members, List<FactInfo> all) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
            java.util.regex.Pattern.quote(stem),
            java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE);
        int matches = 0;
        int isNew = 0;
        int conflicts = 0;
        for (FactInfo info : all) {
            if (pattern.matcher(info.fact().fact().rawDescription()).find()) {
                matches++;
                if (info.origin().equals("NONE")) {
                    isNew++;
                } else {
                    conflicts++;
                }
            }
        }
        long total = members.stream().mapToLong(m -> m.fact().fact().amount()).sum();
        return new Suggestion(source, stem, category, members.size(), total,
            "(?i)" + java.util.regex.Pattern.quote(stem), matches, isNew, conflicts,
            members.stream().map(m -> m.fact().fact().date()).min(Comparator.naturalOrder()).orElse(null),
            members.stream().map(m -> m.fact().fact().date()).max(Comparator.naturalOrder()).orElse(null),
            members.stream().map(m -> m.fact().fact().accountRef()).distinct().sorted().toList(),
            members.stream().map(m -> m.fact().externalId()).limit(5).toList());
    }

    /** "rule #7" -> 7; anything else -> 0. */
    private static int ruleIndex(String ruleId) {
        if (ruleId != null && ruleId.startsWith("rule #")) {
            try {
                return Integer.parseInt(ruleId.substring("rule #".length()));
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }
}
