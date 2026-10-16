package trex.v2.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    /**
     * Clear is what removes a row from the list (V2-INBOX-CLEAR-PLAN.md §2, I-1), so it must reach a
     * file wherever the sweep had filed it — and take the sidecar with it, or the name goes on
     * resolving to an original that has no bytes.
     */
    @Test
    void discardRemovesTheUploadAndItsSidecar(@TempDir Path dir) throws Exception {
        Path root = dir.resolve("staging");
        StagingStore store = new StagingStore(root);
        byte[] bytes = "a,b\n1,2\n".getBytes(StandardCharsets.UTF_8);

        StagingStore.Staged inbox = store.put(bytes, "Inbox.csv");
        assertTrue(store.discard(inbox.name()));
        assertFalse(Files.exists(root.resolve(inbox.name())));
        assertFalse(Files.exists(root.resolve(inbox.name() + ".meta")));
        assertTrue(store.list().isEmpty());

        // What Clear used to leave behind: filed into done/ by the sweep, and still listed.
        StagingStore.Staged filed = store.put(bytes, "Filed.csv");
        assertTrue(store.markDone(filed.name()));
        assertEquals("done", store.list().get(0).state());
        assertTrue(store.discard(filed.name()));
        assertFalse(Files.exists(root.resolve("done").resolve(filed.name() + ".meta")));
        assertTrue(store.list().isEmpty());

        // A failed file is out of the sweep, but it is still the operator's to throw away.
        StagingStore.Staged failed = store.put(bytes, "Failed.csv");
        assertTrue(store.markFailed(failed.name()));
        assertTrue(store.discard(failed.name()));
        assertTrue(store.list().isEmpty());

        // Nothing by that name: the route answers 404, it is not an error.
        assertFalse(store.discard("never-there.csv"));
    }

    @Test
    void discardRejectsTraversal(@TempDir Path dir) {
        StagingStore store = new StagingStore(dir);
        assertThrows(IllegalArgumentException.class, () -> store.discard("../etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> store.discard("a/b"));
    }
}
