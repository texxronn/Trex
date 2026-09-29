package trex.v2.ingest;

import org.junit.jupiter.api.Test;
import trex.v2.core.Observation;
import trex.v2.core.Provenance;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Day-atomic batching (V2-PROPOSAL.md §6.5): never split a calendar day. */
class DayBatcherTest {

    private static FactDraft draft(String account, String date) {
        return new FactDraft(account, LocalDate.parse(date), -100L, 0L, "x", null, Observation.POSTED,
            "ing-csv", Provenance.BANK, null, null, null);
    }

    @Test
    void groupsByAccountAndDayKeepingOrder() {
        List<DayBatcher.Batch> batches = DayBatcher.batch(List.of(
            draft("ing-savings", "2026-09-01"),
            draft("ing-savings", "2026-09-01"),
            draft("ing-savings", "2026-09-02"),
            draft("ing-orange", "2026-09-01")));
        assertEquals(3, batches.size());
        assertEquals(2, batches.get(0).facts().size(), "one day is one batch");
        assertEquals(LocalDate.parse("2026-09-01"), batches.get(0).date());
        assertEquals("ing-orange", batches.get(2).accountRef());
    }
}
