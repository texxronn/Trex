package trex.v2.core;

import org.junit.jupiter.api.Test;
import trex.v2.core.config.Account;
import trex.v2.core.config.BalanceSource;
import trex.v2.core.config.DeriveConfig;
import trex.v2.core.config.Profiles;
import trex.v2.core.config.Registry;
import trex.v2.core.config.RuleSet;
import trex.v2.core.config.TransferRules;
import trex.v2.core.config.TransferRules.TransferPattern;
import trex.v2.core.config.User;
import trex.v2.core.derive.CashMonth;
import trex.v2.core.derive.Derivation;
import trex.v2.core.derive.Derive;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The derived {@code cash_month} table (V2-CASH-PLAN.md §4.4, V2-SPEC.md §9.4): the signed
 * unrecorded cash figure per declared wallet and calendar month, and the parts that make it. The
 * fixtures are {@link CashPairingTest}'s: a declared {@code cash-ron} and an {@code ATM} pattern on
 * {@code ing-savings} that clears to it.
 */
class CashMonthTest {

    private static final Instant ASOF = Instant.parse("2026-10-01T00:00:00Z");

    private static DeriveConfig config() {
        Registry registry = new Registry(
            Map.of("ing-savings", new Account("ing-savings", "AUD", BalanceSource.STATEMENT, 7),
                "cash-ron", new Account("cash-ron", "AUD", BalanceSource.DECLARED, 7)),
            Map.of("ron", new User("ron", "Ron", true, "weekly")));
        TransferRules rules = new TransferRules(4, 0, 0, 30, 0.5, Map.of(
            "ing-savings", List.of(
                new TransferPattern("ATM",
                    Pattern.compile("ATM", Pattern.CASE_INSENSITIVE), Rail.BANK_TRANSFER, true, "cash-ron"))));
        return new DeriveConfig(registry,
            RuleSet.compile("t.yaml", new RuleSet.File(List.of("FOO"), List.of())),
            rules, Profiles.empty(), "sha256:cfg");
    }

    private static Fact fact(long n, String id, String account, LocalDate date, long amount,
                             long balance, String raw) {
        return new Fact(n, id, account, date, amount, balance, raw, null, 0,
            Observation.POSTED, "test", Provenance.BANK, null, "test/1", ASOF);
    }

    /** A wallet count: a $0 fact whose balance is what the person counted. */
    private static Fact count(long n, String account, LocalDate date, long balance) {
        return fact(n, "count-" + n, account, date, 0, balance, "Wallet count");
    }

    private static CashMonth row(Derivation d, String account, YearMonth month) {
        return d.cashMonth().stream()
            .filter(m -> m.accountRef().equals(account) && m.month().equals(month))
            .findFirst().orElseThrow(() -> new AssertionError("no " + account + " row for " + month
                + ": " + d.cashMonth()));
    }

    @Test
    void anAtmWithdrawalLeavesCashUnrecorded() {
        // A month with only an ATM withdrawal: cash left the wallet and nobody recorded it.
        Derivation d = Derive.derive(List.of(
            fact(1, "atm", "ing-savings", LocalDate.of(2026, 9, 10), -10000, 0, "ATM WDL 123")),
            List.of(), config(), ASOF);

        CashMonth sep = row(d, "cash-ron", YearMonth.of(2026, 9));
        assertEquals(10000, sep.inFromBank());
        assertEquals(0, sep.outToBank());
        assertEquals(0, sep.recordedOut());
        assertEquals(0, sep.recordedIn());
        assertNull(sep.heldOpen());
        assertNull(sep.heldClose());
        assertEquals(0, sep.heldDelta(), "no counts: the delta is 0, not the whole balance");
        assertEquals(10000, sep.unrecorded(), "in from the bank and never recorded: positive");
        assertFalse(sep.open());
        // The month range runs through the asOf month.
        assertTrue(row(d, "cash-ron", YearMonth.of(2026, 10)).open());
    }

    @Test
    void cashFoundGivesANegativeMonth() {
        // A count higher than the movement explains: cash appeared that nobody recorded.
        Derivation d = Derive.derive(List.of(
            count(1, "cash-ron", LocalDate.of(2026, 8, 1), 10000),
            fact(2, "spend", "cash-ron", LocalDate.of(2026, 9, 5), -1000, 0, "MANUAL LUNCH"),
            count(3, "cash-ron", LocalDate.of(2026, 9, 30), 15000)),
            List.of(), config(), ASOF);

        CashMonth sep = row(d, "cash-ron", YearMonth.of(2026, 9));
        assertEquals(10000, sep.heldOpen(), "the August baseline");
        assertEquals(15000, sep.heldClose(), "the September count");
        assertEquals(5000, sep.heldDelta(), "the wallet gained 5000 it cannot explain");
        assertEquals(1000, sep.recordedOut());
        assertEquals(-6000, sep.unrecorded(), "1000 recorded out, 5000 appeared unrecorded");
    }

