package trex.category;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import trex.core.CanonicalEvent;
import trex.core.Confidence;
import trex.core.EventState;
import trex.core.Provenance;
import trex.core.TypeHint;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SPEC §7 test 16: categorisation is derived, deterministic and explainable. */
class CategorizerTest {

    @TempDir
    Path dir;

    private static final String RULES = """
        categories: [SALARY, GROCERIES, DISCRETIONARY]
        rules:
          - category: SALARY
            when:
              all:
                - direction: in
                - match: "salary"
          - category: GROCERIES
            when:
              match: "woolworths|coles"
          - category: DISCRETIONARY
            when:
              all:
                - direction: out
                - match: "coffee"
        """;

    private Categorizer load(String yaml) throws IOException {
        return load(yaml, null);
    }

    /** Two files, as SPEC §6 ships them: the vocabulary and the rules here, the pins there. */
    private Categorizer load(String categoriesYaml, String pinsYaml) throws IOException {
        Path categories = dir.resolve("categories.yaml");
        Files.writeString(categories, categoriesYaml);
        Path pins = dir.resolve("pins.yaml");
        if (pinsYaml != null) {
            Files.writeString(pins, pinsYaml);
        } else {
            Files.deleteIfExists(pins);
        }
        return CategoryRules.load(categories, pins);
    }

    static CanonicalEvent line(String id, long amount, String rawDescription) {
        return new CanonicalEvent(1, id, "ing-savings", null, "AUD", LocalDate.of(2026, 7, 1), amount, 0,
            rawDescription, rawDescription, amount < 0 ? TypeHint.WITHDRAWAL : TypeHint.DEPOSIT,
            null, null, null, EventState.EXTERNAL, null, List.of(), Provenance.BANK, "ing-csv",
            null, null, null, null, null, null, Instant.EPOCH);
    }

    static CanonicalEvent transferLine(String id, List<String> legIds) {
        return new CanonicalEvent(9, id, "ing-savings", "ing-orange", "AUD", LocalDate.of(2026, 7, 1), 25000, 0,
            "Fast Transfer", "Fast Transfer", TypeHint.TRANSFER, id, legIds, null, EventState.MATCHED,
            Confidence.EXACT, List.of(), Provenance.BANK, "ing-csv", null, null, null, null, null, null,
            Instant.EPOCH);
    }

    @Test
    void firstMatchWinsInFileOrder() throws IOException {
        Categorizer c = load(RULES);
        assertEquals("SALARY", c.categorize(line("a", 250000, "Salary Deposit"), Set.of()).category());
        assertEquals("GROCERIES", c.categorize(line("b", -8500, "WOOLWORTHS 1234"), Set.of()).category());
        assertEquals("DISCRETIONARY", c.categorize(line("c", -450, "Coffee Cart"), Set.of()).category());
    }

    @Test
    void nothingMatchedIsUncategorizedNeverAGuess() throws IOException {
        Categorized result = load(RULES).categorize(line("a", -1200, "SOME SHOP"), Set.of());
        assertEquals(Categories.UNCATEGORIZED, result.category());
        assertEquals(Categorized.Origin.NONE, result.origin());
    }

    @Test
    void directionIsPartOfTheMatch() throws IOException {
        // A refunded salary payment leaves the account, so the SALARY rule must not fire.
        assertEquals(Categories.UNCATEGORIZED,
            load(RULES).categorize(line("a", -250000, "Salary reversal"), Set.of()).category());
    }

    @Test
    void structuralTransferBeatsEveryRule() throws IOException {
        String yaml = RULES + """
              - category: GROCERIES
                when:
                  match: "Fast Transfer"
            """;
        Categorizer c = load(yaml);
        CanonicalEvent leg = line("leg-a", -25000, "Fast Transfer to Orange");
        Set<String> transfers = Transfers.ids(List.of(transferLine("TRF-1", List.of("leg-a", "leg-b"))));

        Categorized result = c.categorize(leg, transfers);
        assertEquals(Categories.TRANSFER, result.category());
        assertEquals(Categorized.Origin.STRUCTURAL, result.origin());
    }

