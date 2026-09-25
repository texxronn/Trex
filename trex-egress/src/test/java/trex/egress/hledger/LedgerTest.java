package trex.egress.hledger;

import org.junit.jupiter.api.Test;
import trex.core.CanonicalEvent;
import trex.core.Confidence;
import trex.core.EventState;
import trex.core.Provenance;
import trex.core.TypeHint;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC §5.9. The renderer is pure, so all of this runs without hledger — but the assertions it
 * writes are checked by hledger against the bank's own running balance, and every mistake here is
 * a number that looks plausible and is wrong.
 */
class LedgerTest {

    private static final Accounts ACCOUNTS = new Accounts(
        Map.of("ing-salary", "assets:ing:salary",
               "ing-orange", "assets:ing:orange-everyday",
               "bw-credit-card", "liabilities:bankwest:credit-card"),
        Set.of("SALARY"), "assets:unresolved", "equity:opening-balances",
        Map.of("cash-ron", "expenses:cash-withdraw:ron"), Set.of("cash-ron"));

    private static final LocalDate D1 = LocalDate.of(2026, 9, 1);
    private static final LocalDate D2 = LocalDate.of(2026, 9, 2);

    private static Ledger.Row row(long n, String id, String account, LocalDate date,
                                  long amount, long balance, String raw, String category) {
        return new Ledger.Row(line(n, id, account, null, date, amount, balance, raw,
            amount < 0 ? TypeHint.WITHDRAWAL : TypeHint.DEPOSIT, EventState.EXTERNAL, null),
            category);
    }

    private static CanonicalEvent line(long n, String id, String account, String to, LocalDate date,
                                       long amount, long balance, String raw, TypeHint type,
                                       EventState state, List<String> legs) {
        return new CanonicalEvent(n, id, account, to, "AUD", date, amount, balance,
            raw, raw, type, null, legs, null, state, Confidence.HIGH, List.of(), Provenance.BANK,
            "ing-csv", null, null, null, null, null, null, Instant.EPOCH);
    }

    private static String render(List<Ledger.Row> rows) {
        return new Ledger(ACCOUNTS, true).render(rows, 99, "rev");
    }

    /** The posting line for an account inside one transaction, identified by its trex id. */
    private static String posting(String text, String id, String account) {
        int at = text.indexOf("; id: " + id);
        assertTrue(at > 0, "no transaction with id " + id);
        return text.substring(at).lines()
            .filter(l -> l.stripLeading().startsWith(account))
            .findFirst().orElseThrow(() -> new AssertionError("no posting to " + account));
    }

    // ---------------------------------------------------------------- chronology

    /**
     * BankWest exports newest first, so journal order runs against the clock. Taking the highest
     * {@code n} on a day as its close asserts a real balance off a real row — just not the last
     * one — and hledger then fails on the following day for no reason a human can see.
     */
    @Test
    void theDayClosesWhereTheBankSaysItDoes() {
        // Ingested newest first: n=1 happened last.
        var rows = List.of(
            row(1, "b", "bw-credit-card", D1, -3520, -631176, "YMCA", "SPORT_AND_LEISURE"),
            row(2, "a", "bw-credit-card", D1, -1483, -627656, "AMAZON", "SHOPPING"));

        String text = render(rows);
        // -631176 is the close; it must be asserted, and -627656 must not be.
        assertTrue(text.contains("= -$6311.76"), text);
        assertFalse(text.contains("= -$6276.56"), text);
        // And the file prints the day in the order it happened, so `hledger reg` matches a statement.
        assertTrue(text.indexOf("; id: a") < text.indexOf("; id: b"), text);
    }

    /** ING exports oldest first. The same code has to read that without being told which bank. */
    @Test
    void theOppositeExportOrderNeedsNoConfiguration() {
        var rows = List.of(
            row(1, "a", "ing-orange", D1, 1000, 11000, "FIRST", "SALARY"),
            row(2, "b", "ing-orange", D1, -500, 10500, "SECOND", "GROCERIES"));

        String text = render(rows);
        assertTrue(text.contains("= $105.00"), text);
        assertTrue(text.indexOf("; id: a") < text.indexOf("; id: b"), text);
    }

