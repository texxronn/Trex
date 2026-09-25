package trex.ingest.cba;

import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.Test;
import trex.core.Candidate;
import trex.core.Ids;
import trex.core.OccCandidate;
import trex.core.Occurrence;
import trex.ingest.Parsed;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC §7 test 1 (CommBank PDF part) and SPEC §4 cba-pdf: extraction, the frozen normalisation,
 * and the page-furniture rule.
 * <p>
 * The fixture is a synthetic two-page statement shaped like CommBank's, deliberately built so a
 * description's continuation line is the last line before the page footer — the case that
 * silently corrupts ids if the footer is not skipped whole.
 */
class CbaPdfTest {

    static Path resource(String name) {
        try {
            return Path.of(CbaPdfTest.class.getResource("/" + name).toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void pdfSliceMatchesGoldenFileEveryRun() throws Exception {
        List<Candidate> golden = trex.journal.Json.mapper().readValue(
            resource("cba-slice.pdf.golden.json").toFile(), new TypeReference<List<Candidate>>() {});
        for (int run = 0; run < 3; run++) {
            Parsed parsed = CbaPdf.parse(resource("cba-slice.pdf"), "cba-smartaccess");
            assertTrue(parsed.valid(), parsed.badRows().toString());
            assertEquals(golden, parsed.candidates());
        }
    }

    /**
     * The point of the whole source type: the same transaction read from the PDF and from the
     * CSV mints the same external_id, so the two sources dedup rather than double-count.
     */
    @Test
    void pdfAndCsvMintIdenticalIdsForTheSameTransactions() {
        List<Candidate> fromPdf = CbaPdf.parse(resource("cba-slice.pdf"), "cba-smartaccess").candidates();
        List<Candidate> fromCsv = CbaCsv.parse(resource("cba-slice.csv"), "cba-smartaccess").candidates();
        assertEquals(ids(fromCsv), ids(fromPdf));
        assertEquals(4, ids(fromPdf).size());
    }

    private static List<String> ids(List<Candidate> candidates) {
        return Occurrence.assignOcc(candidates).stream()
            .map(oc -> Ids.externalId(Ids.strategyFor(oc)))
            .sorted()
            .toList();
    }

    /** Continuation lines belong to the row above, joined with exactly one space. */
    @Test
    void continuationLinesJoinTheDescription() {
        List<Candidate> c = CbaPdf.parse(resource("cba-slice.pdf"), "cba-smartaccess").candidates();
        assertEquals("Direct Debit 000000 HEALTH FUND 000000000000", c.get(1).rawDescription());
        assertEquals("Transfer from xx0000 CommBank app house expense", c.get(3).rawDescription());
    }

    /**
     * The footer is three lines and the table header restarts the table. Skipping only the first
     * footer line appends "While this letter is accurate…" to whichever description straddles the
     * page break — a wrong id on exactly the rows at page boundaries.
     */
    @Test
    void pageFurnitureNeverLeaksIntoADescription() {
        List<Candidate> c = CbaPdf.parse(resource("cba-slice.pdf"), "cba-smartaccess").candidates();
        assertEquals("Transfer To Alex Carter CommBank App initial payment", c.get(2).rawDescription());
        c.forEach(x -> {
            assertFalse(x.rawDescription().contains("Created"), x.rawDescription());
            assertFalse(x.rawDescription().contains("While this letter"), x.rawDescription());
            assertFalse(x.rawDescription().contains("Page"), x.rawDescription());
            assertFalse(x.rawDescription().contains("Account Number"), x.rawDescription());
        });
    }

    @Test
    void amountsLoseTheirDollarSignAndSeparatorsButKeepExactCents() {
        List<Candidate> c = CbaPdf.parse(resource("cba-slice.pdf"), "cba-smartaccess").candidates();
        assertEquals(-7, c.get(0).amount());          // -$0.07
        assertEquals(100000, c.get(3).amount());      // $1,000.00
        assertEquals(227743, c.get(3).balance());     // $2,277.43
    }

    @Test
    void noRowCarriesAReceipt() {
        CbaPdf.parse(resource("cba-slice.pdf"), "cba-smartaccess").candidates()
            .forEach(c -> assertNull(c.receipt()));
    }

    /** Whitespace is collapsed, so a wider column or an extra space cannot shift an id. */
    @Test
    void descriptionWhitespaceIsNormalised() {
        Parsed p = CbaPdf.parse("""
            Date Transaction details Amount Balance
            01 Jun 2026   Debit     Excess    Interest   -$0.07 $1,948.10
            """, "stmt.pdf", "cba-smartaccess");
        assertTrue(p.valid(), p.badRows().toString());
        assertEquals("Debit Excess Interest", p.candidates().getFirst().rawDescription());
    }

    /** Month names are English regardless of the machine's locale. */
    @Test
    void monthNamesAreParsedInEnglish() {
        Parsed p = CbaPdf.parse("""
            Date Transaction details Amount Balance
            03 Mar 2026 Something -$1.00 $2.00
            """, "stmt.pdf", "cba-smartaccess");
        assertTrue(p.valid(), p.badRows().toString());
        assertEquals("2026-03-03", p.candidates().getFirst().date().toString());
    }

    @Test
    void aFileWithNoTransactionsIsReported() {
        Parsed p = CbaPdf.parse("Dear customer,\nhere is your letter.\n", "stmt.pdf", "cba-smartaccess");
        assertFalse(p.valid());
        assertTrue(p.badRows().getFirst().reason().contains("no transactions found"));
    }
}
