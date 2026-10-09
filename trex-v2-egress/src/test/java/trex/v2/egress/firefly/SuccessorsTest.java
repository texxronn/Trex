package trex.v2.egress.firefly;

import org.junit.jupiter.api.Test;
import trex.v2.egress.hub.HubClient.HubUnit;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SuccessorsTest {

    private static HubUnit external(String id) {
        return new HubUnit(id, "EXTERNAL", 1, "ing-savings", null, LocalDate.of(2026, 9, 1), -1000, "AUD",
            "FOOD", "RULE", "EXTERNAL", false, false, "raw", "h", List.of());
    }

    private static HubUnit transfer(String id, String a, String b) {
        return new HubUnit(id, "TRANSFER", 2, "ing-savings", "ing-orange", LocalDate.of(2026, 9, 1), 500, "AUD",
            "TRANSFER", "STRUCTURAL", "MATCHED", false, false, "raw", "h", List.of(a, b));
    }

    @Test
    void aSupersededExternalRowHasItsSuccessor() {
        var m = Successors.of("old", List.of(), Map.of("old", "new"), List.of(external("new")));
        assertEquals(List.of("new"), m.successorIds());
        assertTrue(m.sameKind());
    }

    @Test
    void aTransferWhoseLegWasSupersededHasTheNewTransfer() {
        var m = Successors.of("TRF-old", List.of("a", "b"), Map.of("a", "a2"),
            List.of(transfer("TRF-new", "a2", "b")));
        assertEquals(List.of("TRF-new"), m.successorIds());
        assertTrue(m.sameKind());
    }

    @Test
    void anUnpairedTransferIsReplacedByItsLegsAcrossKinds() {
        var m = Successors.of("TRF-old", List.of("a", "b"), Map.of(), List.of(external("a"), external("b")));
        assertEquals(List.of("a", "b"), m.successorIds());
        assertFalse(m.sameKind());
    }

    @Test
    void anExternalRowThatBecameALegPointsAtItsTransfer() {
        var m = Successors.of("a", List.of(), Map.of(), List.of(transfer("TRF-1", "a", "b")));
        assertEquals(List.of("TRF-1"), m.successorIds());
        assertFalse(m.sameKind());
    }

    @Test
    void aGenuineOrphanHasNone() {
        assertTrue(Successors.of("gone", List.of(), Map.of(), List.of(external("other"))).successorIds().isEmpty());
    }
}