    @Test
    void pinBeatsAGeneralRuleAndIsExplained() throws IOException {
        Categorizer c = load("""
            categories: [SALARY, GROCERIES]
            rules:
              - category: GROCERIES
                comment: "the two big chains"
                when:
                  match: "woolworths"
            """, """
            pins:
              - category: SALARY
                comment: "reimbursed by work, not a grocery run"
                when: {externalId: ["abc123"]}
            """);
        Categorized pinned = c.categorize(line("abc123", -8500, "WOOLWORTHS 1234"), Set.of());
        assertEquals("SALARY", pinned.category());
        assertEquals(Categorized.Origin.PIN, pinned.origin());
        assertEquals("reimbursed by work, not a grocery run", pinned.comment());
        assertEquals("pin #1 (SALARY) — reimbursed by work, not a grocery run", pinned.explain());

        Categorized byRule = c.categorize(line("other", -8500, "WOOLWORTHS 1234"), Set.of());
        assertEquals("GROCERIES", byRule.category());
        assertEquals("rule #1 (GROCERIES) — the two big chains", byRule.explain());
    }

    /** SPEC §6: the pins block moved out, and the old shape gets a pointed message, not a stack trace. */
    @Test
    void pinsInsideCategoriesYamlAreRejectedWithTheMigration() throws IOException {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> load("""
            categories: [SALARY]
            pins:
              - category: SALARY
                when: {externalId: ["abc123"]}
            rules: []
            """));
        assertTrue(e.getMessage().contains("pins.yaml"), e.getMessage());
    }

    /** categories.yaml owns the vocabulary; a pin naming something undeclared fails, naming its file. */
    @Test
    void aPinMustUseADeclaredCategory() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> load("""
            categories: [GROCERIES]
            rules: []
            """, """
            pins:
              - category: HOLIDAYS
                when: {externalId: ["abc123"]}
            """));
        assertTrue(e.getMessage().startsWith("pins.yaml pin #1"), e.getMessage());
        assertTrue(e.getMessage().contains("not declared in categories.yaml"), e.getMessage());
    }

    /** Pins are optional by nature: the file does not exist until the first one is written (§5.7). */
    @Test
    void aMissingPinsFileIsNotAnError() throws IOException {
        Categorizer c = load("""
            categories: [GROCERIES]
            rules:
              - category: GROCERIES
                when: {match: "woolworths"}
            """);
        assertEquals("GROCERIES", c.categorize(line("x", -100, "WOOLWORTHS"), Set.of()).category());
    }

    /** A comment is optional; without one the explanation is just the entry and its category. */
    @Test
    void anEntryWithoutACommentExplainsWithoutOne() throws IOException {
        Categorizer c = load("""
            categories: [GROCERIES]
            rules:
              - category: GROCERIES
                when: {match: "woolworths"}
            """);
        Categorized r = c.categorize(line("x", -100, "WOOLWORTHS"), Set.of());
        assertEquals("rule #1 (GROCERIES)", r.explain());
        assertEquals(null, r.comment());
    }

    @Test
    void sameInputAlwaysGivesTheSameAnswer() throws IOException {
        Categorizer c = load(RULES);
        CanonicalEvent e = line("a", -8500, "Coles Express");
        Categorized first = c.categorize(e, Set.of());
        for (int i = 0; i < 100; i++) {
            assertEquals(first, c.categorize(e, Set.of()));
        }
    }

    @Test
    void changingTheRulesRecategorisesWithoutTouchingAnyLine() throws IOException {
        CanonicalEvent e = line("a", -8500, "ALDI 42");
        assertEquals(Categories.UNCATEGORIZED, load(RULES).categorize(e, Set.of()).category());

        Categorizer widened = load(RULES.replace("woolworths|coles", "woolworths|coles|aldi"));
        assertEquals("GROCERIES", widened.categorize(e, Set.of()).category());
        // The line itself is the same object it always was: no version, no new n, nothing appended.
        assertEquals(1, e.n());
    }

    @Test
    void accountsAndAmountBoundsNarrowARule() throws IOException {
        Categorizer c = load("""
            categories: [DISCRETIONARY]
            rules:
              - category: DISCRETIONARY
                when:
                  all:
                    - accounts: [ing-savings]
                    - amountMin: 1000
                    - amountMax: 5000
            """);
        assertEquals("DISCRETIONARY", c.categorize(line("a", -2500, "x"), Set.of()).category());
        assertEquals(Categories.UNCATEGORIZED, c.categorize(line("b", -500, "x"), Set.of()).category());
        assertEquals(Categories.UNCATEGORIZED, c.categorize(line("c", -9900, "x"), Set.of()).category());
    }

    @Test
    void notAndAnyCompose() throws IOException {
        Categorizer c = load("""
            categories: [GROCERIES]
            rules:
              - category: GROCERIES
                when:
                  all:
                    - any:
                        - match: "coles"
                        - match: "aldi"
                    - not:
                        match: "fuel"
            """);
        assertEquals("GROCERIES", c.categorize(line("a", -8500, "COLES 123"), Set.of()).category());
        assertEquals(Categories.UNCATEGORIZED, c.categorize(line("b", -8500, "COLES FUEL"), Set.of()).category());
    }

    @Test
    void matchOnCleanedDescriptionIsOptIn() throws IOException {
        Categorizer c = load("""
            categories: [BILLS]
            rules:
              - category: BILLS
                when:
                  match: "rent"
                  matchOn: description
            """);
        CanonicalEvent e = new CanonicalEvent(1, "a", "ing-savings", null, "AUD", LocalDate.of(2026, 7, 1),
            -200000, 0, "Rent payment", "RENT PAYMENT 0001 REF9", TypeHint.WITHDRAWAL, null, null, null,
            EventState.EXTERNAL, null, List.of(), Provenance.BANK, "ing-csv", null, null, null, null, null,
            null, Instant.EPOCH);
        assertEquals("BILLS", c.categorize(e, Set.of()).category());
    }

    @Test
    void theShippedRulesFileLoads() {
        Categorizer c = CategoryRules.load(
            Path.of("..", "deploy", "config", "categories.yaml"),
            Path.of("..", "deploy", "config", "pins.yaml"));
        assertEquals(List.of("SALARY", "INTEREST_EARNED", "INTEREST_PAID", "FEES", "VISA_FEES",
            "CASH_WITHDRAW", "INSURANCE", "SUBSCRIPTIONS", "TRANSPORT", "FUEL", "VEHICLE", "GROCERIES",
            "FOOD", "SCHOOL_FEES", "CHILDCARE", "HEALTH_SUPPLIES", "HEALTH", "SPORT_AND_LEISURE",
            "HOME_IMPROVEMENT", "SHOPPING", "BILLS", "TAXES", "SAVINGS", "DISCRETIONARY"), c.declared());
        assertEquals("GROCERIES", c.categorize(line("a", -8500, "WOOLWORTHS 4321"), Set.of()).category());
        assertEquals("SALARY", c.categorize(line("b", 250000, "Salary Deposit - Receipt No 1"), Set.of()).category());
        // Interest splits by direction: earned is INTEREST, charged is INTEREST_PAID, and the
        // charge must beat BILLS, which matches the word "mortgage" in the same line.
        assertEquals("INTEREST_EARNED", c.categorize(line("c", 822, "Bonus Interest Credit"), Set.of()).category());
        assertEquals("INTEREST_PAID", c.categorize(line("d", -174450, "Interest Charge - Receipt No 901285"), Set.of()).category());
        assertEquals("INTEREST_PAID", c.categorize(line("e", -676, "PURCHASE INTEREST"), Set.of()).category());
        assertEquals("INTEREST_PAID", c.categorize(line("f", -274, "Interest charge – Revolving"), Set.of()).category());
        assertEquals("INTEREST_PAID", c.categorize(line("g", -1000, "Mortgage interest charged"), Set.of()).category());
        // Amazon AU is retail; Amazon Web Services is hosting. One word apart, two categories,
        // which is why the rules match "amazon au" and "amazon web services" separately.
        assertEquals("SHOPPING", c.categorize(line("h", -4500, "AMAZON AU MARKETPLACE    SYDNEY"), Set.of()).category());
        assertEquals("SUBSCRIPTIONS",
            c.categorize(line("i", -4500, "AMAZON WEB SERVICES      SYDNEY"), Set.of()).category());
        // A rideshare trip is transport; Uber Eats is food.
        assertEquals("TRANSPORT", c.categorize(line("j", -1202, "UBER *TRIP HELP.UBER.C"), Set.of()).category());
        assertEquals("FOOD", c.categorize(line("k", -3200, "UBER EATS"), Set.of()).category());
        // Same brand, two categories, decided by rule order: fuel before the supermarket rule.
        assertEquals("GROCERIES", c.categorize(line("l", -18000, "COSTCO WHOLESALE AUSTR   CASULA"), Set.of()).category());
        assertEquals("FUEL", c.categorize(line("m", -9000, "COSTCO GAS CROSSROADS    CASULA"), Set.of()).category());
        // Trains are the primary mode, so they are worth seeing without petrol mixed in.
        assertEquals("TRANSPORT", c.categorize(line("q", -6000, "TFNSW OPAL MACHINE       SYDNEY"), Set.of()).category());
        assertEquals("FUEL", c.categorize(line("r", -14577, "SPEEDWAY INGLEBURN       INGLEBURN    NS"), Set.of()).category());
        // "SHELLHARBOUR" is a suburb in these statements: \bshell\b must not make it fuel.
        assertEquals(Categories.UNCATEGORIZED,
            c.categorize(line("s", -1950, "JJH RETAIL PTY LTD       SHELLHARBOUR NS"), Set.of()).category());
        // Bank charges, and the two ways the word "fee" lies: COFFEE contains it, and ING
        // suffixes cash withdrawals with "ATM owner fee of $0.00".
        assertEquals("FEES", c.categorize(line("t", -3700, "Safe Custody Monthly Fee REDIRECTED"), Set.of()).category());
        assertEquals("FEES", c.categorize(line("u", -1500, "Overdraw Fee For exceeding available funds"), Set.of()).category());
        assertEquals("FOOD", c.categorize(line("v", -384, "COFFEE HOUSE AUBURN      AUBURN       AU"), Set.of()).category());
        // An ATM row mentioning a $0.00 owner fee is a withdrawal, not a fee: CASH_WITHDRAW first.
        assertEquals("CASH_WITHDRAW", c.categorize(
            line("w", -20000, "CBA ATM  INGLEBURN B - Receipt 9763ATM owner fee of $0.00 charged"), Set.of()).category());
        assertEquals("CASH_WITHDRAW",
            c.categorize(line("x", -40000, "Wdl ATM CBA ATM INGLEBURN A NSW 218501 AUS"), Set.of()).category());

        // Card networks truncate merchant names, so the patterns anchor on the stem. These are
        // the real strings from the statements, truncated exactly as the banks wrote them.
        assertEquals("SCHOOL_FEES", c.categorize(line("y", -152920, "COM*HolyFamilyCatho Wollongong"), Set.of()).category());
        assertEquals("CHILDCARE", c.categorize(line("z", -42200, "Direct Debit 518466 Holy Family OOSH"), Set.of()).category());
        assertEquals("SPORT_AND_LEISURE",
            c.categorize(line("aa", -8100, "SQ *CAMPBELLTOWN INDOO Minto"), Set.of()).category());
        assertEquals("SHOPPING", c.categorize(line("ab", -25309, "PAYPAL *JUSTGROUPAU"), Set.of()).category());

        // "Gregory Hill" contains "rego", and it is a suburb on a real card row.
        assertEquals("VEHICLE", c.categorize(line("ac", -70300, "BLUEGUM 4X4 AND AUTOCA   INGLEBURN"), Set.of()).category());
        assertEquals(Categories.UNCATEGORIZED, c.categorize(
            line("ad", -3300, "SQ *BBQ TIME - Visa Purchase - Receipt 161041In Gregory Hill"), Set.of()).category());

        // VISA_FEES must never match the bare word: every ING card row says "Visa Purchase".
        assertEquals("VISA_FEES", c.categorize(line("ae", -25468, "PAYPAL *STATEBANKIN"), Set.of()).category());
        assertEquals("SHOPPING", c.categorize(
            line("af", -1950, "AMAZON AU RETAIL - Visa Purchase - Receipt 1"), Set.of()).category());

        assertEquals("HEALTH_SUPPLIES", c.categorize(line("ag", -17624, "CHEMIST WAREHOUSE"), Set.of()).category());
        assertEquals("HEALTH", c.categorize(line("ah", -64500, "PROSPER HEALTHCARE CEN"), Set.of()).category());
        // Hardware is tracked apart from general retail.
        assertEquals("HOME_IMPROVEMENT", c.categorize(line("ai", -4600, "BUNNINGS 402000"), Set.of()).category());
        // A café is FOOD; a restaurant is the thing you would cut, so it stays DISCRETIONARY.
        assertEquals("FOOD", c.categorize(line("n", -450, "Piccolo Me Silverwater"), Set.of()).category());
        assertEquals("DISCRETIONARY", c.categorize(line("o", -8900, "THAI RESTAURANT MINTO"), Set.of()).category());
        // Insurance providers rarely write the word, and BILLS would otherwise claim them.
        assertEquals("INSURANCE", c.categorize(line("p", -22745, "Direct Debit 485694 MEDIBANK PRIVATE"), Set.of()).category());
    }

    @Test
    void dryRunCountsCategoriesAndSurfacesTheWorklist() throws IOException {
        Categorizer c = load(RULES);
        List<CanonicalEvent> lines = List.of(
            line("a", 250000, "Salary Deposit"),
            line("b", -8500, "COLES 1"),
            line("c", -1200, "MYSTERY SHOP"),
            line("d", -1200, "MYSTERY SHOP"),
            line("e", -300, "ANOTHER SHOP"));

        String report = DryRun.report(c, lines);
        assertTrue(report.contains("transactions   5"), report);
        assertTrue(report.contains("SALARY           1"), report);
        assertTrue(report.contains("UNCATEGORIZED    3"), report);
        // Most frequent unmatched description first: that is the next rule to write.
        assertEquals(List.of("MYSTERY SHOP", "ANOTHER SHOP"), DryRun.uncategorisedDescriptions(c, lines));
        assertEquals(250000L, DryRun.totals(c, lines).get("SALARY"));
    }

    @Test
    void badRulesFailAtLoadWithTheRuleNamed() {
        assertTrue(assertThrows(IllegalArgumentException.class, () -> load("""
            categories: [GROCERIES]
            rules:
              - category: MISSPELLED
                when: {match: "x"}
            """)).getMessage().contains("not declared"));

        assertTrue(assertThrows(IllegalArgumentException.class, () -> load("""
            categories: [GROCERIES]
            rules:
              - category: GROCERIES
                when: {match: "[unclosed"}
            """)).getMessage().contains("bad regex"));

        assertTrue(assertThrows(IllegalArgumentException.class, () -> load("""
            categories: [GROCERIES]
            rules:
              - category: GROCERIES
                when: {}
            """)).getMessage().contains("empty 'when'"));

        assertTrue(assertThrows(IllegalArgumentException.class, () -> load("""
            categories: [GROCERIES]
            rules:
              - category: GROCERIES
                when: {amountMin: 500, amountMax: 100}
            """)).getMessage().contains("greater than"));

        assertTrue(assertThrows(IllegalArgumentException.class, () -> load("""
            categories: [GROCERIES]
            rules:
              - category: GROCERIES
                when: {match: "x", direction: sideways}
            """)).getMessage().contains("direction"));
    }

    @Test
    void reservedCategoriesCannotBeDeclaredOrAssigned() {
        assertTrue(assertThrows(IllegalArgumentException.class, () -> load("""
            categories: [TRANSFER]
            """)).getMessage().contains("reserved"));

        assertTrue(assertThrows(IllegalArgumentException.class, () -> load("""
            categories: [GROCERIES]
            rules:
              - category: UNCATEGORIZED
                when: {match: "x"}
            """)).getMessage().contains("reserved"));
    }

    @Test
    void unknownKeyAndDuplicateKeyAreStartupErrors() {
        assertThrows(IllegalArgumentException.class, () -> load("""
            categories: [GROCERIES]
            rulez:
              - category: GROCERIES
                when: {match: "x"}
            """));
        assertThrows(IllegalArgumentException.class, () -> load("""
            categories: [GROCERIES]
            rules:
              - category: GROCERIES
                when: {match: "x"}
            rules:
              - category: GROCERIES
                when: {match: "y"}
            """));
    }
}
