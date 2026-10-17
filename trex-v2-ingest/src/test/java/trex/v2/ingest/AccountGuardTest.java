package trex.v2.ingest;

import org.junit.jupiter.api.Test;
import trex.v2.core.Actor;
import trex.v2.core.Decision;
import trex.v2.core.Fact;
import trex.v2.core.Observation;
import trex.v2.core.Provenance;
import trex.v2.ingest.AccountGuard.Kind;
import trex.v2.ingest.AccountGuard.Verdict;
import trex.v2.ingest.ing.IngCsv;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.SortedMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The account guard (V2-ACCOUNT-GUARD-PLAN.md): overlap, connection, contradiction and the verdict order. */
class AccountGuardTest {

    private static final Instant AT = Instant.parse("2026-09-30T00:00:00Z");
    private static long n = 0;

    private static LocalDate d(int day) {
        return LocalDate.of(2026, 7, day);
    }

    private static Fact fact(String account, int day, long amount, long balance, String raw,
                             Observation obs, Provenance prov) {
        long id = ++n;
        return new Fact(id, "id-" + id, account, d(day), amount, balance, raw, null, 0, obs, "ing-csv",
            prov, null, "ing-csv/1", AT);
    }

    private static Fact fact(String account, int day, long amount, long balance) {
        return fact(account, day, amount, balance, "row " + day + " " + amount, Observation.POSTED, Provenance.BANK);
    }

    private static FactDraft draft(int day, long amount, long balance, String raw) {
        return new FactDraft("x", d(day), amount, balance, raw, null, Observation.POSTED, "ing-csv",
            Provenance.BANK, null, null, null);
    }

    private static FactDraft draft(int day, long amount, long balance) {
        return draft(day, amount, balance, "file row");
    }

    private static SortedMap<String, List<Fact>> current(Fact... facts) {
        return AccountGuard.current(List.of(facts), List.of());
    }

    @Test
    void overlapFits() {
        var cur = current(fact("a", 1, -100, 900), fact("a", 2, -100, 800), fact("a", 3, -100, 700));
        List<FactDraft> file = List.of(draft(2, -100, 800), draft(3, -100, 700), draft(4, -100, 600));
        Verdict v = AccountGuard.check(file, "a", cur);
        assertEquals(Kind.FITS, v.kind());
        assertFalse(v.refuses());
        assertEquals("fits", v.eventValue());
        var s = v.scores().getFirst();
        assertEquals(2, s.overlapRows());
        assertEquals(d(2), s.overlapFrom());
        assertEquals(d(3), s.overlapTo());
        assertFalse(s.contradicts());
        assertNull(s.contradictFrom());
    }

    @Test
    void connectionAloneFits() {
        var cur = current(fact("a", 1, -100, 900), fact("a", 2, -100, 800));
        List<FactDraft> file = List.of(draft(5, -100, 700), draft(6, -100, 600));
        var s = AccountGuard.score(file, cur).getFirst();
        assertFalse(s.overlaps());
        assertTrue(s.connects());
        assertEquals(Kind.FITS, AccountGuard.check(file, "a", cur).kind());
    }

    @Test
    void connectionNeedsTheNewestDayAtOrBeforeTheFileStart() {
        var cur = current(fact("a", 1, -100, 900), fact("a", 2, -100, 800));
        // opening 900 matches day 1, but day 2 is the newest day before the file starts
        List<FactDraft> file = List.of(draft(5, -100, 800), draft(6, -100, 700));
        assertFalse(AccountGuard.score(file, cur).getFirst().connects());
    }

    @Test
    void elsewhereWhenExactlyOneOtherFits() {
        var cur = current(fact("a", 1, -100, 900), fact("a", 2, -100, 800),
            fact("b", 2, -50, 5000), fact("b", 3, -50, 4950));
        List<FactDraft> file = List.of(draft(2, -100, 800), draft(3, -100, 700));
        Verdict v = AccountGuard.check(file, "b", cur);
        assertEquals(Kind.ELSEWHERE, v.kind());
        assertEquals("a", v.elsewhere());
        assertTrue(v.refuses());
        assertEquals("elsewhere:a", v.eventValue());
        assertEquals(Optional.of("a"), AccountGuard.suggestion(v.scores()));
    }

