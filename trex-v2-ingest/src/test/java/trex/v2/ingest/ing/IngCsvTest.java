package trex.v2.ingest.ing;

import org.junit.jupiter.api.Test;
import trex.v2.core.Observation;
import trex.v2.ingest.Parsed;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The ING CSV adapter (V2-PROPOSAL.md §12.1). */
class IngCsvTest {

    private static final String SAMPLE = """
        Date,Description,Credit,Debit,Balance
        01/07/2026,"Fast Transfer to Orange - Receipt 770001",,-250.00,1750.00
        01/07/2026,"COFFEE CART, SYDNEY",,-4.50,1745.50
        02/07/2026,Salary Deposit - Receipt No 998877,2500.00,,4061.00
        """;

    @Test
    void parsesRowsReceiptsAndAmounts() {
        Parsed parsed = new IngCsv().parse(SAMPLE.getBytes(StandardCharsets.UTF_8), "f.csv", "ing-savings");
        assertTrue(parsed.clean());
        assertEquals(3, parsed.candidates().size());
        assertEquals(-25000L, parsed.candidates().get(0).amount());
        assertEquals("770001", parsed.candidates().get(0).receipt());
        assertEquals(175000L, parsed.candidates().get(0).balance());
        assertEquals(250000L, parsed.candidates().get(2).amount());
        assertEquals(Observation.POSTED, parsed.candidates().get(0).observation());
        assertEquals("ing-csv", parsed.candidates().get(0).sourceType());
    }

    @Test
    void oneBadRowRejectsTheWholeFile() {
        String bad = "Date,Description,Credit,Debit,Balance\n31/02/2026,Bad,,-2.00,0.00\n";
        Parsed parsed = new IngCsv().parse(bad.getBytes(StandardCharsets.UTF_8), "f.csv", "ing-savings");
        assertFalse(parsed.clean());
        assertTrue(parsed.candidates().isEmpty(), "nothing is sent until every row is clean");
        assertEquals("Date", parsed.bad().getFirst().field());
    }

    @Test
    void aWrongHeaderIsRejected() {
        Parsed parsed = new IngCsv().parse("a,b\n1,2\n".getBytes(StandardCharsets.UTF_8), "f.csv", "x");
        assertFalse(parsed.clean());
        assertEquals("header", parsed.bad().getFirst().field());
    }
}
