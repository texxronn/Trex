package trex.v2.ingest.bw;

import org.junit.jupiter.api.Test;
import trex.v2.core.Observation;
import trex.v2.ingest.Parsed;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The BankWest adapter (V2-PROPOSAL.md §12.3): sign inferred per file, pending recorded. */
class BwCsvTest {

    private static final String HEADER =
        "BSB Number,Account Number,Transaction Date,Narration,Cheque,Debit,Credit,Balance,Transaction Type\n";

    @Test
    void normalisesPositiveDebitsAndRecordsPendingAuthorisations() {
        String file = HEADER
            + "062-000,12345678,01/07/2026,COLES 1234,,45.00,,1000.00,EFTPOS\n"
            + "062-000,12345678,02/07/2026,AUTHORISATION ONLY BP FUEL 1234,,49.95,,950.05,EFTPOS\n";
        Parsed parsed = new BwCsv().parse(file.getBytes(StandardCharsets.UTF_8), "f.csv", "bw-credit-card");
        assertTrue(parsed.clean());
        assertEquals(2, parsed.candidates().size());
        assertEquals(-4500L, parsed.candidates().get(0).amount(), "a positive debit is normalised negative");
        assertEquals(Observation.POSTED, parsed.candidates().get(0).observation());
        assertEquals(Observation.PENDING, parsed.candidates().get(1).observation(),
            "a pending authorisation is recorded, not skipped");
        assertEquals(-4995L, parsed.candidates().get(1).amount());
    }

    @Test
    void negativeDebitsAreAcceptedToo() {
        String file = HEADER
            + "062-000,12345678,01/07/2026,COLES 1234,,-45.00,,1000.00,EFTPOS\n";
        Parsed parsed = new BwCsv().parse(file.getBytes(StandardCharsets.UTF_8), "f.csv", "bw-credit-card");
        assertTrue(parsed.clean());
        assertEquals(-4500L, parsed.candidates().getFirst().amount());
    }

    @Test
    void aFileThatMixesDebitSignsIsRejected() {
        String file = HEADER
            + "062-000,12345678,01/07/2026,ONE,,45.00,,1000.00,EFTPOS\n"
            + "062-000,12345678,02/07/2026,TWO,,-10.00,,990.00,EFTPOS\n";
        Parsed parsed = new BwCsv().parse(file.getBytes(StandardCharsets.UTF_8), "f.csv", "bw-credit-card");
        assertFalse(parsed.clean());
        assertTrue(parsed.candidates().isEmpty());
    }
}