    @Test
    void twoOthersFitIsUnprovenWithBothCandidates() {
        var cur = current(fact("a", 2, -100, 800), fact("b", 2, -100, 800), fact("c", 1, -7, 12));
        List<FactDraft> file = List.of(draft(2, -100, 800), draft(3, -1, 799));
        Verdict v = AccountGuard.check(file, "c", cur);
        assertEquals(Kind.UNPROVEN, v.kind());
        assertEquals(List.of("a", "b"), v.candidates());
        assertFalse(v.refuses());
        assertEquals("unproven", v.eventValue());
        assertEquals(Optional.empty(), AccountGuard.suggestion(v.scores()));
    }

    @Test
    void differentRowsOnADayInTheSpanContradict() {
        var cur = current(fact("a", 2, -999, 4321), fact("a", 3, -1, 4320));
        List<FactDraft> file = List.of(draft(2, -100, 800), draft(3, -100, 700));
        Verdict v = AccountGuard.check(file, "a", cur);
        assertEquals(Kind.CONTRADICTS, v.kind());
        assertTrue(v.refuses());
        assertEquals("contradicts", v.eventValue());
        var s = v.scores().getFirst();
        assertEquals(d(2), s.contradictFrom());
        assertEquals(d(3), s.contradictTo());
    }

    @Test
    void aGapWithNothingFittingIsUnprovenWithNoCandidates() {
        var cur = current(fact("a", 1, -100, 900));
        List<FactDraft> file = List.of(draft(10, -100, 500), draft(11, -100, 400));
        Verdict v = AccountGuard.check(file, "a", cur);
        assertEquals(Kind.UNPROVEN, v.kind());
        assertTrue(v.candidates().isEmpty());
        assertFalse(v.refuses());
    }

    @Test
    void emptyChosenIsNewUnlessAnotherAccountFits() {
        var cur = current(fact("a", 2, -100, 800));
        List<FactDraft> fits = List.of(draft(2, -100, 800), draft(3, -100, 700));
        Verdict elsewhere = AccountGuard.check(fits, "fresh", cur);
        assertEquals(Kind.ELSEWHERE, elsewhere.kind());
        assertEquals("a", elsewhere.elsewhere());

        List<FactDraft> stranger = List.of(draft(20, -5, 77));
        Verdict none = AccountGuard.check(stranger, "fresh", cur);
        assertEquals(Kind.NEW, none.kind());
        assertEquals("new", none.eventValue());
        assertFalse(none.refuses());
    }

    @Test
    void emptyFileIsNeverRefused() {
        var cur = current(fact("a", 2, -100, 800));
        assertEquals(Kind.UNPROVEN, AccountGuard.check(List.of(), "a", cur).kind());
        assertEquals(Kind.NEW, AccountGuard.check(List.of(), "z", cur).kind());
    }

    @Test
    void zeroIsNotEvidence() {
        var cur = current(fact("a", 2, -100, 0), fact("a", 3, 100, 0));
        // zero-balance rows match but do not count as overlap
        List<FactDraft> zeros = List.of(draft(2, -100, 0), draft(3, 100, 0));
        assertFalse(AccountGuard.score(zeros, cur).getFirst().overlaps());

        // a file opening of 0 does not connect to an account that stood at 0
        var cur2 = current(fact("a", 1, -100, 0));
        List<FactDraft> fromZero = List.of(draft(5, 100, 100), draft(6, 100, 200));
        assertFalse(AccountGuard.score(fromZero, cur2).getFirst().connects());
    }

