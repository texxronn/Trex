package trex.journal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SPEC §5.1: a missing journal is a state, not an error. */
class JournalPresenceTest {

    @TempDir
    Path dir;

    @Test
    void notReadyUntilTheJournalExists() throws IOException {
        Path journal = dir.resolve("journal.jsonl");
        JournalPresence presence = new JournalPresence(journal);

        assertFalse(presence.ready());
        assertFalse(presence.ready(), "still waiting; repeated calls stay false");

        Files.writeString(journal, "");
        assertTrue(presence.ready());
    }

    @Test
    void goesBackToWaitingWhenTheJournalDisappears() throws IOException {
        Path journal = dir.resolve("journal.jsonl");
        Files.writeString(journal, "");
        JournalPresence presence = new JournalPresence(journal);
        assertTrue(presence.ready());

        Files.delete(journal);
        assertFalse(presence.ready());

        Files.writeString(journal, "");
        assertTrue(presence.ready(), "a replaced journal is followed again");
    }
}
