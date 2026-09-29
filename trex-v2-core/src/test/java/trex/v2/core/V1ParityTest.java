package trex.v2.core;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * V1 parity for the rules that must not drift (V2-IMPLEMENTATION-PLAN.md §1.1): identity, merchant
 * stem and description cleaning are restatements of v1 and are pinned here to v1's exact outputs.
 *
 * <p>The golden values are taken from v1's {@code trex.core.Ids}, {@code trex.category.Merchant} and
 * {@code trex.sequencer.ingest.DescriptionCleaner}; a v2 module must not import a v1 class, so the
 * cases are restated rather than shared. See {@code docs/V2-PARITY.md}.
 */
class V1ParityTest {

    // ---- identity (v1 trex.core.Ids) ---------------------------------------------------------

    @Test
    void identityMatchesV1sCanonicalStrings() {
        // nk|accountRef|date|receipt
        assertEquals("c40ada46e45b99cf",
            Ids.naturalKey("ing-savings", LocalDate.of(2026, 9, 1), "770001"));
        // ch|accountRef|date|amount|rawDescription|occ — rawDescription verbatim
        assertEquals("3a8c9062ed44fb64",
            Ids.contentHash("ing-savings", LocalDate.of(2026, 9, 1), -1000, "COLES 1234", 0));
        assertEquals("91d5c8e26501c608",
            Ids.contentHash("ing-orange", LocalDate.of(2026, 9, 1), 450, "COFFEE CART, SYDNEY", 1));
    }

    @Test
    void idsAreLowercaseSixteenHexChars() {
        assertEquals(16, Ids.contentHash("a", LocalDate.of(2026, 9, 1), 1, "x", 0).length());
        assertEquals(16, Ids.naturalKey("a", LocalDate.of(2026, 9, 1), "r").length());
    }

    @Test
    void transferIdMatchesV1sRules() {
        assertEquals("TRF-770001", Ids.transferId("770001"), "T1: the receipt");
        String a = "3a8c9062ed44fb64";
        String b = "b308d7332363a3c2";
        assertEquals("TRF-251d8227515c5ac9", Ids.transferId(a, b));
        assertEquals(Ids.transferId(a, b), Ids.transferId(b, a), "order-independent");
    }

    // ---- merchant stem (v1 trex.category.Merchant, its MerchantTest cases) --------------------

    @Test
    void merchantStemMatchesV1() {
        assertEquals("COLES 0234", MerchantStem.stem("Coles 0234 - Visa Purchase"));
        assertEquals("OSKO PAYMENT TO DAVE", MerchantStem.stem("Osko Payment to Dave - Receipt 761771"));
        assertEquals("AMAZON AU RETAIL", MerchantStem.stem("AMAZON AU RETAIL   SYDNEY"));
        assertEquals("AMAZON WEB SERVICES", MerchantStem.stem("AMAZON WEB SERVICES    AWS.AMAZON.COM"));
        assertEquals("COM*HOLYFAMILYCATHO", MerchantStem.stem("COM*HolyFamilyCatho"));
        assertEquals("SQ *CAMPBELLTOWN INDOO", MerchantStem.stem("SQ *CAMPBELLTOWN INDOO"));
        assertEquals("JUSTGROUPAU", MerchantStem.stem("JUSTGROUPAU"));
        assertEquals("MINTO FRUIT MARKET", MerchantStem.stem("  Minto\tFruit  Market "));
        assertEquals("WOOLWORTHS 1234", MerchantStem.stem("WOOLWORTHS 1234"));
        assertEquals(MerchantStem.stem("Piccolo Me"), MerchantStem.stem("PICCOLO ME"));
        assertEquals(MerchantStem.stem("PICCOLO ME"), MerchantStem.stem("PICCOLO ME - Visa Purchase"));
    }

    @Test
    void merchantStemKeepsDistinctMerchantsDistinct() {
        assertNotEquals(MerchantStem.stem("AMAZON WEB SERVICES    AWS.AMAZON.COM"),
            MerchantStem.stem("AMAZON AU RETAIL   SYDNEY"));
        assertNotEquals(MerchantStem.stem("COSTCO WHOLESALE"), MerchantStem.stem("COSTCO GAS"));
        assertNotEquals(MerchantStem.stem("PICCOLO ME SYDNEY"), MerchantStem.stem("PICCOLO ME PARRAMATTA"));
    }

    @Test
    void merchantStemHandlesNullAndBlank() {
        assertEquals("", MerchantStem.stem(null));
        assertEquals("", MerchantStem.stem("   "));
    }

    // ---- description cleaning (v1 trex.sequencer.ingest.DescriptionCleaner) -------------------

    @Test
    void cleanMatchesV1() {
        assertEquals("A B", Clean.clean("  A\t B "));
        assertEquals("COFFEE CART, SYDNEY", Clean.clean("COFFEE CART, SYDNEY"));
        assertEquals("x", Clean.clean("x"));
        assertEquals("", Clean.clean("   "));
    }
}