    @Test
    void currentIgnoresClosedPendingAndAuthoredFacts() {
        Fact retired = fact("a", 1, -1, 10);
        Fact superseded = fact("a", 2, -2, 20);
        Fact kept = fact("a", 3, -3, 30);
        Fact pending = fact("a", 4, -4, 40, "p", Observation.PENDING, Provenance.BANK);
        Fact authored = fact("a", 5, -5, 50, "m", Observation.POSTED, Provenance.AUTHORED);
        List<Fact> all = List.of(retired, superseded, kept, pending, authored);
        List<Decision> decisions = List.of(
            new Decision.Retire(10, retired.externalId(), "r", Actor.USER, "u", AT),
            new Decision.Supersede(11, superseded.externalId(), "other", "r", Actor.USER, "u", AT));
        assertEquals(List.of(kept), AccountGuard.current(all, decisions).get("a"));

        List<Decision> revoked = new ArrayList<>(decisions);
        revoked.add(new Decision.Revoke(12, 10, "undo", Actor.USER, "u", AT));
        assertEquals(List.of(retired, kept), AccountGuard.current(all, revoked).get("a"));
    }

    @Test
    void descriptionIsNotPartOfTheMatchKey() {
        var cur = current(fact("a", 2, -100, 800, "OLD TEXT", Observation.POSTED, Provenance.BANK));
        List<FactDraft> file = List.of(draft(2, -100, 800, "Re-worded text"));
        assertEquals(1, AccountGuard.score(file, cur).getFirst().overlapRows());
    }

    @Test
    void shuffledInputsGiveTheSameVerdict() {
        List<Fact> facts = new ArrayList<>(List.of(
            fact("a", 1, -100, 900), fact("a", 2, -100, 800), fact("b", 2, -50, 5000),
            fact("b", 3, -50, 4950), fact("c", 2, -100, 800)));
        List<FactDraft> file = new ArrayList<>(List.of(draft(2, -100, 800), draft(3, -100, 700), draft(4, -1, 699)));
        Verdict expected = AccountGuard.check(file, "b", AccountGuard.current(facts, List.of()));
        for (int seed = 0; seed < 10; seed++) {
            Collections.shuffle(facts, new Random(seed));
            Collections.shuffle(file, new Random(seed));
            assertEquals(expected, AccountGuard.check(file, "b", AccountGuard.current(facts, List.of())));
        }
    }

    // --- the ING pair: three accounts sharing one CSV format ---

    private static final String HEADER = "Date,Description,Credit,Debit,Balance\n";

    private static List<FactDraft> parse(String account, String body) {
        Parsed p = new IngCsv().parse((HEADER + body).getBytes(StandardCharsets.UTF_8), "f.csv", account);
        assertTrue(p.clean());
        return p.candidates();
    }

    private static List<Fact> asFacts(List<FactDraft> drafts) {
        List<Fact> out = new ArrayList<>();
        for (FactDraft f : drafts) {
            out.add(new Fact(++n, "id-" + n, f.accountRef(), f.date(), f.amount(), f.balance(), f.rawDescription(),
                f.receipt(), 0, f.observation(), f.sourceType(), f.provenance(), null, "ing-csv/1", AT));
        }
        return out;
    }

    @Test
    void ingFileForOrangeIsRefusedUnderSalary() {
        List<Fact> facts = new ArrayList<>();
        facts.addAll(asFacts(parse("orange", """
            01/07/2026,"Coffee",,-4.50,995.50
            02/07/2026,"Lunch",,-10.00,985.50
            03/07/2026,"Books",,-20.00,965.50
            """)));
        facts.addAll(asFacts(parse("salary", """
            01/07/2026,"Pay",3000.00,,3000.00
            02/07/2026,"Rent",,-1200.00,1800.00
            """)));
        var cur = AccountGuard.current(facts, List.of());
        List<FactDraft> next = parse("salary", """
            03/07/2026,"Books",,-20.00,965.50
            04/07/2026,"Bus",,-3.00,962.50
            """);

        Verdict wrong = AccountGuard.check(next, "salary", cur);
        assertEquals(Kind.ELSEWHERE, wrong.kind());
        assertEquals("orange", wrong.elsewhere());
        assertEquals("elsewhere:orange", wrong.eventValue());
        assertEquals(Kind.FITS, AccountGuard.check(next, "orange", cur).kind());
        assertEquals(Optional.of("orange"), AccountGuard.suggestion(AccountGuard.score(next, cur)));
    }
}
