package trex.ingest;

import trex.core.Candidate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Day-atomic batching (SPEC §4). Rows are grouped by date in order of first appearance (file
 * order within a day is kept), and whole day-groups are packed into calls up to a soft row target.
 * A day larger than the target is sent as its own call: a day is the atom.
 */
public final class DayBatcher {

    private DayBatcher() {}

    public static List<List<Candidate>> split(List<Candidate> rows, int targetRows) {
        if (targetRows <= 0) {
            throw new IllegalArgumentException("targetRows must be positive");
        }
        Map<LocalDate, List<Candidate>> days = new LinkedHashMap<>();
        rows.forEach(c -> days.computeIfAbsent(c.date(), d -> new ArrayList<>()).add(c));

        List<List<Candidate>> calls = new ArrayList<>();
        List<Candidate> current = new ArrayList<>();
        for (List<Candidate> day : days.values()) {
            if (!current.isEmpty() && current.size() + day.size() > targetRows) {
                calls.add(List.copyOf(current));
                current.clear();
            }
            current.addAll(day);
        }
        if (!current.isEmpty()) {
            calls.add(List.copyOf(current));
        }
        return calls;
    }
}
