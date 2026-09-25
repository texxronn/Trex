package trex.sequencer.config;

import trex.core.BalanceSource;

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
        Files.writeString(dir.resolve("sequencer.yaml"), sequencer);
        Files.writeString(dir.resolve("accounts.yaml"), accounts);
        Files.writeString(dir.resolve("transfers.yaml"), transfers);
    }

    private static final String SEQUENCER = """
        bindPort: 8080
        journal:
          source: "journal.jsonl"
          target: "journal.jsonl"
        """;
    private static final String ACCOUNTS = """
        accounts:
          - ref: "ing-savings"
            currency: "AUD"
            balanceSource: statement
        """;
    private static final String TRANSFERS = """
        windowDays: 3
        allowlist: ["Fast Transfer", "To my account"]
        """;

    /**
     * The Firefly mapping moved to firefly.yaml (SPEC §5.8), and strict binding turns the old
     * shape into a startup error naming the key rather than a line silently ignored. Without
     * this, a registry left as it was would load and the egress would quietly use a different
     * file than the one being edited.
     */
    @Test
    void anEgressFieldLeftInTheRegistryIsAStartupError() throws IOException {
        write(SEQUENCER, """
            accounts:
              - ref: "ing-savings"
                currency: "AUD"
                balanceSource: statement
                fireflyAccountId: "12"
            """, TRANSFERS);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Config.load(dir));
        assertTrue(e.getMessage().contains("fireflyAccountId"), e.getMessage());
    }

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
        write("bindHost: \"0.0.0.0\"\n" + SEQUENCER, ACCOUNTS, TRANSFERS);
        assertEquals("0.0.0.0", Config.load(dir).bindHost());
        write(SEQUENCER.replace("bindPort", "apiPort"), ACCOUNTS, TRANSFERS);
        assertThrows(IllegalArgumentException.class, () -> Config.load(dir));
        write("bindHost: \" \"\n" + SEQUENCER, ACCOUNTS, TRANSFERS);
        assertThrows(IllegalArgumentException.class, () -> Config.load(dir));
    }

    /** The shipped sample configuration (deploy/config) must always load. */
    @Test
    void sampleDeployConfigLoads() {
        Config c = Config.load(Path.of("..", "deploy", "config"));
        assertEquals("127.0.0.1", c.bindHost());
        assertEquals(8080, c.bindPort());
        assertEquals(Path.of("/var/lib/trex/journal/journal.jsonl"), c.journalSource());
        assertEquals(c.journalSource(), c.journalTarget());
        assertEquals("AUD", c.registry().find("ing-savings").orElseThrow().currency());
        // The allowlist must separate an internal movement from a payment to a person. Both
        // shapes are written by CommBank and only the first has a contra leg; the broad form
        // 'Transfer (to|from)' matched both and held 14 real expenses forever.
        assertTrue(c.rules().isTransferShaped("Transfer from xx2435 CommBank app house expenses"));
        assertFalse(c.rules().isTransferShaped("Transfer To Vazhel A John CommBank App Piano Lessons"));
        assertFalse(c.rules().isTransferShaped("Transfer To Tax Store Australia Pty Ltd CommBank App"));
        // Tracks the shipped transfers.yaml, so a change to the window is a deliberate
        // edit here too — the value decides whether a leg pairs or becomes a final
        // manual decision, and it must not drift unnoticed.
        assertEquals(4, c.rules().windowDays());
    }

    @Test
    void windowDaysIsRequired() throws IOException {
        write(SEQUENCER, ACCOUNTS, "allowlist: [\"x\"]\n");
        assertThrows(IllegalArgumentException.class, () -> Config.load(dir));
    }

    @Test
    void fsyncOptionIsNotAccepted() throws IOException {
        write("fsync: \"never\"\n" + SEQUENCER, ACCOUNTS, TRANSFERS);
        assertThrows(IllegalArgumentException.class, () -> Config.load(dir));
    }

    @Test
    void unsupportedCurrencyIsRejected() throws IOException {
        write(SEQUENCER, ACCOUNTS.replace("AUD", "EUR"), TRANSFERS);
        assertThrows(IllegalArgumentException.class, () -> Config.load(dir));
    }

    /** A duplicated key is an error, not last-wins (SPEC §6, strict binding). */
    @Test
    void duplicateKeyIsRejected() throws IOException {
        write(SEQUENCER + "bindPort: 9090\n", ACCOUNTS, TRANSFERS);
        assertThrows(IllegalArgumentException.class, () -> Config.load(dir));
    }

    /** Config moved to YAML: a leftover .toml must fail loudly rather than be ignored (SPEC §6). */
    @Test
    void leftoverTomlIsRejected() throws IOException {
        write(SEQUENCER, ACCOUNTS, TRANSFERS);
        Files.writeString(dir.resolve("accounts.toml"), "[[account]]\nref = \"stale\"\n");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Config.load(dir));
        assertTrue(e.getMessage().contains("accounts.toml"), e.getMessage());
        assertTrue(e.getMessage().contains("accounts.yaml"), e.getMessage());
    }

    /**
     * The container variant (deploy/compose/sequencer.yaml) is mounted at run time, so a
     * bad key there would surface as a failed container start rather than a failed build.
     * compose delivers all three files into one directory (/etc/trex): the sequencer.yaml
     * from deploy/compose, accounts and transfers from deploy/config. Assemble that same
     * directory here and load it.
     */
    @Test
    void containerConfigLoads() throws IOException {
        Files.copy(Path.of("..", "deploy", "compose", "sequencer.yaml"), dir.resolve("sequencer.yaml"));
        Files.copy(Path.of("..", "deploy", "config", "accounts.yaml"), dir.resolve("accounts.yaml"));
        Files.copy(Path.of("..", "deploy", "config", "transfers.yaml"), dir.resolve("transfers.yaml"));

        Config c = Config.load(dir);
        // Binds all interfaces inside the network namespace; compose publishes to loopback.
        assertEquals("0.0.0.0", c.bindHost());
        assertEquals(8080, c.bindPort());
        // Data lives on the volume, never in the image.
        assertEquals(Path.of("/var/lib/trex/journal/journal.jsonl"), c.journalSource());
        assertEquals(c.journalSource(), c.journalTarget());
        assertEquals("AUD", c.registry().find("ing-savings").orElseThrow().currency());
        // Tracks the shipped transfers.yaml, so a change to the window is a deliberate
        // edit here too — the value decides whether a leg pairs or becomes a final
        // manual decision, and it must not drift unnoticed.
        assertEquals(4, c.rules().windowDays());
    }

    /**
     * SPEC §7 test 28. Required rather than defaulted: a missing value would have to mean one of
     * the two, and the guess decides whether a gap in that account's balance chain is reported as
     * a fault or as normal. §0.6 says an ambiguity goes to review, not to a default.
     */
    @Test
    void balanceSourceIsRequiredAndNamesTheAccount() throws IOException {
        Path dir = Files.createTempDirectory("trex-bs");
        Files.writeString(dir.resolve("sequencer.yaml"), """
            bindPort: 8080
            journal:
              source: "j.jsonl"
              target: "j.jsonl"
            """);
        Files.writeString(dir.resolve("transfers.yaml"), "windowDays: 4\nallowlist: ['Internal Transfer']\n");

        Files.writeString(dir.resolve("accounts.yaml"), """
            accounts:
              - ref: "ing-savings"
                currency: "AUD"
            """);
        IllegalArgumentException missing =
            assertThrows(IllegalArgumentException.class, () -> Config.load(dir));
        assertTrue(missing.getMessage().contains("ing-savings"), missing.getMessage());
        assertTrue(missing.getMessage().contains("balanceSource"), missing.getMessage());

        Files.writeString(dir.resolve("accounts.yaml"), """
            accounts:
              - ref: "ing-savings"
                currency: "AUD"
                balanceSource: "sometimes"
            """);
        IllegalArgumentException bad =
            assertThrows(IllegalArgumentException.class, () -> Config.load(dir));
        assertTrue(bad.getMessage().contains("sometimes"), bad.getMessage());

        Files.writeString(dir.resolve("accounts.yaml"), """
            accounts:
              - ref: "cash-ron"
                currency: "AUD"
                balanceSource: "declared"
            """);
        assertEquals(BalanceSource.DECLARED,
            Config.load(dir).registry().find("cash-ron").orElseThrow().balanceSource());
    }
}
