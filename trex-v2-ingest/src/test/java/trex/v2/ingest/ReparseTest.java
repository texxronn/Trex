package trex.v2.ingest;

import org.junit.jupiter.api.Test;
import trex.v2.core.Fact;
import trex.v2.core.Ids;
import trex.v2.core.Observation;
import trex.v2.core.Provenance;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The re-parse diff (V2-PROPOSAL.md §8.2): matched, shifted, new and missing. */
class ReparseTest {

    private static final Instant AT = Instant.parse("2026-09-30T00:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 1);
    private static final String EVIDENCE = "sha256:e1";

    private static Fact previous(String raw, long amount) {
        String id = Ids.contentHash("ing-savings", DAY, amount, raw, 0);
        return new Fact(1, id, "ing-savings", DAY, amount, 0, raw, null, 0, Observation.POSTED,
            "ing-csv", Provenance.BANK, EVIDENCE, "ing-csv/1", AT);
    }

    private static FactDraft draft(String raw, long amount) {
        return new FactDraft("ing-savings", DAY, amount, 0L, raw, null, Observation.POSTED, "ing-csv",
            Provenance.BANK, null, null, null);
    }

    @Test
    void anUnchangedReparseMatchesEveryRow() {
        Fact fact = previous("COLES 1234", -1000);
        List<Reparse.Proposal> proposals = Reparse.diff(List.of(fact), EVIDENCE, List.of(draft("COLES 1234", -1000)));
        assertEquals(1, proposals.size());
        assertEquals(Reparse.Kind.MATCHED, proposals.getFirst().kind());
    }

    @Test
    void aTextFixIsAShiftedRowToSupersede() {
        Fact fact = previous("COLES 1234", -1000);
        List<Reparse.Proposal> proposals = Reparse.diff(List.of(fact), EVIDENCE,
            List.of(draft("COLES 1234 SYDNEY", -1000)));
        Reparse.Proposal shifted = proposals.stream().filter(p -> p.kind() == Reparse.Kind.SHIFTED)
            .findFirst().orElseThrow(() -> new AssertionError(proposals.toString()));
        assertEquals(fact.externalId(), shifted.previousId());
        assertTrue(shifted.detail().contains("supersede"));
    }

    @Test
    void aRowNoLongerReadIsMissingAndRetired() {
        Fact fact = previous("COLES 1234", -1000);
        List<Reparse.Proposal> proposals = Reparse.diff(List.of(fact), EVIDENCE, List.of());
        assertEquals(Reparse.Kind.MISSING, proposals.getFirst().kind());
        assertTrue(proposals.getFirst().detail().contains("retire"));
    }

    @Test
    void aRowPreviouslyDroppedIsNew() {
        Fact fact = previous("COLES 1234", -1000);
        List<Reparse.Proposal> proposals = Reparse.diff(List.of(fact), EVIDENCE,
            List.of(draft("COLES 1234", -1000), draft("BRAND NEW SHOP", -2000)));
        assertTrue(proposals.stream().anyMatch(p -> p.kind() == Reparse.Kind.NEW));
        assertTrue(Reparse.mintIds(List.of(fact), List.of(draft("COLES 1234", -1000))).getFirst()
            .equals(fact.externalId()), "the minted id matches the sequencer's");
    }
}
