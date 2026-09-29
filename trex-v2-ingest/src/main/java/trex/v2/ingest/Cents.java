package trex.v2.ingest;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Cents parsing (V2-PROPOSAL.md §12): money is integer cents; a stray decimal is a bad row, not rounding. */
public final class Cents {

    private Cents() {}

    public static long parse(String text) {
        String s = text.strip().replace(",", "");
        if (s.isEmpty()) {
            throw new IllegalArgumentException("empty amount");
        }
        BigDecimal value;
        try {
            value = new BigDecimal(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("not a decimal number: '" + text + "'");
        }
        try {
            return value.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("more than two decimal places: '" + text + "'");
        }
    }

    public static Long optional(String text) {
        return text == null || text.isBlank() ? null : parse(text);
    }
}
