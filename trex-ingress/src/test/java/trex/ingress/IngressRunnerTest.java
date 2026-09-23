package trex.ingress;

import org.junit.jupiter.api.Test;
import trex.ingress.bw.BwCsv;
import trex.ingress.cba.CbaCsv;
import trex.ingress.ing.IngCsv;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IngressRunnerTest {

    @Test
    void everyKnownSourceTypeSelectsAParser() {
        assertNotNull(IngressRunner.parser(IngCsv.SOURCE_TYPE));
        assertNotNull(IngressRunner.parser(BwCsv.SOURCE_TYPE));
        assertNotNull(IngressRunner.parser(CbaCsv.SOURCE_TYPE));
    }

    @Test
    void unknownSourceTypeIsRejected() {
        // A bank name is not a source type: the format is what binds a parser (SPEC §4).
        assertThrows(IllegalArgumentException.class, () -> IngressRunner.parser("ing"));
        assertThrows(IllegalArgumentException.class, () -> IngressRunner.parser("cba"));
        assertThrows(IllegalArgumentException.class, () -> IngressRunner.parser("westpac-csv"));
    }

    /** An unknown type must say what is known, since that is the only place the list exists. */
    @Test
    void theRejectionListsTheKnownTypes() {
        String message = assertThrows(IllegalArgumentException.class,
            () -> IngressRunner.parser("westpac-csv")).getMessage();
        assertTrue(message.contains(IngCsv.SOURCE_TYPE), message);
        assertTrue(message.contains(BwCsv.SOURCE_TYPE), message);
        assertTrue(message.contains(CbaCsv.SOURCE_TYPE), message);
    }
}
