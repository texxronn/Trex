package trex.v2.ingest.cba;

import org.junit.jupiter.api.Test;
import trex.v2.ingest.Parsed;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The CommBank CSV adapter (V2-PROPOSAL.md §12.1): headerless, already-signed amounts. */
class CbaCsvTest {

    @Test
    void parsesSignedHeaderlessRows() {
        String file = """
            01/07/2026,-75.00,COLES 1234,1000.00
            02/07/2026,+1000.00,SALARY ACME,2000.00
            """;
        Parsed parsed = new CbaCsv().parse(file.getBytes(StandardCharsets.UTF_8), "f.csv", "cba-smartaccess");
        assertTrue(parsed.clean());
        assertEquals(2, parsed.candidates().size());
        assertEquals(-7500L, parsed.candidates().get(0).amount());
        assertEquals(100000L, parsed.candidates().get(1).amount());
        assertNull(parsed.candidates().get(0).receipt(), "no receipts: content-hash identity");
    }

    @Test
    void aHeaderRowIsRejectedWithAPointedMessage() {
        String file = "Date,Amount,Description,Balance\n01/07/2026,-75.00,COLES,1000.00\n";
        Parsed parsed = new CbaCsv().parse(file.getBytes(StandardCharsets.UTF_8), "f.csv", "cba-smartaccess");
        assertFalse(parsed.clean());
        assertTrue(parsed.bad().getFirst().reason().contains("header"), parsed.bad().getFirst().reason());
    }
}
