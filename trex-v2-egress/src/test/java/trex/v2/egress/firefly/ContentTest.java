package trex.v2.egress.firefly;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import trex.v2.log.Json;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentTest {

    private static final Map<String, Object> POSTED = Map.of(
        "type", "withdrawal", "date", "2026-09-01", "amount", "10.00", "currency_code", "AUD",
        "source_id", "1", "destination_name", "COLES", "description", "Coles 1234");

    @Test
    void whatWePostAndWhatFireflyEchoesHaveTheSameFingerprint() throws Exception {
        JsonNode echoed = Json.mapper().readTree("""
            {"type":"withdrawal","date":"2026-09-01T00:00:00+10:00","amount":"10.000000000000",
             "currency_code":"AUD","source_id":"1","source_name":"ING Savings",
             "destination_id":"412","destination_name":"COLES","description":"Coles 1234"}""");
        assertEquals(Content.expected(POSTED), Content.observed(echoed, Set.of("1", "2")));
        assertEquals(Content.expected(POSTED).fingerprint(), Content.observed(echoed, Set.of("1", "2")).fingerprint());
    }

    @Test
    void anOwnLiabilityOnTheFarSideComparesById() throws Exception {
        Map<String, Object> cardPayment = Map.of("type", "withdrawal", "date", "2026-09-01", "amount", "50.00",
            "currency_code", "AUD", "source_id", "1", "destination_id", "2", "description", "Card");
        JsonNode echoed = Json.mapper().readTree("""
            {"type":"withdrawal","date":"2026-09-01T00:00:00+10:00","amount":"50.00","currency_code":"AUD",
             "source_id":"1","source_name":"ING Savings","destination_id":"2","destination_name":"ING Card",
             "description":"Card"}""");
        assertEquals(Content.expected(cardPayment), Content.observed(echoed, Set.of("1", "2")));
    }

    @Test
    void anAmountMoveChangesTheFingerprint() {
        Map<String, Object> moved = new java.util.HashMap<>(POSTED);
        moved.put("amount", "12.00");
        assertNotEquals(Content.expected(POSTED).fingerprint(), Content.expected(moved).fingerprint());
    }

    @Test
    void onlyFp1HashesAreVerified() {
        assertTrue(Content.verified(Content.expected(POSTED).fingerprint()));
        assertTrue(Content.verified(Content.HAND_SPLIT));
        assertFalse(Content.verified("9f2c…an old hub unitHash"));
        assertFalse(Content.verified(""));
    }

    @Test
    void amountsCompareExactlySoASubCentEditIsDrift() {
        assertEquals("10.00", Content.exact("10.000000000000"));
        assertEquals("100.00", Content.exact("100"));
        assertEquals("10.004", Content.exact("10.004"), "below half a cent is still a difference (V6)");
        assertEquals("0.00", Content.exact(""));
        assertEquals("0.00", Content.exact(null));
        Map<String, Object> edited = new java.util.HashMap<>(POSTED);
        edited.put("amount", "10.004");
        assertNotEquals(Content.expected(POSTED).fingerprint(), Content.expected(edited).fingerprint());
    }

    @Test
    void handSplitSumsRoundInsteadOfThrowing() {
        assertEquals(1001, Content.cents("10.005"));
        assertEquals(0, Content.cents(""));
        assertEquals(0, Content.cents(null));
    }

    @Test
    void aNoonPostingAndItsEchoAreTheSameDay() throws Exception {
        // If Stage 0 A5 triggers the fallback, Projection.of posts <date>T12:00:00 (review R7/V3):
        // the expected side must reduce to the day exactly as the observed side does.
        Map<String, Object> noon = new java.util.HashMap<>(POSTED);
        noon.put("date", "2026-09-01T12:00:00");
        JsonNode echoed = Json.mapper().readTree("""
            {"type":"withdrawal","date":"2026-09-01T12:00:00+10:00","amount":"10.00","currency_code":"AUD",
             "source_id":"1","destination_id":"412","destination_name":"COLES","description":"Coles 1234"}""");
        assertEquals("2026-09-01", Content.expected(noon).date());
        assertEquals(Content.expected(noon), Content.observed(echoed, Set.of("1", "2")));
    }
}