    /**
     * A missing line breaks the day into two disconnected runs. Asserting either run's end would
     * assert a figure wrong by the amount of the line trex does not have, so the day is skipped —
     * and hledger then fails on a later day, which is where the evidence actually is.
     */
    @Test
    void aMissingLineSuppressesTheAssertionRatherThanGuessing() {
        // 100 -> 90 (-10), then an unseen -25, then 40 -> 35 (-5): nothing links 90 to 40.
        var rows = List.of(
            row(1, "a", "ing-orange", D1, -1000, 9000, "SEEN", "GROCERIES"),
            row(2, "b", "ing-orange", D1, -500, 3500, "ALSO SEEN", "GROCERIES"));

        String text = render(rows);
        assertFalse(text.contains(" = "), "asserted a balance it cannot know:\n" + text);
    }

    /**
     * Two rows sharing a balance make the middle of the day ambiguous but leave the tail unique.
     * The close is still knowable, so it is still asserted — only the printed order falls back.
     * Intra-day order was never knowable from a statement; the closing balance always was.
     */
    @Test
    void anAmbiguousMiddleStillAssertsTheClose() {
        // 1983.97 is the unique tail: +400 and +2000 both land the account on 2388.97.
        var rows = List.of(
            row(1, "a", "ing-orange", D1, 40000, 238897, "TRANSFER IN", "SALARY"),
            row(2, "b", "ing-orange", D1, -40000, 198897, "ATM", "CASH_WITHDRAW"),
            row(3, "c", "ing-orange", D1, 200000, 238897, "FAST TRANSFER", "SALARY"),
            row(4, "d", "ing-orange", D1, -40500, 198397, "OOSH", "CHILDCARE"));

        String text = render(rows);
        assertTrue(text.contains("= $1983.97"), text);
    }

    // ---------------------------------------------------------------- openings

    /**
     * trex's history starts mid-life, so the bank's running balance counts transactions the
     * journal has never seen. Without an opening every single assertion fails at the first
     * transaction and the file says nothing about the data.
     */
    @Test
    void everyAccountOpensOnWhatItHeldBeforeTrexSawIt() {
        var rows = List.of(row(1, "a", "ing-orange", D1, -1000, 9000, "SPEND", "GROCERIES"));

        String text = render(rows);
        assertTrue(text.contains("2026-08-31 * Opening balance — ing-orange"), text);
        assertTrue(text.contains("equity:opening-balances"), text);
        // 90.00 after a -10.00 movement means it opened on 100.00.
        assertTrue(text.contains("assets:ing:orange-everyday                        $100.00"), text);
    }

    /**
     * The opening is derived forward, from the first day, not backward from the last — the
     * opposite of the Firefly egress, on purpose. Anchoring forward makes the early assertions
     * hold and the first one after a gap fail, which names the gap. Backward would fail every
     * assertion before it, which names nothing.
     */
    @Test
    void theOpeningIsAnchoredForwardSoAGapLocalisesItself() {
        // Day 1 closes on 90. Day 2's close of 60 accounts for -20 the journal does not have.
        var rows = List.of(
            row(1, "a", "ing-orange", D1, -1000, 9000, "SPEND", "GROCERIES"),
            row(2, "b", "ing-orange", D2, -1000, 6000, "SPEND", "GROCERIES"));

        Ledger ledger = new Ledger(ACCOUNTS, true);
        ledger.render(rows, 99, "rev");
        Ledger.Opening opening = ledger.openings().getFirst();
        assertEquals(10000, opening.cents(), "opening must come from day one, not from the end");
        assertEquals(-2000, opening.gap(), "the gap is the history the bank counted and trex lacks");
    }

    // ---------------------------------------------------------------- units

