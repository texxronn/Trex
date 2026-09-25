package trex.ingest.ing;

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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SPEC §7 tests 1 and 3 (ING parts), SPEC §4 parsing and whole-file validation. */
class IngCsvTest {

    static Path resource(String name) {
        try {
            return Path.of(IngCsvTest.class.getResource("/" + name).toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void ingSliceMatchesGoldenFileEveryRun() throws Exception {
        List<Candidate> golden = trex.journal.Json.mapper().readValue(resource("ing-slice.golden.json").toFile(),
            new TypeReference<List<Candidate>>() {});
        for (int run = 0; run < 3; run++) {
            Parsed parsed = IngCsv.parse(resource("ing-slice.csv"), "ing-savings");
            assertTrue(parsed.valid(), parsed.badRows().toString());
            assertEquals(golden, parsed.candidates());
        }
    }

    @Test
    void debitsNegativeCreditsPositive() {
        List<Candidate> c = IngCsv.parse(resource("ing-slice.csv"), "ing-savings").candidates();
        assertTrue(c.get(0).amount() < 0);
        assertTrue(c.get(1).amount() < 0);
        assertTrue(c.get(3).amount() > 0);
        assertTrue(c.get(4).amount() > 0);
    }

    @Test
    void creditPlusDebitSumsWhenBothPresent() {
        Parsed p = IngCsv.parse("Date,Description,Credit,Debit,Balance\n01/06/2026,Adj,10.00,-2.50,7.50\n", "f.csv", "acc");
        assertEquals(750, p.candidates().getFirst().amount());
    }

    @Test
    void anyBadValueInvalidatesWholeFileAndReportsEveryBadRow() {
        String csv = """
            Date,Description,Credit,Debit,Balance
            01/06/2026,Good row,,-1.00,99.00
            02/06/2026,Three decimals,,-1.005,98.00
            2026-06-03,Bad date,,-1.00,97.00
            04/06/2026,No balance,,-1.00,
            05/06/2026,Positive debit,,1.00,96.00
            06/06/2026,Neither,,,96.00
            07/06/2026,Too,many,fields,1,2
            """;
        Parsed p = IngCsv.parse(csv, "stmt.csv", "ing-savings");
        assertFalse(p.valid());
        assertEquals(List.of(), p.candidates());
        assertEquals(List.of(3, 4, 5, 6, 7, 8), p.badRows().stream().map(BadRow::line).toList());
        assertEquals(new BadRow("stmt.csv", 3, "Debit", "-1.005", "not an exact amount in cents"), p.badRows().getFirst());
    }

    @Test
    void wrongHeaderRejected() {
        Parsed p = IngCsv.parse("Date,Details,Credit,Debit,Balance\n", "stmt.csv", "ing-savings");
        assertFalse(p.valid());
        assertEquals("header", p.badRows().getFirst().column());
    }

    @Test
    void quotedFieldsWithEmbeddedNewlineKeepRecordStartLine() {
        String csv = "Date,Description,Credit,Debit,Balance\n01/06/2026,\"two\nlines\",,-1.00,9.00\n02/06/2026,next,,-1.00,8.00\n";
        Parsed p = IngCsv.parse(csv, "f.csv", "acc");
        assertTrue(p.valid(), p.badRows().toString());
        assertEquals("two\nlines", p.candidates().get(0).rawDescription());
        assertEquals("row-2", p.candidates().get(0).candidateRef());
        assertEquals("row-4", p.candidates().get(1).candidateRef());
    }
}
