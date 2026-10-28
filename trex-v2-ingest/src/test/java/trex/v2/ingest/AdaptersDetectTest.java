package trex.v2.ingest;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Detecting a file's source type: the adapters that parse it whole, with no bad row. */
class AdaptersDetectTest {

    private static final String ING = """
        Date,Description,Credit,Debit,Balance
        01/07/2026,"COLES 1234",,-10.00,990.00
        """;

    private static final String BW =
        "BSB Number,Account Number,Transaction Date,Narration,Cheque,Debit,Credit,Balance,Transaction Type\n"
            + "062-000,12345678,01/07/2026,COLES 1234,,45.00,,1000.00,EFTPOS\n";

    private static final String CBA = """
        01/07/2026,-75.00,COLES 1234,1000.00
        02/07/2026,+1000.00,SALARY ACME,2000.00
        """;

    @Test
    void eachCsvShapeIsDetectedAsExactlyItsOwnType() {
        assertEquals(List.of("ing-csv"), detect(ING));
        assertEquals(List.of("bw-csv"), detect(BW));
        assertEquals(List.of("cba-csv"), detect(CBA));
    }

    @Test
    void aFileNoAdapterParsesWholeIsNotDetected() {
        // One bad row is enough: detection never guesses past a row an ingest would reject.
        assertEquals(List.of(), detect(ING + "02/07/2026,\"CAFE\",,oops,985.00\n"));
        assertEquals(List.of(), detect("hello, world\n"));
        assertEquals(List.of(), detect(""));
    }

    @Test
    void aHeaderWithNoRowsIsNotDetected() {
        assertEquals(List.of(), detect("Date,Description,Credit,Debit,Balance\n"));
    }

    @Test
    void bytesThatAreNotAPdfDoNotBreakDetection() {
        // CbaPdf throws on non-PDF bytes; that is "not this type", not a failure of detection.
        assertEquals(List.of(), detect("%PDF-1.7 truncated"));
    }

    private static List<String> detect(String text) {
        return Adapters.detect(text.getBytes(StandardCharsets.UTF_8), "f.csv");
    }
}
