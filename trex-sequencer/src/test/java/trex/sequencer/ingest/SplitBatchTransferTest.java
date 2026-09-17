package trex.sequencer.ingest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trex.core.CandidateResult.DroppedDuplicate;
import trex.core.CandidateResult.Held;
import trex.core.CandidateResult.Resolved;
import trex.core.CanonicalEvent;
import trex.core.EventState;
import trex.core.Ids;
import trex.core.TypeHint;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static trex.sequencer.ingest.Harness.c;
import static trex.sequencer.ingest.Harness.id;

/** SPEC §7 test 5. */
class SplitBatchTransferTest {

    @TempDir
    Path dir;

    @Test
    void fuzzyLegsInSeparateBatchesResolveToOneTransfer() {
        try (Harness h = new Harness(dir.resolve("j.jsonl"))) {
            var out = h.submit(c("row-2", "ing-savings", "2026-06-28", -50000, "Fast Transfer to CBA", 1000, null));
            assertInstanceOf(Held.class, out.results().getFirst());
            String legA = id(out.results().getFirst());

            h.restart();
            var in = h.submit(c("row-9", "cba-everyday", "2026-06-29", 50000, "Transfer from ING", 60000, null));
            assertInstanceOf(Resolved.class, in.results().getFirst());
            String legB = id(in.results().getFirst());

            String trf = Ids.transferId(legA, legB);
            List<CanonicalEvent> transfers = h.lines().stream().filter(l -> l.typeHint() == TypeHint.TRANSFER).toList();
            assertEquals(1, transfers.size());
            assertEquals(trf, transfers.getFirst().externalId());
            assertEquals(List.of(legA, legB), transfers.getFirst().legIds());
            assertEquals(EventState.MATCHED, h.latest(legA).state());
            assertEquals(EventState.MATCHED, h.latest(legB).state());

            var again = h.submit(c("row-9", "cba-everyday", "2026-06-29", 50000, "Transfer from ING", 60000, null));
            assertInstanceOf(DroppedDuplicate.class, again.results().getFirst());
            assertEquals(1, h.lines().stream().filter(l -> l.typeHint() == TypeHint.TRANSFER).count());
        }
    }

    @Test
    void sharedReceiptLegsInSeparateBatchesResolveToReceiptTransferId() {
        try (Harness h = new Harness(dir.resolve("j.jsonl"))) {
            h.submit(c("row-2", "ing-savings", "2026-06-10", -1000, "To my account Receipt 555", 500, "555"));
            var in = h.submit(c("row-3", "ing-orange", "2026-06-10", 1000, "From my account Receipt 555", 900, "555"));
            assertInstanceOf(Resolved.class, in.results().getFirst());
            CanonicalEvent transfer = h.latest("TRF-555");
            assertEquals(TypeHint.TRANSFER, transfer.typeHint());
            assertEquals("555", transfer.receipt());
            assertEquals(trex.core.Confidence.EXACT, transfer.confidence());
        }
    }
}
