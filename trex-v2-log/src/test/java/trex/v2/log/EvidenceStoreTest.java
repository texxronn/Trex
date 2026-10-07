package trex.v2.log;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** §15.9: evidence is content-addressed, immutable and reproducible. */
class EvidenceStoreTest {

    @Test
    void storesCompressedContentAddressedAndIdempotent(@TempDir Path dir) throws Exception {
        EvidenceStore store = new EvidenceStore(dir);
        byte[] content = "date,amount\n2026-09-01,-10.00\n".getBytes(StandardCharsets.UTF_8);

        String id = store.put(content);
        assertTrue(id.startsWith("sha256:"));
        assertTrue(store.contains(id));
        assertArrayEquals(content, store.get(id));

        Path stored = store.pathFor(id);
        assertTrue(stored.toString().endsWith(".gz"), stored.toString());
        String hex = id.substring("sha256:".length());
        assertTrue(stored.toString().endsWith("sha256/" + hex.substring(0, 2) + "/" + hex.substring(2, 4)
            + "/" + hex + ".gz"), stored.toString());

        // A second put of the same content writes nothing new.
        assertEquals(1, store.list().size());
        assertEquals(id, store.put(content));
        assertEquals(1, store.list().size());
    }

    @Test
    void verifyDetectsCorruption(@TempDir Path dir) throws Exception {
        EvidenceStore store = new EvidenceStore(dir);
        String id = store.put("the bank said this".getBytes(StandardCharsets.UTF_8));
        assertTrue(store.verify().isEmpty());
        // Overwrite the file with different content; its id no longer matches its name.
        Files.write(store.pathFor(id), "tampered".getBytes(StandardCharsets.UTF_8));
        assertEquals(1, store.verify().size());
        assertFalse(store.verify().isEmpty());
    }
}
