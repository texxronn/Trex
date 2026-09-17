package trex.sequencer.ingest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trex.core.CanonicalEvent;
import trex.core.Confidence;
import trex.core.EventState;
import trex.core.Provenance;
import trex.core.TypeHint;
import trex.core.state.Projection;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static trex.sequencer.ingest.Harness.c;
import static trex.sequencer.ingest.Harness.id;

/** SPEC §7 test 11 plus TRANSFER line fields (§3.4). */
class TransferProjectionTest {

    @TempDir
    Path dir;

    @Test
    void splitBatchTransferIsFourLinesAndProjectsOnlyTheTransfer() {
        try (Harness h = new Harness(dir.resolve("j.jsonl"))) {
            String a = id(h.submit(c("r1", "ing-savings", "2026-06-28", -50000, "Fast Transfer to CBA", 1000, null)).results().getFirst());
            String b = id(h.submit(c("r2", "cba-everyday", "2026-06-29", 50000, "Transfer from ING", 60000, null)).results().getFirst());

            List<CanonicalEvent> lines = h.lines();
            assertEquals(4, lines.size());
            assertEquals(List.of(a, b, a), lines.subList(0, 3).stream().map(CanonicalEvent::externalId).toList());
            assertEquals(List.of(EventState.HELD, EventState.MATCHED, EventState.MATCHED, EventState.MATCHED),
                lines.stream().map(CanonicalEvent::state).toList());
            assertEquals(List.of(1L, 2L, 3L, 4L), lines.stream().map(CanonicalEvent::n).toList());
            assertNull(lines.get(2).transferKey(), "re-appended leg keeps its original transferKey");

            List<CanonicalEvent> projectable = Projection.projectableUnits(h.sequencer().view().latestLines());
            assertEquals(1, projectable.size());
            assertEquals(TypeHint.TRANSFER, projectable.getFirst().typeHint());
        }
    }

    @Test
    void sameBatchTransferIsThreeLinesWithFieldsFromTheFromLeg() {
        try (Harness h = new Harness(dir.resolve("j.jsonl"))) {
            var r = h.submit(
                c("r1", "cba-everyday", "2026-06-29", 50000, "Transfer from ING", 60000, null),
                c("r2", "ing-savings", "2026-06-28", -50000, "Fast Transfer  to CBA", 1000, null));
            String in = id(r.results().get(0));
            String out = id(r.results().get(1));

            List<CanonicalEvent> lines = h.lines();
            assertEquals(3, lines.size());
            CanonicalEvent transfer = lines.get(2);
            assertEquals(List.of(in, out), lines.subList(0, 2).stream().map(CanonicalEvent::externalId).toList());
            lines.subList(0, 2).forEach(l -> {
                assertEquals(EventState.MATCHED, l.state());
                assertEquals(transfer.externalId(), l.transferKey());
            });

            assertEquals(transfer.externalId(), transfer.transferKey());
            assertEquals("ing-savings", transfer.accountRef());
            assertEquals("cba-everyday", transfer.toAccountRef());
            assertEquals(50000, transfer.amount());
            assertEquals(0, transfer.balance());
            assertEquals("2026-06-28", transfer.date().toString());
            assertEquals("Fast Transfer to CBA", transfer.description());
            assertEquals("Fast Transfer  to CBA", transfer.rawDescription());
            assertEquals(List.of(out, in), transfer.legIds());
            assertEquals(Confidence.HIGH, transfer.confidence());
            assertEquals(Provenance.BANK, transfer.provenance());
            assertNull(transfer.receipt());
            assertEquals(List.of(), transfer.flags());

            assertEquals(List.of(transfer), Projection.projectableUnits(h.sequencer().view().latestLines()));
        }
    }
}
