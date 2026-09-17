package trex.sequencer.ingest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trex.core.BatchStatus;
import trex.core.CandidateResult.Rejected;
import trex.core.CandidateResult.Resolved;
import trex.core.CanonicalEvent;
import trex.core.Confidence;
import trex.core.EventState;
import trex.core.Ids;
import trex.core.Provenance;
import trex.core.TypeHint;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static trex.sequencer.ingest.Harness.c;
import static trex.sequencer.ingest.Harness.id;

/** POST /decisions logic, SPEC §3.5. */
class DecisionsTest {

    @TempDir
    Path dir;

    @Test
    void markExternalReappendsWithComment() {
        try (Harness h = new Harness(dir.resolve("j.jsonl"))) {
            String dave = id(h.submit(c("r1", "ing-savings", "2026-06-01", -500, "Osko to Dave", 0, null)).results().getFirst());
            var r = h.decide(DecisionInput.markExternal("d1", dave, "paid Dave back"));
            assertEquals(new Resolved("d1", dave, 2), r.results().getFirst());
            CanonicalEvent latest = h.latest(dave);
            assertEquals(EventState.EXTERNAL, latest.state());
            assertEquals("paid Dave back", latest.comment());
            assertEquals(0, h.sequencer().view().held().size());

            var again = h.decide(DecisionInput.markExternal("d2", dave, null));
            assertInstanceOf(Rejected.class, again.results().getFirst());
        }
    }

    @Test
    void confirmTransferPairsHeldLegsManually() {
        try (Harness h = new Harness(dir.resolve("j.jsonl"))) {
            String out = id(h.submit(c("r1", "ing-savings", "2026-06-01", -500, "Fast Transfer", 0, null)).results().getFirst());
            String in = id(h.submit(c("r2", "cba-everyday", "2026-06-20", 500, "Transfer from ING", 0, null)).results().getFirst());

            var r = h.decide(DecisionInput.confirmTransfer("d1", in, out, "late settlement"));
            String trf = Ids.transferId(out, in);
            assertEquals(new Resolved("d1", trf, 5), r.results().getFirst());

            List<CanonicalEvent> lines = h.lines();
            assertEquals(5, lines.size());
            assertEquals(List.of(in, out, trf), lines.subList(2, 5).stream().map(CanonicalEvent::externalId).toList());
            lines.subList(2, 4).forEach(l -> {
                assertEquals(EventState.MATCHED, l.state());
                assertEquals("late settlement", l.comment());
            });
            CanonicalEvent t = lines.get(4);
            assertEquals(TypeHint.TRANSFER, t.typeHint());
            assertEquals(Provenance.AUTHORED, t.provenance());
            assertEquals(Confidence.EXACT, t.confidence());
            assertEquals("ing-savings", t.accountRef());
            assertEquals("cba-everyday", t.toAccountRef());
            assertEquals(List.of(out, in), t.legIds());
            assertEquals("late settlement", t.comment());
        }
    }

    @Test
    void confirmTransferRejections() {
        try (Harness h = new Harness(dir.resolve("j.jsonl"))) {
            var legs = h.submit(
                c("r1", "ing-savings", "2026-06-01", -500, "Fast Transfer", 0, null),
                c("r2", "ing-savings", "2026-06-20", 500, "Transfer from savings", 0, null),
                c("r3", "bw-usd", "2026-06-20", 500, "Transfer from ING", 0, null),
                c("r4", "cba-everyday", "2026-06-20", 700, "Transfer from ING", 0, null),
                c("r5", "cba-everyday", "2026-06-25", 500, "Transfer from ING", 0, null),
                c("r6", "ing-orange", "2026-06-25", 900, "Salary", 0, null));
            String out = id(legs.results().get(0));
            String sameAcct = id(legs.results().get(1));
            String usd = id(legs.results().get(2));
            String wrongAmount = id(legs.results().get(3));
            String ok = id(legs.results().get(4));
            String external = id(legs.results().get(5));

            var r = h.decide(
                DecisionInput.confirmTransfer("d1", out, out, null),
                DecisionInput.confirmTransfer("d2", out, sameAcct, null),
                DecisionInput.confirmTransfer("d3", out, usd, null),
                DecisionInput.confirmTransfer("d4", out, wrongAmount, null),
                DecisionInput.confirmTransfer("d5", out, external, null),
                DecisionInput.confirmTransfer("d6", out, "nope", null),
                DecisionInput.confirmTransfer("d7", out, ok, null),
                DecisionInput.markExternal("d8", out, null));
            assertEquals(BatchStatus.PARTIAL, r.batchStatus());
            assertEquals(List.of(Rejected.class, Rejected.class, Rejected.class, Rejected.class, Rejected.class,
                    Rejected.class, Resolved.class, Rejected.class),
                r.results().stream().map(Object::getClass).toList());
        }
    }

    @Test
    void dismissDupClearsFlagOnly() {
        try (Harness h = new Harness(dir.resolve("j.jsonl"))) {
            String id = id(h.submit(c("r1", "ing-savings", "2026-06-01", -500, "Coffee", 1000, null)).results().getFirst());
            assertInstanceOf(Rejected.class, h.decide(DecisionInput.dismissDup("d0", id, null)).results().getFirst());

            h.submit(c("r1", "ing-savings", "2026-06-01", -500, "Coffee", 1, null));
            var r = h.decide(DecisionInput.dismissDup("d1", id, "same row, balance column glitch"));
            assertInstanceOf(Resolved.class, r.results().getFirst());
            assertEquals(List.of(), h.latest(id).flags());
            assertEquals(EventState.EXTERNAL, h.latest(id).state());
            assertEquals(0, h.sequencer().view().review().size());
        }
    }

    @Test
    void allOrNoneDecisionsAppendNothingOnReject() {
        try (Harness h = new Harness(dir.resolve("j.jsonl"))) {
            String id = id(h.submit(c("r1", "ing-savings", "2026-06-01", -500, "Osko to Dave", 0, null)).results().getFirst());
            var r = h.sequencer().submitDecisions(true, List.of(
                DecisionInput.markExternal("d1", id, null),
                DecisionInput.markExternal("d2", "unknown", null)));
            assertEquals(BatchStatus.REJECTED, r.batchStatus());
            assertEquals(1, h.lines().size());
        }
    }

    @Test
    void reviewLegCanBeResolved() {
        try (Harness h = new Harness(dir.resolve("j.jsonl"))) {
            h.submit(c("r1", "cba-everyday", "2026-06-02", 500, "Transfer from ING", 500, null),
                c("r2", "ing-orange", "2026-06-02", 500, "Transfer from ING", 500, null));
            String review = id(h.submit(c("r3", "ing-savings", "2026-06-02", -500, "Fast Transfer", 0, null)).results().getFirst());
            assertInstanceOf(Resolved.class, h.decide(DecisionInput.markExternal("d1", review, null)).results().getFirst());
            assertEquals(0, h.sequencer().view().review().size());
        }
    }
}
