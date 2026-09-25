package trex.egress.firefly;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC §5.8. The mapping is pure, so all of this runs without an instance — which is the point:
 * every mistake in here is silent and would land 1751 times.
 */
class ProjectionTest {

    private static final AccountMap ACCOUNTS = new AccountMap(Map.of(
        "ing-salary", new AccountMap.Entry("ING Salary", AccountMap.Kind.ASSET, "3"),
        "ing-loan-offset", new AccountMap.Entry("ING Loan Offset", AccountMap.Kind.ASSET, "5"),
        "bw-credit-card", new AccountMap.Entry("BankWest Credit Card", AccountMap.Kind.LIABILITY, "9"),
        "ing-credit-card", new AccountMap.Entry("ING Credit Card", AccountMap.Kind.LIABILITY, "4")));

    @SuppressWarnings("unchecked")
    private static Map<String, Object> split(Projection.Posting p) {
        return ((List<Map<String, Object>>) p.body().get("transactions")).getFirst();
    }

    private static CanonicalEvent spend(String id, String account, long amount, String raw) {
        return line(1, id, account, null, amount, raw, TypeHint.WITHDRAWAL, EventState.EXTERNAL);
    }

    private static CanonicalEvent transfer(String id, String from, String to, long amount) {
        return line(1, id, from, to, amount, "Internal Transfer", TypeHint.TRANSFER, EventState.MATCHED);
    }

    private static CanonicalEvent line(long n, String id, String account, String to, long amount,
                                       String raw, TypeHint type, EventState state) {
        return new CanonicalEvent(n, id, account, to, "AUD", LocalDate.of(2026, 9, 1), amount, 0,
            raw, raw, type, null, null, null, state, Confidence.HIGH, List.of(), Provenance.BANK,
            "ing-csv", null, null, null, null, null, null, Instant.EPOCH);
    }

    // ---------------------------------------------------------------- unit selection

    /**
     * A TRANSFER line replaces its two legs. Posting the legs as well counts every internal
     * movement twice — silently, as a wrong total rather than an error.
     */
    @Test
    void legsAreNotProjectable() {
        assertTrue(Projection.projectable(transfer("TRF-1", "ing-salary", "bw-credit-card", 50000)));
        assertTrue(Projection.projectable(spend("a", "ing-salary", -1000, "COLES")));
        assertFalse(Projection.projectable(
            line(1, "leg", "ing-salary", null, -50000, "x", TypeHint.WITHDRAWAL, EventState.MATCHED)),
            "a matched leg is MATCHED, never EXTERNAL — that is what excludes it");
        assertFalse(Projection.projectable(
            line(1, "h", "ing-salary", null, -1000, "x", TypeHint.WITHDRAWAL, EventState.HELD)),
            "HELD is withheld until it is resolved");
    }

    // ---------------------------------------------------------------- the type matrix

    /**
     * The correction the dev instance forced. Firefly 6.7.3 rejects a transfer that crosses
     * between asset and liability, and 21 of the 30 transfers in the real journal do exactly that.
     */
    @Test
    void transferTypeComesFromTheAccountKinds() {
        assertEquals("transfer",
            split(Projection.of(transfer("t1", "ing-salary", "ing-loan-offset", 50000), "TRANSFER", "r", ACCOUNTS)).get("type"),
            "asset -> asset");
        assertEquals("transfer",
            split(Projection.of(transfer("t2", "bw-credit-card", "ing-credit-card", 50000), "TRANSFER", "r", ACCOUNTS)).get("type"),
            "liability -> liability, a balance transfer");
        assertEquals("withdrawal",
            split(Projection.of(transfer("t3", "ing-salary", "bw-credit-card", 50000), "TRANSFER", "r", ACCOUNTS)).get("type"),
            "asset -> liability: paying the card. A 'transfer' here is a 422.");
        assertEquals("deposit",
            split(Projection.of(transfer("t4", "bw-credit-card", "ing-salary", 50000), "TRANSFER", "r", ACCOUNTS)).get("type"),
            "liability -> asset: a refund from the card");
    }

    @Test
    void aTransferCarriesBothAccountIdsAndNoNames() {
        Map<String, Object> s = split(Projection.of(
            transfer("t", "ing-loan-offset", "bw-credit-card", 686000), "TRANSFER", "rev1", ACCOUNTS));
        assertEquals("5", s.get("source_id"));
        assertEquals("9", s.get("destination_id"));
        assertEquals("6860.00", s.get("amount"));
        assertFalse(s.containsKey("destination_name"), "ids only: a name would let Firefly guess");
    }

    // ---------------------------------------------------------------- spending and income

