package trex.v2.egress.firefly;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** V1 parity for the posting format (V2-PROPOSAL.md §11.2): cents to fixed decimals, sign kept. */
class ProjectionFormatParityTest {

    @Test
    void amountsMatchV1sFixedDecimalStrings() {
        // v1 trex.egress.firefly.Projection.amount / signedAmount: BigDecimal, never a float, never
        // locale-formatted.
        assertEquals("12.34", Projection.amount(1234));
        assertEquals("12.34", Projection.amount(-1234), "amount is the magnitude; the sign is separate");
        assertEquals("0.00", Projection.amount(0));
        assertEquals("-12.34", Projection.signedAmount(-1234));
        assertEquals("12.34", Projection.signedAmount(1234));
        assertEquals("-0.09", Projection.signedAmount(-9));
    }
}
