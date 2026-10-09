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

    /**
     * The repair of a receipt collision (V2-REVIEW-FIXES-PLAN.md §4.3): the three rows ING printed
     * under one receipt were recorded as three observations of one natural-key id. Re-read, they mint
     * three content ids; the row that matches the id's latest observation supersedes it, so the
     * chain root survives, and the other two are new facts. Nothing is retired.
     */
    @Test
    void aCollidedReceiptIdIsSupersededByItsMatchingRowAndTheRestAreNew() {
        String collided = Ids.naturalKey("ing-savings", DAY, "171689");
        String[] raws = {"Fee Rebate - Receipt 171689", "Fee - Receipt 171689", "SHOP - Visa Purchase - Receipt 171689"};
        long[] amounts = {192, -192, -6401};
        List<Fact> observed = new java.util.ArrayList<>();
        List<FactDraft> reread = new java.util.ArrayList<>();
        for (int i = 0; i < raws.length; i++) {
            observed.add(new Fact(i + 1, collided, "ing-savings", DAY, amounts[i], 0, raws[i], "171689", 0,
                Observation.POSTED, "ing-csv", Provenance.BANK, EVIDENCE, "ing-csv/1", AT));
            reread.add(new FactDraft("ing-savings", DAY, amounts[i], 0L, raws[i], "171689", Observation.POSTED,
                "ing-csv", Provenance.BANK, null, null, null));
        }
        List<Reparse.Proposal> proposals = Reparse.diff(observed, EVIDENCE, reread);

        List<Reparse.Proposal> shifted = proposals.stream().filter(p -> p.kind() == Reparse.Kind.SHIFTED).toList();
        assertEquals(1, shifted.size(), proposals.toString());
        assertEquals(collided, shifted.getFirst().previousId());
        assertEquals(-6401L, shifted.getFirst().candidate().amount(), "the latest observation keeps the chain");
        assertEquals(2, proposals.stream().filter(p -> p.kind() == Reparse.Kind.NEW).count(), proposals.toString());
        assertTrue(proposals.stream().noneMatch(p -> p.kind() == Reparse.Kind.MISSING), proposals.toString());
    }

    /** A repaired evidence file re-parses to nothing: the superseded id is closed, not missing. */
    @Test
    void aSecondReparseAfterTheRepairProposesNothing() {
        String collided = Ids.naturalKey("ing-savings", DAY, "900077");
        String[] raws = {"Transfer - Receipt No 900077", "Annual fee - Receipt No 900077"};
        long[] amounts = {29900, -29900};
        List<Fact> journal = new java.util.ArrayList<>();
        List<FactDraft> reread = new java.util.ArrayList<>();
        for (int i = 0; i < raws.length; i++) {
            journal.add(new Fact(i + 1, collided, "ing-savings", DAY, amounts[i], 0, raws[i], "900077", 0,
                Observation.POSTED, "ing-csv", Provenance.BANK, EVIDENCE, "ing-csv/1", AT));
            reread.add(new FactDraft("ing-savings", DAY, amounts[i], 0L, raws[i], "900077", Observation.POSTED,
                "ing-csv", Provenance.BANK, null, null, null));
        }
        List<String> ids = Reparse.mintIds(reread);
        for (int i = 0; i < raws.length; i++) {
            journal.add(new Fact(10 + i, ids.get(i), "ing-savings", DAY, amounts[i], 0, raws[i], "900077", 0,
                Observation.POSTED, "ing-csv", Provenance.BANK, EVIDENCE, "ing-csv/1", AT));
        }
        List<trex.v2.core.Decision> decisions = List.of(new trex.v2.core.Decision.Supersede(
            20, collided, ids.get(1), "re-parse", trex.v2.core.Actor.SYSTEM, null, AT));

        List<Reparse.Proposal> proposals = Reparse.diff(journal, Reparse.closedIds(decisions), EVIDENCE, reread);
        assertTrue(proposals.stream().allMatch(p -> p.kind() == Reparse.Kind.MATCHED), proposals.toString());
    }

    @Test
    void aRevokedSupersedeLeavesItsIdOpen() {
        List<trex.v2.core.Decision> decisions = List.of(
            new trex.v2.core.Decision.Supersede(5, "old", "new", "re-parse", trex.v2.core.Actor.SYSTEM, null, AT),
            new trex.v2.core.Decision.Revoke(6, 5, "wrong", trex.v2.core.Actor.USER, "ron", AT),
            new trex.v2.core.Decision.Retire(7, "gone", "re-parse", trex.v2.core.Actor.SYSTEM, null, AT));
        assertEquals(java.util.Set.of("gone"), Reparse.closedIds(decisions));
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
        assertTrue(Reparse.mintIds(List.of(draft("COLES 1234", -1000))).getFirst()
            .equals(fact.externalId()), "the minted id matches the sequencer's");
    }
}
