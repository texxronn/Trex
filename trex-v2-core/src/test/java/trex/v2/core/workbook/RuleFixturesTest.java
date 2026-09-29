package trex.v2.core.workbook;

import org.junit.jupiter.api.Test;
import trex.v2.core.config.RuleSet;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The fixture runner (V2-PROPOSAL.md §10.4) and the regex-risk heuristic. */
class RuleFixturesTest {

    private static RuleSet rules() {
        return RuleSet.compile("t.yaml", new RuleSet.File(List.of("GROCERIES"),
            List.of(new RuleSet.RuleEntry("GROCERIES", "food", new RuleSet.WhenEntry(null, null, null, null,
                "COLES", "raw", null, null, null, null)))));
    }

    @Test
    void aMatchingCasePasses() {
        assertTrue(RuleFixtures.check(rules(), List.of(
            new RuleFixtures.Case("COLES 1234", -1000, "GROCERIES", null))).isEmpty());
        assertTrue(RuleFixtures.check(rules(), List.of(
            new RuleFixtures.Case("MYSTERY", -1000, "UNCATEGORIZED", null))).isEmpty());
    }

    @Test
    void aRegressionFailsAndNamesTheActual() {
        List<RuleFixtures.Failure> failures = RuleFixtures.check(rules(), List.of(
            new RuleFixtures.Case("COLES 1234", -1000, "FOOD", null)));
        assertEquals(1, failures.size());
        assertEquals("FOOD", failures.getFirst().expected());
        assertEquals("GROCERIES", failures.getFirst().actual());
    }

    @Test
    void detectsCatastrophicBacktracking() {
        assertTrue(CatastrophicRegex.risky("(a+)+"));
        assertTrue(CatastrophicRegex.risky(".*.*"));
        assertTrue(CatastrophicRegex.risky("(\\w*)*"));
        assertTrue(!CatastrophicRegex.risky("(a|b)+"));
        assertTrue(!CatastrophicRegex.risky("COLES|WOOLWORTHS"));
    }
}