    /**
     * A TRANSFER line replaces its two legs. Emitting the legs as well counts every internal
     * movement twice — silently, as a wrong total rather than an error.
     */
    @Test
    void aTransferReplacesItsLegsAndInheritsTheirAssertions() {
        var legOut = new Ledger.Row(line(1, "leg-out", "ing-salary", null, D1, -5000, 8317,
            "Internal Transfer", TypeHint.WITHDRAWAL, EventState.MATCHED, null), "TRANSFER");
        var legIn = new Ledger.Row(line(2, "leg-in", "ing-orange", null, D1, 5000, 13000,
            "Internal Transfer", TypeHint.DEPOSIT, EventState.MATCHED, null), "TRANSFER");
        var transfer = new Ledger.Row(line(3, "TRF-1", "ing-salary", "ing-orange", D1, 5000, 0,
            "Internal Transfer", TypeHint.TRANSFER, EventState.MATCHED,
            List.of("leg-out", "leg-in")), "TRANSFER");

        String text = render(List.of(legOut, legIn, transfer));
        assertFalse(text.contains("; id: leg-out"), "a leg was emitted:\n" + text);
        assertFalse(text.contains("; id: leg-in"), "a leg was emitted:\n" + text);
        // Both legs' closing balances land on the transfer, one per side.
        assertTrue(posting(text, "TRF-1", "assets:ing:salary").endsWith("= $83.17"), text);
        assertTrue(posting(text, "TRF-1", "assets:ing:orange-everyday").endsWith("= $130.00"), text);
    }

    /**
     * Unlike Firefly, nothing is withheld. A balance assertion needs every movement the bank
     * counted, so an undecided row posts its known side and parks the other in suspense.
     */
    @Test
    void anUnresolvedRowIsStillPostedAgainstSuspense() {
        var held = new Ledger.Row(line(1, "h", "ing-orange", null, D1, -1000, 9000, "WHO?",
            TypeHint.WITHDRAWAL, EventState.HELD, null), "UNCATEGORIZED");

        String text = render(List.of(held));
        assertTrue(text.contains("assets:unresolved"), text);
        // `!` is hledger's own vocabulary for undecided; `*` would claim it is settled.
        assertTrue(text.contains("2026-09-01 ! WHO?"), "a HELD row is pending, not cleared:\n" + text);
    }

    // ---------------------------------------------------------------- the account tree

    /**
     * A refund is not income. It belongs in the same expense account as the purchase it reverses,
     * as a negative amount, so that a category's balance nets to what was actually spent.
     * Deciding by the sign instead files every returned purchase under {@code income:} and
     * overstates both sides of every report.
     */
    @Test
    void aRefundStaysANegativeExpense() {
        var rows = List.of(row(1, "r", "bw-credit-card", D1, 10000, -50000,
            "BUNNINGS 402000 CASULA", "HOME_IMPROVEMENT"));

        String text = render(rows);
        assertTrue(text.contains("expenses:home-improvement:bunnings"), text);
        assertFalse(text.contains("income:home-improvement"), text);
    }

    /** Money earned is income, and it is the declared category that says so, not the sign. */
    @Test
    void declaredIncomeCategoriesBecomeIncome() {
        var rows = List.of(row(1, "s", "ing-orange", D1, 500000, 500000, "ACME PAYROLL", "SALARY"));

        assertTrue(render(rows).contains("income:salary:acme"), render(rows));
    }

    /** With nothing declared there is no way to tell a refund from income, so the sign decides. */
    @Test
    void withoutConfigurationTheSignDecides() {
        Accounts bare = Accounts.none();
        assertEquals("expenses", bare.top("GROCERIES", -100));
        assertEquals("income", bare.top("GROCERIES", 100));
        assertEquals("income", ACCOUNTS.top("SALARY", 100));
        assertEquals("expenses", ACCOUNTS.top("GROCERIES", 100));
    }

