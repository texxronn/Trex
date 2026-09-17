package trex.core;

import org.junit.jupiter.api.Test;
import trex.core.IdentityStrategy.ContentHash;
import trex.core.IdentityStrategy.NaturalKey;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** SPEC §7 test 1 (stage 1: hand-built fixtures). */
class DeterminismTest {

    private static List<String> ids(List<Candidate> batch) {
        return Occurrence.assignOcc(batch).stream().map(oc -> Ids.externalId(Ids.strategyFor(oc))).toList();
    }

    @Test
    void sameBatchYieldsSameIdsEveryRun() {
        for (List<Candidate> batch : List.of(Fixtures.naturalKeyBatch(), Fixtures.contentHashBatch())) {
            List<String> first = ids(batch);
            for (int run = 0; run < 5; run++) {
                assertEquals(first, ids(batch));
            }
        }
    }

    /** Golden values computed independently with sha256sum; they pin the frozen identity contract. */
    @Test
    void goldenIdentityValues() {
        assertEquals("1420f57e211537d7", Ids.externalId(new NaturalKey("ing-savings", "123456789")));
        assertEquals("461ef55588483c32", Ids.externalId(
            new ContentHash("ing-savings", LocalDate.parse("2026-06-28"), -50000, "Fast Transfer to CBA", 0)));
        assertEquals("0e48e920f178c7c4", Ids.externalId(
            new ContentHash("ing-savings", LocalDate.parse("2026-06-28"), -50000, "Fast Transfer to CBA", 1)));
        assertEquals("TRF-52cfcf1f23434e78", Ids.transferId("aaaa", "bbbb"));
        assertEquals("TRF-52cfcf1f23434e78", Ids.transferId("bbbb", "aaaa"));
        assertEquals("TRF-123456789", Ids.transferId("123456789"));
    }

    @Test
    void rawDescriptionIsHashedUtf8AndUntrimmed() {
        assertEquals("16fa7ef05bf56f44", Ids.externalId(
            new ContentHash("acc", LocalDate.parse("2026-01-02"), 1234, "Café  latte ", 0)));
    }

    @Test
    void occIsStableAscendingInInputOrder() {
        List<OccCandidate> occ = Occurrence.assignOcc(Fixtures.contentHashBatch());
        assertEquals(List.of(0, 1, 2, 0, 0, 0), occ.stream().map(OccCandidate::occ).toList());
        assertEquals(Fixtures.contentHashBatch(), occ.stream().map(OccCandidate::c).toList());
    }

    @Test
    void naturalKeyCandidatesSkipOcc() {
        Candidate a = Fixtures.candidate("r1", "acc", "2026-06-01", -100, "SAME", 0, "R1");
        Candidate b = Fixtures.candidate("r2", "acc", "2026-06-01", -100, "SAME", 0, null);
        Candidate c = Fixtures.candidate("r3", "acc", "2026-06-01", -100, "SAME", 0, "R2");
        Candidate d = Fixtures.candidate("r4", "acc", "2026-06-01", -100, "SAME", 0, "  ");
        List<OccCandidate> occ = Occurrence.assignOcc(List.of(a, b, c, d));
        assertEquals(List.of(0, 0, 0, 1), occ.stream().map(OccCandidate::occ).toList());
    }
}
