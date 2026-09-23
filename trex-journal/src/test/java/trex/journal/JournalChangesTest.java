package trex.journal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JournalChangesTest {

    @TempDir
    Path dir;

    @Test
    void wakesOnJournalWriteAndTimesOutOtherwise() throws Exception {
        Path journal = dir.resolve("journal.jsonl");
        try (JournalChanges changes = new JournalChanges(journal)) {
            assertTrue(changes.available());
            assertFalse(changes.await(150), "no change → timeout");

            Files.writeString(dir.resolve("other.txt"), "x");
            assertFalse(changes.await(300), "changes to other files are ignored");

            CompletableFuture<Boolean> woke = CompletableFuture.supplyAsync(() -> {
                try {
                    return changes.await(10_000);
                } catch (InterruptedException _) {
                    return false;
                }
            });
            Thread.sleep(100);
            long t0 = System.nanoTime();
            Files.writeString(journal, "line\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            assertTrue(woke.get(5, TimeUnit.SECONDS));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) < 5_000);
        }
    }

    @Test
    void changeBeforeWaitIsNotLost() throws Exception {
        Path journal = dir.resolve("journal.jsonl");
        try (JournalChanges changes = new JournalChanges(journal)) {
            Files.writeString(journal, "line\n");      // happens while the reader is "busy"
            assertTrue(changes.await(5_000));
            assertFalse(changes.await(150), "a burst is coalesced into one wake");
        }
    }
}
