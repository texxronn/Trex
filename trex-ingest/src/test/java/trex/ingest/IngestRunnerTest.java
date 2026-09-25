package trex.ingest;

import org.junit.jupiter.api.Test;
import trex.ingest.bw.BwCsv;
import trex.ingest.cba.CbaCsv;
import trex.ingest.ing.IngCsv;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IngestRunnerTest {

    @Test
    void everyKnownSourceTypeSelectsAParser() {
        assertNotNull(IngestRunner.parser(IngCsv.SOURCE_TYPE));
        assertNotNull(IngestRunner.parser(BwCsv.SOURCE_TYPE));
        assertNotNull(IngestRunner.parser(CbaCsv.SOURCE_TYPE));
    }

    @Test
    void unknownSourceTypeIsRejected() {
        // A bank name is not a source type: the format is what binds a parser (SPEC §4).
        assertThrows(IllegalArgumentException.class, () -> IngestRunner.parser("ing"));
        assertThrows(IllegalArgumentException.class, () -> IngestRunner.parser("cba"));
        assertThrows(IllegalArgumentException.class, () -> IngestRunner.parser("westpac-csv"));
    }

    /** An unknown type must say what is known, since that is the only place the list exists. */
    @Test
    void theRejectionListsTheKnownTypes() {
        String message = assertThrows(IllegalArgumentException.class,
            () -> IngestRunner.parser("westpac-csv")).getMessage();
        assertTrue(message.contains(IngCsv.SOURCE_TYPE), message);
        assertTrue(message.contains(BwCsv.SOURCE_TYPE), message);
        assertTrue(message.contains(CbaCsv.SOURCE_TYPE), message);
    }
}
