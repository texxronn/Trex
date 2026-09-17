package trex.sequencer.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigTest {

    @TempDir
    Path dir;

    private void write(String sequencer, String accounts, String transfers) throws IOException {
        Files.writeString(dir.resolve("sequencer.toml"), sequencer);
        Files.writeString(dir.resolve("accounts.toml"), accounts);
        Files.writeString(dir.resolve("transfers.toml"), transfers);
    }

    private static final String SEQUENCER = """
        bindPort = 8080
        [journal]
        source = "journal.jsonl"
        target = "journal.jsonl"
        """;
    private static final String ACCOUNTS = """
        [[account]]
        ref = "ing-savings"
        format = "ing"
        currency = "AUD"
        fireflyAccountId = "12"
        """;
    private static final String TRANSFERS = """
        windowDays = 3
        allowlist = ["Fast Transfer", "To my account"]
        """;

    @Test
    void loadsConfigDirectory() throws IOException {
        write(SEQUENCER, ACCOUNTS, TRANSFERS);
        Config c = Config.load(dir);
        assertEquals(8080, c.bindPort());
        assertEquals("127.0.0.1", c.bindHost());
        assertEquals(dir.resolve("journal.jsonl"), c.journalSource());
        assertEquals("AUD", c.registry().find("ing-savings").orElseThrow().currency());
        assertTrue(c.rules().isTransferShaped("FAST TRANSFER to CBA"));
        assertFalse(c.rules().isTransferShaped("Woolworths"));
        assertEquals(3, c.rules().windowDays());
    }

    @Test
    void bindHostCanBeSetAndOldApiPortIsRejected() throws IOException {
        write("bindHost = \"0.0.0.0\"\n" + SEQUENCER, ACCOUNTS, TRANSFERS);
        assertEquals("0.0.0.0", Config.load(dir).bindHost());
        write(SEQUENCER.replace("bindPort", "apiPort"), ACCOUNTS, TRANSFERS);
        assertThrows(IllegalArgumentException.class, () -> Config.load(dir));
        write("bindHost = \" \"\n" + SEQUENCER, ACCOUNTS, TRANSFERS);
        assertThrows(IllegalArgumentException.class, () -> Config.load(dir));
    }

    @Test
    void windowDaysIsRequired() throws IOException {
        write(SEQUENCER, ACCOUNTS, "allowlist = [\"x\"]\n");
        assertThrows(IllegalArgumentException.class, () -> Config.load(dir));
    }

    @Test
    void fsyncOptionIsNotAccepted() throws IOException {
        write("fsync = \"never\"\n" + SEQUENCER, ACCOUNTS, TRANSFERS);
        assertThrows(IllegalArgumentException.class, () -> Config.load(dir));
    }

    @Test
    void unsupportedCurrencyIsRejected() throws IOException {
        write(SEQUENCER, ACCOUNTS.replace("AUD", "EUR"), TRANSFERS);
        assertThrows(IllegalArgumentException.class, () -> Config.load(dir));
    }
}