    /** A colon is hledger's tree separator, so a merchant must never smuggle one into a name. */
    @Test
    void aMerchantCannotInventATreeLevel() {
        assertEquals("sq-foo-bar", Accounts.slug("SQ *FOO: BAR"));
        assertEquals("unknown", Accounts.slug("   "));
    }

    // ---------------------------------------------------------------- formatting

    /** The assertion has to match to the cent, so amounts are never near a float. */
    @Test
    void amountsAreExact() {
        assertEquals("$0.07", Ledger.money(7, "AUD"));
        assertEquals("-$6276.56", Ledger.money(-627656, "AUD"));
        assertEquals("USD 10.00", Ledger.money(1000, "USD"));
    }

    /** A semicolon in a description would open a comment and swallow the postings after it. */
    @Test
    void aDescriptionCannotOpenAComment() {
        assertEquals("FOO, BAR", Ledger.clean("FOO; BAR"));
        assertEquals("A B", Ledger.clean("A\n  B"));
        assertEquals("(no description)", Ledger.clean(null));
    }

    // ---------------------------------------------------------------- declared accounts

    private static Ledger.Row cash(long n, String id, LocalDate date, long amount,
                                   long balance, TypeHint type, String raw) {
        return new Ledger.Row(line(n, id, "cash-ron", null, date, amount, balance, raw, type,
            EventState.EXTERNAL, null), type == TypeHint.ATTESTATION ? "TRANSFER" : "GROCERIES");
    }

    /**
     * SPEC §7 test 32. The plug is computed AS attested-minus-derived, so an assertion on it would
     * be satisfied by construction and could never fail — the same tautology that got the
     * balancing account rejected. A declared account therefore carries none at all, and the
     * generated file's assertion count must stay exactly what the bank accounts contribute.
     */
    @Test
    void aDeclaredAccountCarriesNoAssertionAnywhere() {
        var rows = List.of(
            cash(1, "a1", D1, 0, 16000, TypeHint.ATTESTATION, "Cash attestation"),
            cash(2, "p1", D2, -4000, 0, TypeHint.WITHDRAWAL, "Market stall"),
            cash(3, "a2", LocalDate.of(2026, 9, 25), 0, 6000, TypeHint.ATTESTATION, "Cash attestation"));

        String text = render(rows);
        assertFalse(text.contains(" = "), "a declared account must assert nothing:\n" + text);
        assertFalse(text.contains("Opening balance"), "and has no derivable opening:\n" + text);
    }

    /** The plug is the unrecorded remainder, and it lands where hledger.yaml points it. */
    @Test
    void anAttestationPlugsTheDifferenceIntoTheConfiguredAccount() {
        var rows = List.of(
            cash(1, "a1", D1, 0, 16000, TypeHint.ATTESTATION, "Cash attestation"),
            cash(2, "p1", D2, -4000, 0, TypeHint.WITHDRAWAL, "Market stall"),
            cash(3, "a2", LocalDate.of(2026, 9, 25), 0, 6000, TypeHint.ATTESTATION, "Cash attestation"));

        String text = render(rows);
        // opened at 160 from nothing, then 160-40 = 120 derived against 60 attested: -60 unrecorded
        assertTrue(text.contains("$160.00"), "the first attestation opens the account:\n" + text);
        assertTrue(text.contains("-$60.00"), "the second plugs the unrecorded remainder:\n" + text);
        assertTrue(text.contains("expenses:cash-withdraw:ron"), text);
    }

    /** Recording everything leaves nothing to plug, and the file says so rather than posting 0. */
    @Test
    void anAttestationThatMatchesTheRecordPlugsNothing() {
        var rows = List.of(
            cash(1, "a1", D1, 0, 16000, TypeHint.ATTESTATION, "Cash attestation"),
            cash(2, "p1", D2, -10000, 0, TypeHint.WITHDRAWAL, "Market stall"),
            cash(3, "a2", LocalDate.of(2026, 9, 25), 0, 6000, TypeHint.ATTESTATION, "Cash attestation"));

        assertTrue(render(rows).contains("everything since the last attestation was recorded"),
            render(rows));
    }
}
