package trex.v2.ingest;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Day-atomic batching (V2-PROPOSAL.md §6.5, §12.1): {@code occ} is scoped to {@code (account, day)},
 * so a calendar day is never split across a call. Batches keep the file's order.
 */
public final class DayBatcher {

    public record Batch(String accountRef, LocalDate date, List<FactDraft> facts) {}

    private DayBatcher() {}

    public static List<Batch> batch(List<FactDraft> drafts) {
        Map<String, List<FactDraft>> byDay = new LinkedHashMap<>();
        Map<String, String[]> keys = new LinkedHashMap<>();
        for (FactDraft d : drafts) {
            String key = d.accountRef() + '\u0000' + d.date();
            byDay.computeIfAbsent(key, k -> new ArrayList<>()).add(d);
            keys.putIfAbsent(key, new String[] { d.accountRef(), d.date().toString() });
        }
        List<Batch> out = new ArrayList<>();
        byDay.forEach((key, facts) -> {
            String[] parts = keys.get(key);
            out.add(new Batch(parts[0], LocalDate.parse(parts[1]), List.copyOf(facts)));
        });
        return out;
    }
}
