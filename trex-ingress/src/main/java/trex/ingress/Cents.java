package trex.ingress;

import java.math.BigDecimal;
import java.util.List;

/**
 * Decimal string → exact cents. The only place {@code BigDecimal} lives (SPEC §0.4): never
 * {@code Double.parseDouble(x) * 100}, which reintroduces the float error the whole design
 * avoids, and never rounded — more than two decimals is an error, not something to fix silently.
 * <p>
 * Shared by every source type: two copies would be two places to get rounding wrong.
 */
public final class Cents {

    private Cents() {}

    public static long of(String decimal) {
        return new BigDecimal(decimal).movePointRight(2).longValueExact();
    }

    /** Empty → null; unconvertible → a bad row and null, so the whole file can be reported at once. */
    public static Long optional(String file, int line, String column, String text, List<BadRow> bad) {
        if (text.isEmpty()) {
            return null;
        }
        try {
            return of(text);
        } catch (NumberFormatException | ArithmeticException _) {
            bad.add(new BadRow(file, line, column, text, "not an exact amount in cents"));
            return null;
        }
    }
}
