package trex.v2.log;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    void refusesIdsThatAreNot64LowercaseHex(@TempDir Path dir) {
        EvidenceStore store = new EvidenceStore(dir);
        for (String bad : List.of(
            "sha256:../../../../tmp/x",
            "sha256:" + "A".repeat(64),
            "sha256:abc",
            "sha256:" + "a".repeat(65),
            "a".repeat(64))) {
            assertThrows(IllegalArgumentException.class, () -> store.contains(bad), bad);
            assertThrows(IllegalArgumentException.class, () -> store.pathFor(bad), bad);
        }
        // A well-formed id that is not stored is absent, not malformed.
        assertFalse(store.contains("sha256:" + "0".repeat(64)));
    }

    @Test
    void verifyMarksAStrayFilenameBadRatherThanCrashing(@TempDir Path dir) throws Exception {
        EvidenceStore store = new EvidenceStore(dir);
        store.put("real".getBytes(StandardCharsets.UTF_8));
        Path stray = dir.resolve("sha256").resolve("zz").resolve("zz").resolve("not-an-id.gz");
        Files.createDirectories(stray.getParent());
        Files.write(stray, new byte[] {1, 2, 3});
        assertEquals(1, store.verify().size(), "a stray name is evidence the store cannot trust");
    }
}
