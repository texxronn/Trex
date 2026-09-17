package trex.core;

import org.junit.jupiter.api.Test;
import trex.core.IdentityStrategy.ContentHash;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SPEC §7 test 3 (stage 1: fixtures; ING column parsing is covered in stage 5). */
class SignCorrectnessTest {

    @Test
    void knownDebitsNegativeAndCreditsPositive() {
        List<Candidate> batch = Fixtures.naturalKeyBatch();
        assertTrue(batch.get(0).amount() < 0, "purchase is a debit");
        assertTrue(batch.get(2).amount() > 0, "salary is a credit");
    }

    @Test
    void signIsPartOfContentHashIdentity() {
        LocalDate d = LocalDate.parse("2026-06-01");
        String debit = Ids.externalId(new ContentHash("acc", d, -50000, "Transfer", 0));
        String credit = Ids.externalId(new ContentHash("acc", d, 50000, "Transfer", 0));
        assertNotEquals(debit, credit);
    }

    @Test
    void oppositeSignsAreDifferentOccurrenceSignatures() {
        Candidate debit = Fixtures.candidate("r1", "acc", "2026-06-01", -500, "X", 0, null);
        Candidate credit = Fixtures.candidate("r2", "acc", "2026-06-01", 500, "X", 0, null);
        assertEquals(List.of(0, 0), Occurrence.assignOcc(List.of(debit, credit)).stream().map(OccCandidate::occ).toList());
    }
}
