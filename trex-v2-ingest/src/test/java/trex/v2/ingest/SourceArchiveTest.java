package trex.v2.ingest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The source archive (V2-PROPOSAL.md §12.6): a dated, human-named gzip of the exact bytes. */
class SourceArchiveTest {

    @Test
    void writesADatedGzip(@TempDir Path dir) throws Exception {
        byte[] content = "a,b\n1,2\n".getBytes(StandardCharsets.UTF_8);
        Path path = SourceArchive.write(dir, content, "My Bank.csv",
            Instant.parse("2026-09-30T14:05:09Z"));

        assertTrue(path.toString().endsWith("sources/2026/09/30/140509-My_Bank.csv.gz"), path.toString());
        try (GZIPInputStream in = new GZIPInputStream(Files.newInputStream(path))) {
            assertArrayEquals(content, in.readAllBytes());
        }
    }
}
