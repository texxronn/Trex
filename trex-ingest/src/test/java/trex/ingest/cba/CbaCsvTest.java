package trex.ingest.cba;

import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.Test;
import trex.core.Candidate;
import trex.ingest.BadRow;
import trex.ingest.Parsed;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SPEC §7 test 1 (CommBank part), SPEC §4 cba-csv parsing: header-less, pre-signed amounts. */
class CbaCsvTest {

    static Path resource(String name) {
        try {
            return Path.of(CbaCsvTest.class.getResource("/" + name).toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void cbaSliceMatchesGoldenFileEveryRun() throws Exception {
        List<Candidate> golden = trex.journal.Json.mapper().readValue(resource("cba-slice.golden.json").toFile(),
            new TypeReference<List<Candidate>>() {});
        for (int run = 0; run < 3; run++) {
            Parsed parsed = CbaCsv.parse(resource("cba-slice.csv"), "cba-smartaccess");
            assertTrue(parsed.valid(), parsed.badRows().toString());
            assertEquals(golden, parsed.candidates());
        }
    }

    /** No header: the first line is a transaction, so candidateRef starts at row-1, not row-2. */
    @Test
    void theFirstLineIsData() {
        List<Candidate> c = CbaCsv.parse(resource("cba-slice.csv"), "cba-smartaccess").candidates();
        assertEquals(4, c.size());
        assertEquals("row-1", c.getFirst().candidateRef());
        assertEquals("Transfer To Alex Carter CommBank App initial payment", c.getFirst().rawDescription());
    }

    /** The amount arrives signed; the parser neither negates nor combines columns. */
    @Test
    void signsComeStraightFromTheFile() {
        List<Candidate> c = CbaCsv.parse(resource("cba-slice.csv"), "cba-smartaccess").candidates();
        assertEquals(-7500, c.get(0).amount());     // "-75.00"
        assertEquals(100000, c.get(1).amount());    // "+1000.00"
        assertEquals(-7, c.get(3).amount());        // "-0.07", the cent-level case
        assertEquals(220243, c.get(0).balance());   // "+2202.43"
    }

    @Test
    void noRowCarriesAReceipt() {
        CbaCsv.parse(resource("cba-slice.csv"), "cba-smartaccess").candidates()
            .forEach(c -> assertNull(c.receipt()));
    }

    /** A file of another type must fail on its first row, not be half-read. */
    @Test
    void anIngFileFedAsCbaIsRejected() {
        Parsed p = CbaCsv.parse(resource("ing-slice.csv"), "cba-smartaccess");
        assertFalse(p.valid());
        assertTrue(p.candidates().isEmpty());
        assertTrue(p.badRows().getFirst().reason().contains("expected 4 fields"), p.badRows().toString());
    }

    /** The mistake someone will actually make: a file that has a header. */
    @Test
    void aHeaderRowIsNamedAsSuch() {
        Parsed p = CbaCsv.parse("Date,Amount,Description,Balance\n14/09/2026,\"-75.00\",\"x\",\"+1.00\"\n",
            "stmt.csv", "cba-smartaccess");
        assertFalse(p.valid());
        assertTrue(p.badRows().getFirst().reason().contains("looks like a header"), p.badRows().toString());
    }

    @Test
    void badRowsInvalidateTheWholeFileAndAreAllReported() {
        String csv = """
            31/02/2026,"-1.00","bad date","+1.00"
            15/09/2026,"-1.005","three decimals","+1.00"
            15/09/2026,"","no amount","+1.00"
            15/09/2026,"-1.00","no balance",""
            15/09/2026,"-1.00","too many fields","+1.00","extra"
            """;
        Parsed p = CbaCsv.parse(csv, "stmt.csv", "cba-smartaccess");
        assertFalse(p.valid());
        assertTrue(p.candidates().isEmpty());
        assertEquals(List.of(1, 2, 3, 4, 5), p.badRows().stream().map(BadRow::line).distinct().toList());
    }

    @Test
    void anEmptyFileIsReportedRatherThanAcceptedAsZeroRows() {
        Parsed p = CbaCsv.parse("", "stmt.csv", "cba-smartaccess");
        assertFalse(p.valid());
        assertEquals("no rows", p.badRows().getFirst().reason());
    }
}
