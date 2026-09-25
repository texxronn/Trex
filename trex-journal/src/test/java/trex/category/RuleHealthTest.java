package trex.category;

import trex.journal.RuleFiles;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trex.core.CanonicalEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC §9 rule health. The file says what the rules are; only the journal says what they do, and
 * a rule that fires for nothing looks exactly like a good one until the two are put together.
 */
class RuleHealthTest {

    @TempDir
    Path dir;

    private static final List<CanonicalEvent> JOURNAL = List.of(
        CategorizerTest.line("g1", -8500, "WOOLWORTHS 1234"),
        CategorizerTest.line("g2", -9200, "COLES 0234"),
        CategorizerTest.line("g3", -4100, "WOOLWORTHS 5678"),
        CategorizerTest.line("c1", -520, "PICCOLO ME"),
        CategorizerTest.line("x1", -1500, "SOMETHING NOBODY MATCHES"));

    private Categorizer load(String categories, String pins) throws IOException {
        Files.writeString(dir.resolve("categories.yaml"), categories);
        Files.writeString(dir.resolve("pins.yaml"), pins == null ? "pins: []\n" : pins);
        return RuleFiles.load(dir.resolve("categories.yaml"), dir.resolve("pins.yaml"));
    }

    @Test
    void countsWhatEachRuleActuallyDecides() throws IOException {
        RuleHealth.Report r = RuleHealth.of(load("""
            categories: [GROCERIES, FOOD]
            rules:
              - category: GROCERIES
                when: {match: "woolworths|coles"}
              - category: FOOD
                when: {match: "piccolo"}
            """, null), JOURNAL);

        RuleHealth.RuleStat groceries = r.rules().get(0);
        assertEquals(3, groceries.hits());
        assertEquals(-21800, groceries.total());
        // Three distinct merchant stems, because store numbers are kept: WOOLWORTHS 1234 and
        // WOOLWORTHS 5678 are two of them (Merchant.stem deliberately does not strip a trailing
        // number — COSTCO GAS and COSTCO WHOLESALE are two shops, not one).
        assertEquals(3, groceries.merchants());
        assertFalse(groceries.dead());

        assertEquals(1, r.rules().get(1).hits());
        assertEquals(4, r.categorized());
        assertEquals(1, r.uncategorized());
    }

    /** A rule whose subject is gone, or whose pattern never worked. */
    @Test
    void namesARuleThatFiresForNothing() throws IOException {
        RuleHealth.Report r = RuleHealth.of(load("""
            categories: [GROCERIES, FOOD]
            rules:
              - category: GROCERIES
                when: {match: "woolworths|coles"}
              - category: FOOD
                comment: "the cafe that closed"
                when: {match: "gelato messina"}
            """, null), JOURNAL);

        RuleHealth.RuleStat dead = r.rules().get(1);
        assertEquals(0, dead.hits());
        assertTrue(dead.dead(), "nothing matches it at all");
        assertFalse(dead.fullyShadowed());
        assertNull(dead.shadowedBy());
    }

    /**
     * The more interesting failure: a rule that matches plenty and decides nothing, because
     * something above it always gets there first. Reading the file cannot show this.
     */
    @Test
    void namesARuleThatIsFullyShadowed() throws IOException {
        RuleHealth.Report r = RuleHealth.of(load("""
            categories: [GROCERIES, FOOD]
            rules:
              - category: GROCERIES
                when: {match: "woolworths|coles"}
              - category: FOOD
                comment: "added later, and never fires"
                when: {match: "woolworths"}
            """, null), JOURNAL);

        RuleHealth.RuleStat shadowed = r.rules().get(1);
        assertEquals(0, shadowed.hits());
        assertEquals(2, shadowed.shadowed(), "it matches both WOOLWORTHS rows");
        assertEquals(1, shadowedBy(shadowed));
        assertTrue(shadowed.fullyShadowed());
        assertFalse(shadowed.dead(), "dead and shadowed are different problems with different fixes");
    }

    private static int shadowedBy(RuleHealth.RuleStat stat) {
        return stat.shadowedBy() == null ? -1 : stat.shadowedBy();
    }

    /** A pin naming an id the journal no longer holds — usually a re-ingest that moved it. */
    @Test
    void namesAStalePin() throws IOException {
        RuleHealth.Report r = RuleHealth.of(load("""
            categories: [GROCERIES, FOOD]
            rules:
              - category: GROCERIES
                when: {match: "woolworths"}
            """, """
            pins:
              - category: FOOD
                comment: "still current"
                when: {externalId: ["g1"]}
              - category: FOOD
                comment: "points at an id that is gone"
                when: {externalId: ["deadbeef"]}
            """), JOURNAL);

        assertEquals(1, r.pins().get(0).hits());
        assertFalse(r.pins().get(0).stale());
        assertEquals(0, r.pins().get(1).hits());
        assertEquals(1, r.pins().get(1).missingIds());
        assertTrue(r.pins().get(1).stale());
    }

    /** Pinning the same shop three times is the file asking for one line instead of three. */
    @Test
    void promotesAMerchantPinnedOverAndOver() throws IOException {
        RuleHealth.Report r = RuleHealth.of(load("""
            categories: [GROCERIES, FOOD]
            rules: []
            """, """
            pins:
              - category: FOOD
                when: {externalId: ["g1", "g3"]}
              - category: FOOD
                when: {externalId: ["g2"]}
            """), List.of(
                CategorizerTest.line("g1", -100, "PICCOLO ME"),
                CategorizerTest.line("g2", -200, "PICCOLO ME - Visa Purchase"),
                CategorizerTest.line("g3", -300, "PICCOLO ME")));

        assertEquals(1, r.promotions().size());
        RuleHealth.Promotion p = r.promotions().getFirst();
        assertEquals("PICCOLO ME", p.stem());
        assertEquals("FOOD", p.category());
        assertEquals(3, p.pinned());
        assertEquals(List.of("g1", "g2", "g3"), p.externalIds());
    }

    @Test
    void twoPinsForOneMerchantAreNotYetAPromotion() throws IOException {
        RuleHealth.Report r = RuleHealth.of(load("""
            categories: [FOOD]
            rules: []
            """, """
            pins:
              - category: FOOD
                when: {externalId: ["g1", "g2"]}
            """), List.of(
                CategorizerTest.line("g1", -100, "PICCOLO ME"),
                CategorizerTest.line("g2", -200, "PICCOLO ME")));
        assertTrue(r.promotions().isEmpty(), "two is a coincidence; three is a pattern");
    }

    /** Structural transfers belong to the journal, not to any rule, and are counted apart. */
    @Test
    void transfersAreNeitherCategorizedNorUncategorized() throws IOException {
        Categorizer c = load("""
            categories: [GROCERIES]
            rules:
              - category: GROCERIES
                when: {match: "woolworths"}
            """, null);
        List<CanonicalEvent> withTransfer = List.of(
            CategorizerTest.line("g1", -8500, "WOOLWORTHS 1234"),
            CategorizerTest.transferLine("TRF-1", List.of("leg-a", "leg-b")),
            CategorizerTest.line("leg-a", -25000, "Fast Transfer to Orange"));
        RuleHealth.Report r = RuleHealth.of(c, withTransfer);
        assertEquals(1, r.categorized());
        assertEquals(0, r.uncategorized());
        assertEquals(2, r.structural(), "the TRANSFER line and its leg");
    }
}
