package trex.ingress.ing;

import org.junit.jupiter.api.Test;
import trex.core.Candidate;
import trex.core.Provenance;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DayBatcherTest {

    private static Candidate row(int line, String date) {
        return new Candidate("row-" + line, "acc", LocalDate.parse(date), -100, "x", 0, null, null, null, "ing-csv", Provenance.BANK);
    }

    private static List<String> refs(List<Candidate> call) {
        return call.stream().map(Candidate::candidateRef).toList();
    }

    @Test
    void packsWholeDaysAndNeverSplitsADay() {
        List<Candidate> rows = new ArrayList<>();
        rows.add(row(2, "2026-06-01"));
        rows.add(row(3, "2026-06-01"));
        rows.add(row(4, "2026-06-02"));
        rows.add(row(5, "2026-06-03"));
        rows.add(row(6, "2026-06-03"));
        rows.add(row(7, "2026-06-03"));
        rows.add(row(8, "2026-06-04"));

        List<List<Candidate>> calls = DayBatcher.split(rows, 3);
        assertEquals(List.of(List.of("row-2", "row-3", "row-4"), List.of("row-5", "row-6", "row-7"), List.of("row-8")),
            calls.stream().map(DayBatcherTest::refs).toList());

        List<List<Candidate>> tiny = DayBatcher.split(rows, 2);
        assertEquals(List.of(List.of("row-2", "row-3"), List.of("row-4"), List.of("row-5", "row-6", "row-7"), List.of("row-8")),
            tiny.stream().map(DayBatcherTest::refs).toList());

        assertEquals(List.of(rows), DayBatcher.split(rows, Integer.MAX_VALUE));
    }
}