    @Test
    void aFullyRecordedMonthIsZero() {
        Derivation d = Derive.derive(List.of(
            count(1, "cash-ron", LocalDate.of(2026, 8, 1), 0),
            fact(2, "atm", "ing-savings", LocalDate.of(2026, 9, 10), -10000, 0, "ATM WDL 123"),
            fact(3, "spend", "cash-ron", LocalDate.of(2026, 9, 12), -10000, 0, "MANUAL SPEND"),
            count(4, "cash-ron", LocalDate.of(2026, 9, 30), 0)),
            List.of(), config(), ASOF);

        CashMonth sep = row(d, "cash-ron", YearMonth.of(2026, 9));
        assertEquals(0, sep.heldDelta());
        assertEquals(10000, sep.inFromBank());
        assertEquals(10000, sep.recordedOut());
        assertEquals(0, sep.unrecorded(), "the ATM went in and was recorded out: balanced");
    }

    @Test
    void theFirstCountOnlySetsTheBaseline() {
        // D-4: before the first count the delta is 0, so the count's balance is never dumped into
        // one month. The first count and the movement share the month.
        Derivation d = Derive.derive(List.of(
            count(1, "cash-ron", LocalDate.of(2026, 9, 1), 50000),
            fact(2, "atm", "ing-savings", LocalDate.of(2026, 9, 10), -10000, 0, "ATM WDL 123")),
            List.of(), config(), ASOF);

        CashMonth sep = row(d, "cash-ron", YearMonth.of(2026, 9));
        assertNull(sep.heldOpen(), "nothing strictly before the month");
        assertEquals(50000, sep.heldClose());
        assertEquals(0, sep.heldDelta(), "the first count only sets a baseline");
        assertEquals(sep.inFromBank(), sep.unrecorded(), "the movement is the whole figure");
        assertEquals(10000, sep.unrecorded(), "never the count's 50000");
    }

    @Test
    void onlyTheAsOfMonthIsOpen() {
        Derivation d = Derive.derive(List.of(
            fact(1, "spend", "cash-ron", LocalDate.of(2026, 8, 20), -1000, 0, "MANUAL SPEND")),
            List.of(), config(), Instant.parse("2026-09-15T00:00:00Z"));

        CashMonth aug = row(d, "cash-ron", YearMonth.of(2026, 8));
        CashMonth sep = row(d, "cash-ron", YearMonth.of(2026, 9));
        assertFalse(aug.open());
        assertTrue(sep.open());
        assertEquals(2, d.cashMonth().stream().filter(m -> m.accountRef().equals("cash-ron")).count(),
            "August through the asOf month, inclusive");
    }

    @Test
    void aRetireReflowsTheMonth() {
        List<Fact> facts = List.of(
            fact(1, "atm", "ing-savings", LocalDate.of(2026, 9, 10), -10000, 0, "ATM WDL 123"),
            fact(2, "spend", "cash-ron", LocalDate.of(2026, 9, 5), -5000, 0, "MANUAL SPEND"));

        Derivation before = Derive.derive(facts, List.of(), config(), ASOF);
        CashMonth beforeSep = row(before, "cash-ron", YearMonth.of(2026, 9));
        assertEquals(5000, beforeSep.recordedOut());
        assertEquals(5000, beforeSep.unrecorded());

        Derivation after = Derive.derive(facts,
            List.of(new Decision.Retire(3, "spend", "not mine", Actor.USER, "ron", ASOF)), config(), ASOF);
        CashMonth afterSep = row(after, "cash-ron", YearMonth.of(2026, 9));
        assertEquals(0, afterSep.recordedOut(), "the retired spend drops out");
        assertEquals(10000, afterSep.unrecorded(), "the whole withdrawal is now unrecorded");
    }

    @Test
    void countToCountGivesTheDeferredDelta() {
        Derivation d = Derive.derive(List.of(
            count(1, "cash-ron", LocalDate.of(2026, 7, 1), 1000),
            fact(2, "atm", "ing-savings", LocalDate.of(2026, 7, 15), -2000, 0, "ATM WDL 123"),
            count(3, "cash-ron", LocalDate.of(2026, 8, 31), 4000)),
            List.of(), config(), ASOF);

        CashMonth jul = row(d, "cash-ron", YearMonth.of(2026, 7));
        assertEquals(0, jul.heldDelta(), "the first count is still only a baseline");
        CashMonth aug = row(d, "cash-ron", YearMonth.of(2026, 8));
        assertEquals(1000, aug.heldOpen());
        assertEquals(4000, aug.heldClose());
        assertEquals(3000, aug.heldDelta(), "count to count");
    }
}
