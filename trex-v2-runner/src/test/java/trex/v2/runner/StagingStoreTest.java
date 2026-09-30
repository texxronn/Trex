package trex.v2.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StagingStoreTest {

    @Test
    void putListResolveAndClear(@TempDir Path dir) throws Exception {
        StagingStore store = new StagingStore(dir.resolve("staging"));
        byte[] bytes = "a,b\n1,2\n".getBytes(StandardCharsets.UTF_8);
        StagingStore.Staged staged = store.put(bytes, "My Bank.csv");

        assertEquals("sha256:" + StagingStore.sha256Hex(bytes), "sha256:" + staged.sha256());
        assertEquals("My Bank.csv", staged.original());
        assertEquals("staged", staged.state());
        assertTrue(staged.name().endsWith("My_Bank.csv"));

        assertEquals("My Bank.csv", store.originalOf(staged.name()));
        assertEquals(1, store.list().size());
        assertTrue(Files.exists(store.resolve(staged.name())));

        assertTrue(store.markDone(staged.name()));
        assertEquals("done", store.list().get(0).state());
        assertEquals("My Bank.csv", store.originalOf(staged.name()));
        assertTrue(Files.exists(store.resolve(staged.name())));
    }

    @Test
    void resolveRejectsTraversal(@TempDir Path dir) {
        StagingStore store = new StagingStore(dir);
        assertThrows(IllegalArgumentException.class, () -> store.resolve("../etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> store.resolve("a/b"));
    }
}
