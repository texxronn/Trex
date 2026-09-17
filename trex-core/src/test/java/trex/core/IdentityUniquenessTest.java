package trex.core;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** SPEC §7 test 2. */
class IdentityUniquenessTest {

    @Test
    void naturalKeyDistinctIdsEqualRowCount() {
        List<Candidate> batch = Fixtures.naturalKeyBatch();
        Set<String> ids = new HashSet<>();
        Occurrence.assignOcc(batch).forEach(oc -> ids.add(Ids.externalId(Ids.strategyFor(oc))));
        assertEquals(batch.size(), ids.size());
    }

    @Test
    void contentHashIdsUniqueWithinOccurrenceGroups() {
        List<OccCandidate> occ = Occurrence.assignOcc(Fixtures.contentHashBatch());
        Map<Sig, List<String>> idsBySig = occ.stream().collect(Collectors.groupingBy(
            oc -> Sig.of(oc.c()),
            Collectors.mapping(oc -> Ids.externalId(Ids.strategyFor(oc)), Collectors.toList())));
        idsBySig.values().forEach(ids -> assertEquals(ids.size(), new HashSet<>(ids).size()));
        Set<String> all = occ.stream().map(oc -> Ids.externalId(Ids.strategyFor(oc))).collect(Collectors.toSet());
        assertEquals(occ.size(), all.size());
    }

    @Test
    void sameReceiptInDifferentAccountsGivesDifferentIds() {
        Candidate out = Fixtures.candidate("r1", "ing-savings", "2026-06-03", -50000, "To my account", 0, "777");
        Candidate in = Fixtures.candidate("r2", "ing-orange", "2026-06-03", 50000, "From my account", 0, "777");
        List<OccCandidate> occ = Occurrence.assignOcc(List.of(out, in));
        assertEquals(2, occ.stream().map(oc -> Ids.externalId(Ids.strategyFor(oc))).distinct().count());
    }
}
