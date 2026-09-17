package trex.sequencer.ingest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trex.core.CandidateResult.Flagged;
import trex.core.CandidateResult.Held;
import trex.core.CandidateResult.Resolved;
import trex.core.EventState;
import trex.core.TypeHint;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static trex.sequencer.ingest.Harness.c;
import static trex.sequencer.ingest.Harness.id;

/** Phase-1 defensive rules, SPEC §3.4. */
class MatcherRulesTest {

    @TempDir
    Path dir;

    private long transfers(Harness h) {
        return h.lines().stream().filter(l -> l.typeHint() == TypeHint.TRANSFER).count();
    }

    @Test
    void ordinaryRowIsExternal() {
        try (Harness h = new Harness(dir.resolve("j.jsonl"))) {
            var r = h.submit(c("r1", "ing-savings", "2026-06-01", -1000, "Woolworths", 0, null));
            assertInstanceOf(Resolved.class, r.results().getFirst());
            assertEquals(EventState.EXTERNAL, h.latest(id(r.results().getFirst())).state());
            assertEquals(TypeHint.WITHDRAWAL, h.latest(id(r.results().getFirst())).typeHint());
        }
    }

    @Test
    void receiptAloneDoesNotHold() {
        try (Harness h = new Harness(dir.resolve("j.jsonl"))) {
            var r = h.submit(c("r1", "ing-savings", "2026-06-01", 1000, "Salary Receipt 77", 0, "77"));
            assertEquals(EventState.EXTERNAL, h.latest(id(r.results().getFirst())).state());
            assertEquals(TypeHint.DEPOSIT, h.latest(id(r.results().getFirst())).typeHint());
        }
    }

    @Test
    void transferShapedWithoutContraIsHeld() {
        try (Harness h = new Harness(dir.resolve("j.jsonl"))) {
            var r = h.submit(c("r1", "ing-savings", "2026-06-01", -1000, "OSKO payment to Dave", 0, null));
            assertInstanceOf(Held.class, r.results().getFirst());
            assertEquals(List.of(id(r.results().getFirst())),
                h.sequencer().view().held().stream().map(l -> l.externalId()).toList());
        }
    }

    @Test
    void multipleContrasGoToReviewAndLeaveHeldLegsUntouched() {
        try (Harness h = new Harness(dir.resolve("j.jsonl"))) {
            var held = h.submit(
                c("r1", "cba-everyday", "2026-06-02", 500, "Transfer from ING", 500, null),
                c("r2", "ing-orange", "2026-06-02", 500, "Transfer from ING", 500, null));
            var r = h.submit(c("r3", "ing-savings", "2026-06-02", -500, "Fast Transfer", 0, null));
            assertInstanceOf(Flagged.class, r.results().getFirst());
            assertEquals(List.of(), ((Flagged) r.results().getFirst()).flags());
            assertEquals(EventState.REVIEW, h.latest(id(r.results().getFirst())).state());
            held.results().forEach(x -> assertEquals(EventState.HELD, h.latest(id(x)).state()));
            assertEquals(3, h.lines().size());
            assertEquals(1, h.sequencer().view().review().size());
        }
    }

    @Test
    void outsideWindowDifferentCurrencyOrSameAccountStayHeld() {
        try (Harness h = new Harness(dir.resolve("j.jsonl"))) {
            h.submit(c("r1", "ing-savings", "2026-06-01", -500, "Fast Transfer", 0, null));
            h.submit(c("r2", "cba-everyday", "2026-06-05", 500, "Transfer from ING", 0, null));
            h.submit(c("r3", "bw-usd", "2026-06-01", 500, "Transfer from ING", 0, null));
            h.submit(c("r4", "ing-savings", "2026-06-01", 500, "Transfer from savings", 0, null));
            assertEquals(0, transfers(h));
            assertEquals(4, h.sequencer().view().held().size());
        }
    }

    @Test
    void t3RequiresBothLegsTransferShaped() {
        try (Harness h = new Harness(dir.resolve("j.jsonl"))) {
            h.submit(c("r1", "ing-savings", "2026-06-01", -500, "Fast Transfer", 0, null));
            var r = h.submit(c("r2", "cba-everyday", "2026-06-01", 500, "Refund", 0, null));
            assertEquals(EventState.EXTERNAL, h.latest(id(r.results().getFirst())).state());
            assertEquals(0, transfers(h));
        }
    }

    @Test
    void t1MatchesRegardlessOfShapeWhenContraIsHeld() {
        try (Harness h = new Harness(dir.resolve("j.jsonl"))) {
            h.submit(c("r1", "ing-savings", "2026-06-01", -500, "To my account Receipt 88", 0, "88"));
            var r = h.submit(c("r2", "ing-orange", "2026-06-09", 500, "Deposit Receipt 88", 0, "88"));
            assertInstanceOf(Resolved.class, r.results().getFirst());
            assertEquals(EventState.MATCHED, h.latest("TRF-88").state());
        }
    }

    @Test
    void existingTransferIdCollisionGoesToReview() {
        try (Harness h = new Harness(dir.resolve("j.jsonl"))) {
            h.submit(c("r1", "ing-savings", "2026-06-01", -500, "To my account Receipt 88", 0, "88"),
                c("r2", "ing-orange", "2026-06-01", 500, "From my account Receipt 88", 0, "88"));
            h.submit(c("r3", "cba-everyday", "2026-07-01", -700, "To my account Receipt 88", 0, "88"));
            var r = h.submit(c("r4", "bw-usd", "2026-07-01", 700, "From my account Receipt 88", 0, "88"));
            assertEquals(EventState.REVIEW, h.latest(id(r.results().getFirst())).state());
            assertEquals(1, transfers(h));
        }
    }
}
