package trex.ingress.bw;

import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.Test;
import trex.core.Candidate;
import trex.ingress.BadRow;
import trex.ingress.Parsed;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SPEC §7 test 1 (BankWest part), SPEC §4 bw-csv parsing, pending rows and whole-file validation. */
class BwCsvTest {

    static Path resource(String name) {
        try {
            return Path.of(BwCsvTest.class.getResource("/" + name).toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void bwSliceMatchesGoldenFileEveryRun() throws Exception {
        List<Candidate> golden = trex.journal.Json.mapper().readValue(resource("bw-slice.golden.json").toFile(),
            new TypeReference<List<Candidate>>() {});
        for (int run = 0; run < 3; run++) {
            Parsed parsed = BwCsv.parse(resource("bw-slice.csv"), "bw-credit-card");
            assertTrue(parsed.valid(), parsed.badRows().toString());
            assertEquals(golden, parsed.candidates());
        }
    }

    /** BankWest writes both money columns positive; the sign comes from which one is filled. */
    @Test
    void debitsBecomeNegativeAndCreditsPositive() {
        List<Candidate> c = BwCsv.parse(resource("bw-slice.csv"), "bw-credit-card").candidates();
        assertEquals(-999, c.getFirst().amount());       // 9.99 in the Debit column
        assertEquals(686000, c.get(3).amount());         // 6860.00 in the Credit column
    }

    /** Pending authorisations are skipped and reported; they never become candidates. */
    @Test
    void pendingAuthorisationIsSkippedNotIngested() {
        Parsed p = BwCsv.parse(resource("bw-slice.csv"), "bw-credit-card");
        assertTrue(p.valid(), p.badRows().toString());
        assertEquals(1, p.skipped().size());
        assertEquals(2, p.skipped().getFirst().line());
        assertTrue(p.skipped().getFirst().reason().contains("pending"));
        assertTrue(p.candidates().stream().noneMatch(c -> c.rawDescription().contains("AUTHORISATION ONLY")));
        assertEquals(5, p.candidates().size());
    }

    /** No receipts anywhere in this format, so every row takes content-hash identity (§2.5). */
    @Test
    void noRowCarriesAReceipt() {
        BwCsv.parse(resource("bw-slice.csv"), "bw-credit-card").candidates()
            .forEach(c -> assertNull(c.receipt()));
    }

    @Test
    void identicalRowsOnOneDayAreKeptSoOccCanSeparateThem() {
        List<Candidate> c = BwCsv.parse(resource("bw-slice.csv"), "bw-credit-card").candidates();
        List<Candidate> coffees = c.stream().filter(x -> x.rawDescription().startsWith("COFFEE HOUSE")).toList();
        assertEquals(2, coffees.size());
        assertEquals(coffees.get(0).date(), coffees.get(1).date());
        assertEquals(coffees.get(0).amount(), coffees.get(1).amount());
        assertEquals(coffees.get(0).rawDescription(), coffees.get(1).rawDescription());
    }

    @Test
    void narrationIsVerbatimIncludingItsInternalSpacing() {
        Candidate netflix = BwCsv.parse(resource("bw-slice.csv"), "bw-credit-card").candidates().getFirst();
        assertEquals("PAYPAL *NETFLIX AUS      4029357733   AU", netflix.rawDescription());
    }

    @Test
    void wrongHeaderRejected() {
        Parsed p = BwCsv.parse("Date,Description,Credit,Debit,Balance\n", "stmt.csv", "bw-credit-card");
        assertFalse(p.valid());
        assertEquals("header", p.badRows().getFirst().column());
    }

    @Test
    void badRowsInvalidateTheWholeFileAndAreAllReported() {
        String csv = """
            BSB Number,Account Number,Transaction Date,Narration,Cheque,Debit,Credit,Balance,Transaction Type
            ,1,31/02/2026,bad date,,1.00,,5.00,WDL
            ,1,15/09/2026,three decimals,,1.005,,5.00,WDL
            ,1,15/09/2026,both columns,,1.00,2.00,5.00,WDL
            ,1,15/09/2026,neither column,,,,5.00,WDL
            ,1,15/09/2026,no balance,,1.00,,,WDL
            """;
        Parsed p = BwCsv.parse(csv, "stmt.csv", "bw-credit-card");
        assertFalse(p.valid());
        assertTrue(p.candidates().isEmpty());
        assertEquals(List.of(2, 3, 4, 5, 6), p.badRows().stream().map(BadRow::line).distinct().toList());
    }

    // ---------------------------------------------------------------- two BankWest exports

    private static final String SIGNED_DEBITS = """
        BSB Number,Account Number,Transaction Date,Narration,Cheque Number,Debit,Credit,Balance,Transaction Type
        ,5229,15/09/2026,"PAYPAL *NETFLIX AUS      4029357733   AU",,-9.99,,-1185.43,WDL
        ,5229,14/09/2026,"BILL PAYMENT RECEIVED FROM ING",,,6860.00,10.90,DEP
        """;

    /** BankWest ships both spellings of the fifth column; the rest of the header stays strict. */
    @Test
    void bothChequeColumnSpellingsAreAccepted() {
        assertTrue(BwCsv.parse(SIGNED_DEBITS, "full.csv", "bw-credit-card").valid());
        assertFalse(BwCsv.parse(SIGNED_DEBITS.replace("Cheque Number", "Cheque Note"), "x.csv", "bw-credit-card")
            .valid());
    }

    /**
     * One export writes debits positive, another already negative. Assuming either would invert
     * every debit in a file of the other kind — and amount is hashed into identity (§2.4).
     */
    @Test
    void theDebitSignIsInferredPerFile() {
        List<Candidate> signed = BwCsv.parse(SIGNED_DEBITS, "full.csv", "bw-credit-card").candidates();
        assertEquals(-999, signed.get(0).amount());     // "-9.99" stays negative
        assertEquals(686000, signed.get(1).amount());   // credits are positive in both exports

        List<Candidate> positive = BwCsv.parse(resource("bw-slice.csv"), "bw-credit-card").candidates();
        assertEquals(-999, positive.getFirst().amount());   // "9.99" becomes negative
    }

    /** The same transaction in either export is the same transaction: same amount, same id. */
    @Test
    void bothExportsAgreeOnTheSameTransaction() {
        Candidate fromSigned = BwCsv.parse(SIGNED_DEBITS, "full.csv", "bw-credit-card").candidates().getFirst();
        Candidate fromPositive = BwCsv.parse(resource("bw-slice.csv"), "bw-credit-card").candidates().getFirst();
        assertEquals(fromPositive.date(), fromSigned.date());
        assertEquals(fromPositive.amount(), fromSigned.amount());
        assertEquals(fromPositive.rawDescription(), fromSigned.rawDescription());
    }

    /** Mixed signs are not a convention; there is no sound reading, so the file is refused. */
    @Test
    void aFileMixingDebitSignsIsRejected() {
        Parsed p = BwCsv.parse(SIGNED_DEBITS + ",5229,13/09/2026,\"POSITIVE ONE\",,39.39,,-1155.94,WDL\n",
            "mixed.csv", "bw-credit-card");
        assertFalse(p.valid());
        assertTrue(p.badRows().getFirst().reason().contains("mixes positive and negative debits"),
            p.badRows().toString());
    }
}
