package trex.v2.egress.firefly;

import com.fasterxml.jackson.databind.JsonNode;
import trex.v2.core.Hashes;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Set;

/**
 * What a Firefly transaction says, reduced to the fields the egress owns on a single split
 * (V2-SPEC.md §11.1), so it can be computed from both sides: the posting we would send and the group
 * Firefly holds. That is the point — the hub's unit hash cannot be recovered from Firefly, so a
 * rebuilt state had a blank hash and content drift became invisible (F3).
 */
public record Content(String type, String date, String amount, String currency, String source,
                      String destination, String description) {

    static final String PREFIX = "fp1:";

    /** Recorded for a group you split by hand: its content is yours and is never compared. */
    public static final String HAND_SPLIT = PREFIX + "hand-split";

    public String fingerprint() {
        return PREFIX + Hashes.sha256(String.join("|", type, date, amount, currency,
            source, destination, description));
    }

    /** A state hash this version wrote. Anything else (blank, an old hub hash) is checked once. */
    public static boolean verified(String stateHash) {
        return stateHash != null && stateHash.startsWith(PREFIX);
    }

    public static Content expected(Map<String, Object> split) {
        return new Content(str(split.get("type")), day(str(split.get("date"))),
            exact(str(split.get("amount"))), str(split.get("currency_code")),
            side(split.get("source_id"), split.get("source_name")),
            side(split.get("destination_id"), split.get("destination_name")),
            str(split.get("description")));
    }

    public static Content observed(JsonNode split, Set<String> ownIds) {
        return new Content(split.path("type").asText(""), day(split.path("date").asText("")),
            exact(split.path("amount").asText("")), split.path("currency_code").asText(""),
            observedSide(split, "source", ownIds), observedSide(split, "destination", ownIds),
            split.path("description").asText(""));
    }

    private static String observedSide(JsonNode split, String side, Set<String> ownIds) {
        String id = split.path(side + "_id").asText(null);
        return id != null && ownIds.contains(id) ? "id:" + id : "name:" + split.path(side + "_name").asText("");
    }

    private static String side(Object id, Object name) {
        return id != null ? "id:" + id : "name:" + str(name);
    }

    /**
     * The local day of a date we posted or Firefly echoed. Both sides go through here (review V3): a
     * noon posting ({@code 2026-09-01T12:00:00}) and its echo ({@code 2026-09-01T12:00:00+10:00})
     * must agree. Firefly echoes a local date-time in its own zone, so the day is the first ten
     * characters. If Stage 0 A5 measured a UTC-normalised echo instead, use
     * {@code OffsetDateTime.parse(date).toLocalDate()} for an offset-bearing value — safe only because
     * the posting is at noon, which no offset of at most twelve hours moves to another day.
     */
    static String day(String date) {
        if (date == null) {
            return "";
        }
        return date.length() >= 10 ? date.substring(0, 10) : date;
    }

    /**
     * The exact amount, normalised, so the fingerprint sees every difference: Firefly's
     * {@code "10.000000000000"} equals our {@code "10.00"}, but a hand-edited {@code "10.004"} does
     * not — rounding to cents would hide a remainder below half a cent (review V6). Blank is zero,
     * never a throw (R4).
     */
    static String exact(String amount) {
        if (amount == null || amount.isBlank()) {
            return "0.00";
        }
        BigDecimal v = new BigDecimal(amount.strip()).abs().stripTrailingZeros();
        return (v.scale() < 2 ? v.setScale(2) : v).toPlainString();
    }

    /** Cents, rounded HALF_UP — only for summing a hand-split group, never for the fingerprint. */
    static long cents(String amount) {
        if (amount == null || amount.isBlank()) {
            return 0;
        }
        return new BigDecimal(amount.strip()).abs().setScale(2, RoundingMode.HALF_UP)
            .movePointRight(2).longValueExact();
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }
}