    @Test
    void spendingBecomesAWithdrawalToTheMerchantStem() {
        Map<String, Object> s = split(Projection.of(
            spend("a", "bw-credit-card", -3822, "AMAZON AU RETAIL   SYDNEY"), "SHOPPING", "rev1", ACCOUNTS));
        assertEquals("withdrawal", s.get("type"));
        assertEquals("9", s.get("source_id"));
        assertEquals("AMAZON AU RETAIL", s.get("destination_name"), "the stem, so one shop is one account");
        assertEquals("38.22", s.get("amount"));
    }

    @Test
    void incomeBecomesADepositFromTheStem() {
        Map<String, Object> s = split(Projection.of(
            spend("b", "ing-salary", 420000, "JPM Admin Serv A Payroll - Receipt 151009"), "SALARY", "rev1", ACCOUNTS));
        assertEquals("deposit", s.get("type"));
        assertEquals("JPM ADMIN SERV A PAYROLL", s.get("source_name"));
        assertEquals("3", s.get("destination_id"));
        assertEquals("4200.00", s.get("amount"));
    }

    // ---------------------------------------------------------------- our stamps

    /**
     * The cache is an accelerator, so everything needed to rebuild it has to be recoverable from
     * Firefly: the id from external_id, the last projected category from the tag, and n from notes.
     */
    @Test
    void everyPostingCarriesWhatARebuildNeeds() {
        Map<String, Object> s = split(Projection.of(
            spend("2a6cfc36840b971b", "bw-credit-card", -3822, "AMAZON AU RETAIL"), "SHOPPING", "83f272f8", ACCOUNTS));
        assertEquals("2a6cfc36840b971b", s.get("external_id"));
        assertEquals(List.of("trex", "trex-category:SHOPPING"), s.get("tags"));
        assertEquals("SHOPPING", s.get("category_name"), "Firefly reports on this; the tag is our stamp");
        assertTrue(((String) s.get("notes")).contains("n=1"));
        assertTrue(((String) s.get("notes")).contains("rules=83f272f8"));
        assertEquals("bw-credit-card", s.get("internal_reference"));
    }

    /** An uncategorised row is tagged as such: a missing tag looks like an egress that failed. */
    @Test
    void uncategorisedIsStillTagged() {
        Map<String, Object> s = split(Projection.of(
            spend("c", "ing-salary", -500, "SOMETHING ODD"), "UNCATEGORIZED", "r", ACCOUNTS));
        assertEquals(List.of("trex", "trex-category:UNCATEGORIZED"), s.get("tags"));
    }

    @Test
    void theGroupBodyCarriesTheSafetyFlags() {
        Projection.Posting p = Projection.of(spend("d", "ing-salary", -500, "X"), "SHOPPING", "r", ACCOUNTS);
        assertEquals(Boolean.TRUE, p.body().get("error_if_duplicate_hash"));
        assertEquals(Boolean.FALSE, p.body().get("apply_rules"), "trex is the single classifier (§5.6)");
        assertEquals("d", p.externalId());
    }

    // ---------------------------------------------------------------- money

    /** Firefly returns "38.220000000000"; we send exact 2dp from cents and never a float. */
    @Test
    void amountsAreExactFromCents() {
        assertEquals("0.01", Projection.amount(1));
        assertEquals("38.22", Projection.amount(-3822));
        assertEquals("1234567.89", Projection.amount(123456789));
        assertEquals("115507.08", Projection.amount(11550708));
    }

    // ---------------------------------------------------------------- refusals

    @Test
    void anUnmappedAccountIsRefusedRatherThanGuessed() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () ->
            Projection.of(spend("x", "cba-netsaver", -100, "X"), "SHOPPING", "r", ACCOUNTS));
        assertTrue(e.getMessage().contains("cba-netsaver"), e.getMessage());
    }

    /** A name the instance does not have — a rename. Never post into a guessed account. */
    @Test
    void anUnresolvedAccountIsRefused() {
        AccountMap unresolved = new AccountMap(Map.of(
            "ing-salary", new AccountMap.Entry("ING Salary", AccountMap.Kind.ASSET, null)));
        IllegalStateException e = assertThrows(IllegalStateException.class, () ->
            Projection.of(spend("x", "ing-salary", -100, "X"), "SHOPPING", "r", unresolved));
        assertTrue(e.getMessage().contains("ING Salary"), e.getMessage());
    }

    /**
     * SPEC §5.8. An ATTESTATION is trex's own bookkeeping, not a transaction — no money moved.
     * Its state is EXTERNAL like any settled row, so without an explicit exclusion it would post
     * to Firefly as a $0 withdrawal, once per attestation, forever.
     */
    @Test
    void anAttestationIsNeverProjected() {
        CanonicalEvent attestation = line(1, "a1", "cash-ron", null, 0,
            "Cash attestation", TypeHint.ATTESTATION, EventState.EXTERNAL);
        assertFalse(Projection.projectable(attestation),
            "an attestation would post as a $0 transaction");

        // ...while an ordinary cash purchase on the same account still projects.
        assertTrue(Projection.projectable(spend("p1", "cash-ron", -4000, "Market stall")));
    }
}
