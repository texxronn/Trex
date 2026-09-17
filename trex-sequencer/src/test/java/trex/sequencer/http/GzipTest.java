package trex.sequencer.http;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GzipTest {

    @Test
    void acceptEncodingNegotiation() {
        assertTrue(Gzip.acceptsGzip(List.of("gzip")));
        assertTrue(Gzip.acceptsGzip(List.of("deflate, GZIP;q=0.5")));
        assertFalse(Gzip.acceptsGzip(null));
        assertFalse(Gzip.acceptsGzip(List.of("deflate, br")));
        assertFalse(Gzip.acceptsGzip(List.of("gzip;q=0")));
        assertFalse(Gzip.acceptsGzip(List.of("gzip; q=0.000")));
    }
}
