package trex.ingress;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** SPEC §0.4: BigDecimal at the parse boundary, scale-checked, never rounded. */
class CentsTest {

    @Test
    void conversionIsExactAndNeverRounds() {
        assertEquals(-50000, Cents.of("-500.00"));
        assertEquals(7, Cents.of("0.07"));
        assertEquals(1200, Cents.of("12"));
        assertThrows(ArithmeticException.class, () -> Cents.of("1.234"));
        assertThrows(NumberFormatException.class, () -> Cents.of("1,234.00"));
        assertThrows(NumberFormatException.class, () -> Cents.of(" 1.00"));
    }
}
