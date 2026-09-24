package trex.category;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * SPEC §8 step 18. Every shape here was taken from a real statement in this project's history —
 * the stem exists because these are what the banks actually write.
 */
class MerchantTest {

    @Test
    void stripsIngsPerTransactionTails() {
        assertEquals("COLES 0234", Merchant.stem("Coles 0234 - Visa Purchase"));
        assertEquals("OSKO PAYMENT TO DAVE", Merchant.stem("Osko Payment to Dave - Receipt 761771"));
    }

    /** A padded column is the CommBank and Amazon shape: the branch is in the same field. */
    @Test
    void cutsAtAPaddedColumn() {
        assertEquals("AMAZON AU RETAIL", Merchant.stem("AMAZON AU RETAIL   SYDNEY"));
        assertEquals("AMAZON WEB SERVICES", Merchant.stem("AMAZON WEB SERVICES    AWS.AMAZON.COM"));
    }

    /**
     * A truncated merchant is still a merchant. These three cost real debugging time: the bank
     * cuts the name mid-word, so the stem must keep whatever survived rather than try to repair it.
     */
    @Test
    void keepsTruncatedMerchantsWhole() {
        assertEquals("COM*HOLYFAMILYCATHO", Merchant.stem("COM*HolyFamilyCatho"));
        assertEquals("SQ *CAMPBELLTOWN INDOO", Merchant.stem("SQ *CAMPBELLTOWN INDOO"));
        assertEquals("JUSTGROUPAU", Merchant.stem("JUSTGROUPAU"));
    }

    /** The same shop written two ways by two banks is one row in the worklist, not two. */
    @Test
    void groupsAcrossCasing() {
        assertEquals(Merchant.stem("Piccolo Me"), Merchant.stem("PICCOLO ME"));
    }

    @Test
    void collapsesInternalWhitespaceAndTrims() {
        assertEquals("MINTO FRUIT MARKET", Merchant.stem("  Minto\tFruit  Market "));
    }

    /**
     * AMAZON WEB SERVICES is a business cost and AMAZON AU is shopping. If the stem merged them
     * the worklist would propose one rule for both, which is the mistake this guards.
     */
    @Test
    void keepsDistinctMerchantsDistinct() {
        assertNotEquals(
            Merchant.stem("AMAZON WEB SERVICES    AWS.AMAZON.COM"),
            Merchant.stem("AMAZON AU RETAIL   SYDNEY"));
    }

    /** Store numbers stay: COSTCO 5 and COSTCO can be a fuel station and a warehouse. */
    @Test
    void keepsTrailingStoreNumbers() {
        assertNotEquals(Merchant.stem("COSTCO WHOLESALE"), Merchant.stem("COSTCO GAS"));
        assertEquals("WOOLWORTHS 1234", Merchant.stem("WOOLWORTHS 1234"));
    }

    @Test
    void handlesNullAndBlank() {
        assertEquals("", Merchant.stem(null));
        assertEquals("", Merchant.stem("   "));
    }
}
