package trex.category;

import trex.core.CanonicalEvent;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Categorise a whole journal and report what happened. SPEC §5.6: this is the tuning loop —
 * rules are written against the uncategorised list, not guessed at.
 * <p>
 * Pure: takes lines, returns text. The caller decides where the lines come from and where the
 * report goes.
 */
public final class DryRun {

    private static final int SHOWN = 20;

    private DryRun() {}

    public static String report(Categorizer categorizer, List<CanonicalEvent> latestLines) {
        Set<String> transfers = Transfers.ids(latestLines);
        Map<String, Integer> counts = new TreeMap<>();
        Map<String, Integer> uncategorised = new LinkedHashMap<>();
        int pinned = 0;

        for (CanonicalEvent line : latestLines) {
            Categorized c = categorizer.categorize(line, transfers);
            counts.merge(c.category(), 1, Integer::sum);
            if (c.origin() == Categorized.Origin.PIN) {
                pinned++;
            }
            if (c.origin() == Categorized.Origin.NONE) {
                uncategorised.merge(line.rawDescription(), 1, Integer::sum);
            }
        }

        StringBuilder out = new StringBuilder();
        out.append("transactions   ").append(latestLines.size()).append('\n');
        out.append("categories     ").append(String.join(", ", categorizer.declared())).append('\n');
        out.append("pinned         ").append(pinned).append('\n');
        counts.forEach((category, n) -> out.append("  %-16s %d%n".formatted(category, n)));

        if (!uncategorised.isEmpty()) {
            out.append("top uncategorised descriptions (write rules for these first):\n");
            uncategorised.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                    .thenComparing(Map.Entry.comparingByKey()))
                .limit(SHOWN)
                .forEach(e -> out.append("  %4d  %s%n".formatted(e.getValue(), e.getKey())));
            if (uncategorised.size() > SHOWN) {
                out.append("  ... %d more distinct descriptions%n".formatted(uncategorised.size() - SHOWN));
            }
        }
        return out.toString();
    }

    /** Distinct raw descriptions that no rule matched, most frequent first. */
    public static List<String> uncategorisedDescriptions(Categorizer categorizer, List<CanonicalEvent> latestLines) {
        Set<String> transfers = Transfers.ids(latestLines);
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (CanonicalEvent line : latestLines) {
            if (categorizer.categorize(line, transfers).origin() == Categorized.Origin.NONE) {
                counts.merge(line.rawDescription(), 1, Integer::sum);
            }
        }
        return counts.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                .thenComparing(Map.Entry.comparingByKey()))
            .map(Map.Entry::getKey)
            .toList();
    }

    /** Category totals in cents, for a footer or a report. TRANSFER lines are the caller's problem. */
    public static Map<String, Long> totals(Categorizer categorizer, List<CanonicalEvent> latestLines) {
        Set<String> transfers = Transfers.ids(latestLines);
        Map<String, Long> totals = new TreeMap<>(Comparator.naturalOrder());
        for (CanonicalEvent line : latestLines) {
            totals.merge(categorizer.categorize(line, transfers).category(), line.amount(), Long::sum);
        }
        return totals;
    }
}
