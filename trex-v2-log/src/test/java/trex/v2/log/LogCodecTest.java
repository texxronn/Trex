package trex.v2.log;

import org.junit.jupiter.api.Test;
import trex.v2.core.Action;
import trex.v2.core.Actor;
import trex.v2.core.Decision;
import trex.v2.core.Fact;
import trex.v2.core.LogLine;
import trex.v2.core.Observation;
import trex.v2.core.Provenance;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The v2 line contract (V2-PROPOSAL.md §6.1, §6.2, §6.7): every event round-trips byte-for-byte. */
class LogCodecTest {

    private static final Instant AT = Instant.parse("2026-09-29T08:31:00Z");

    private static Fact fact() {
        return new Fact(8421, "9e546cc0260ead1e", "ing-savings", LocalDate.of(2026, 9, 24), -7599, 399132,
            "VISA PURCHASE COLES 1234 SYDNEY", null, 0, Observation.POSTED, "ing-csv", Provenance.BANK,
            "sha256:2f9c", "ing-csv/3", Instant.parse("2026-09-29T08:11:02Z"));
    }

    @Test
    void factRoundTripsAndWritesNulls() {
        Fact f = fact();
        String json = LogCodec.encodeString(f);
        assertTrue(json.contains("\"kind\":\"fact\""), json);
        assertTrue(json.contains("\"receipt\":null"), json);
        assertTrue(json.contains("\"evidenceId\":\"sha256:2f9c\""), json);
        assertEquals(f, LogCodec.parse(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    void everyDecisionActionRoundTrips() {
        List<LogLine> decisions = List.of(
            new Decision.Pair(1, "a", "b", "moved", Actor.USER, "ron", AT),
            new Decision.Unpair(2, "a", "b", "no", Actor.USER, "priya", AT),
            new Decision.MarkExternal(3, "a", "ordinary", Actor.USER, "ron", AT),
            new Decision.Settle(4, "p", "q", "settled", Actor.USER, "ron", AT),
            new Decision.Dismiss(5, "POTENTIAL_DUP", List.of("a", "b"), "rebased", Actor.USER, "ron", AT),
            new Decision.Pin(6, List.of("a", "b"), "TAXES", "ATO", Actor.USER, "ron", AT),
            new Decision.Unpin(7, List.of("a"), null, Actor.USER, "mel", AT),
            new Decision.Supersede(8, "from", "to", "parser fix", Actor.SYSTEM, null, AT),
            new Decision.Retire(9, "x", "double up", Actor.SYSTEM, null, AT),
            new Decision.Revoke(10, 5, "real after all", Actor.USER, "ron", AT),
            new Decision.UserAck(11, "2026-W39", 11, "sha256:cfg", "derive/1", "statehash/1",
                "sha256:st", null, Actor.USER, "ron", AT),
            new Decision.Note(12, "a", "reimbursed", Actor.USER, "mel", AT));
        for (LogLine line : decisions) {
            assertEquals(line, LogCodec.parse(LogCodec.encode(line)),
                "round trip failed for " + ((Decision) line).action());
        }
    }

    @Test
    void systemDecisionsOmitUser() {
        String json = LogCodec.encodeString(new Decision.Supersede(8, "from", "to", "fix", Actor.SYSTEM, null, AT));
        assertFalse(json.contains("\"user\""), json);
        assertTrue(json.contains("\"actor\":\"system\""), json);
    }

    @Test
    void userAckWritesNullComment() {
        String json = LogCodec.encodeString(new Decision.UserAck(11, "2026-W39", 11, "cfg", "derive/1",
            "statehash/1", "st", null, Actor.USER, "ron", AT));
        assertTrue(json.contains("\"comment\":null"), json);
    }

    @Test
    void unknownKindIsCorruption() {
        assertThrows(JournalCorruptException.class, () -> LogCodec.parse("{\"n\":1,\"kind\":\"wat\"}".getBytes()));
    }

    @Test
    void missingRequiredFieldIsCorruption() {
        assertThrows(JournalCorruptException.class, () -> LogCodec.parse(
            ("{\"n\":1,\"kind\":\"fact\",\"v\":2}").getBytes()));
    }

    @Test
    void actionFromWireIsExhaustive() {
        for (Action a : Action.values()) {
            assertEquals(a, Action.fromWire(a.wire()));
        }
    }
}
