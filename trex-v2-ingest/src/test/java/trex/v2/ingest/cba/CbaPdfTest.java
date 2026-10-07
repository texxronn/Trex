package trex.v2.ingest.cba;

import org.junit.jupiter.api.Test;
import trex.v2.ingest.Parsed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The CommBank PDF adapter's parsing rules (V2-PROPOSAL.md §12.1), tested on extracted text. */
class CbaPdfTest {

    private static final String TEXT = """
        CommBank
        123 Main Street
        Date Transaction details Amount Balance
        01 Jan 2026  Debit Excess Interest  -$0.09  $1,296.21
        02 Jan 2026  COLES 1234  -$10.00  $1,286.21
        continued detail line
        Created 03/01/26
        """;

    @Test
    void extractsTransactionsAndJoinsContinuationLines() {
        Parsed parsed = CbaPdf.parse(TEXT, "statement.pdf", "cba-netsaver");
        assertTrue(parsed.clean(), parsed.bad().toString());
        assertEquals(2, parsed.candidates().size());
        assertEquals(-9L, parsed.candidates().get(0).amount());
        assertEquals(129621L, parsed.candidates().get(0).balance());
        assertEquals("COLES 1234 continued detail line", parsed.candidates().get(1).rawDescription(),
            "a line inside the table continues the row above it");
        assertEquals(-1000L, parsed.candidates().get(1).amount());
    }
}
