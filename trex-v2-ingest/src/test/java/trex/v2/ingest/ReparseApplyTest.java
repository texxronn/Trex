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

/** The re-parse apply path (V2-PROPOSAL.md §8.2): new facts posted, SUPERSEDE/RETIRE decisions written. */
class ReparseApplyTest {

    private static final Instant AT = Instant.parse("2026-09-30T00:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 1);
    private static final String EVIDENCE = "sha256:e1";

    private static Fact previous(long n, long amount, String raw, int occ) {
        String id = Ids.contentHash("ing-savings", DAY, amount, raw, occ);
        return new Fact(n, id, "ing-savings", DAY, amount, 0, raw, null, occ, Observation.POSTED,
            "ing-csv", Provenance.BANK, EVIDENCE, "ing-csv/1", AT);
    }

    private static FactDraft draft(long amount, String raw) {
        return new FactDraft("ing-savings", DAY, amount, 0L, raw, null, Observation.POSTED, "ing-csv",
            Provenance.BANK, null, null, null);
    }

    @Test
    void postsNewFactsAndTheSupersedeAndRetireDecisions() throws Exception {
        try (FakeSequencer sequencer = new FakeSequencer("Appended")) {
            Fact changed = previous(1, -1000, "COLES 1234", 0);
            Fact dropped = previous(2, -2000, "OTHER SHOP", 1);

            List<Reparse.Proposal> proposals = Reparse.diff(List.of(changed, dropped), EVIDENCE,
                List.of(draft(-1000, "COLES 1234 SYDNEY")));

            Reparse.ApplyResult result = Reparse.apply(new IngestClient(sequencer.url()), "ing-csv/2",
                EVIDENCE, proposals);

            assertEquals(1, result.facts(), "the shifted row is posted as a new fact");
            assertEquals(2, result.decisions(), "one SUPERSEDE and one RETIRE");
            assertEquals(1, sequencer.factsSeen);
            assertEquals(1, sequencer.decisionCalls);
            assertTrue(sequencer.decisions.stream().anyMatch(d -> "SUPERSEDE".equals(d.get("action"))
                && changed.externalId().equals(d.get("fromId"))), sequencer.decisions.toString());
            assertTrue(sequencer.decisions.stream().anyMatch(d -> "RETIRE".equals(d.get("action"))
                && dropped.externalId().equals(d.get("externalId"))));
        }
    }
}
