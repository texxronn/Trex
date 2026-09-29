package trex.v2.ingest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trex.v2.core.Fact;
import trex.v2.core.Observation;
import trex.v2.core.Provenance;
import trex.v2.ingest.ing.IngCsv;
import trex.v2.log.EvidenceStore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** §15.9: re-parsing stored evidence with the same parser reproduces the same facts. */
class EvidenceReparseTest {

    private static final String SAMPLE = """
        Date,Description,Credit,Debit,Balance
        01/07/2026,"COFFEE CART, SYDNEY",,-4.50,1745.50
        01/07/2026,"COFFEE CART, SYDNEY",,-4.50,1741.00
        02/07/2026,Salary Deposit - Receipt No 998877,2500.00,,4061.00
        """;

    @Test
    void reparsingStoredEvidenceReproducesTheFactsAndMatchesThem(@TempDir Path dir) {
        EvidenceStore store = new EvidenceStore(dir);
        String evidenceId = store.put(SAMPLE.getBytes(StandardCharsets.UTF_8));
        IngCsv adapter = new IngCsv();

        List<FactDraft> first = adapter.parse(SAMPLE.getBytes(StandardCharsets.UTF_8), "f.csv", "ing-savings")
            .candidates();
        List<FactDraft> again = adapter.parse(store.get(evidenceId), "f.csv", "ing-savings").candidates();
        assertEquals(first, again, "same parser, same stored bytes, same drafts");

        // The facts the sequencer would have written, built from the minted ids and occs.
        List<Reparse.Minted> minted = Reparse.mint(List.of(), first);
        List<Fact> facts = new ArrayList<>();
        for (int i = 0; i < first.size(); i++) {
            FactDraft d = first.get(i);
            facts.add(new Fact(i + 1, minted.get(i).id(), d.accountRef(), d.date(), d.amount(), d.balance(),
                d.rawDescription(), d.receipt(), minted.get(i).occ(), Observation.POSTED, d.sourceType(),
                Provenance.BANK, evidenceId, adapter.parser(), Instant.parse("2026-09-30T00:00:00Z")));
        }

        List<Reparse.Proposal> proposals = Reparse.diff(facts, evidenceId, again);
        assertTrue(proposals.stream().allMatch(p -> p.kind() == Reparse.Kind.MATCHED),
            proposals.toString());
        assertEquals(3, proposals.size());
    }
}
