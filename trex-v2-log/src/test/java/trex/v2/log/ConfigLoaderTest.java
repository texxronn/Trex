package trex.v2.log;

import org.junit.jupiter.api.Test;
import trex.v2.core.config.DeriveConfig;
import trex.v2.core.config.Registry;
import trex.v2.core.config.RuleSet;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tuned config in {@code deploy/config} loads as v2 derive inputs (plan §1.2): the loader
 * consumes the files as they are, so a rebuild that cannot reproduce their effect is a rebuild bug.
 */
class ConfigLoaderTest {

    private static Path configDir() {
        Path fromModule = Path.of("..", "deploy", "config");
        return Files.isDirectory(fromModule) ? fromModule : Path.of("deploy", "config");
    }

    @Test
    void loadsTheTunedConfig() {
        ConfigLoader.Loaded loaded = ConfigLoader.load(configDir());
        Registry registry = loaded.registry();
        assertTrue(registry.findUser("ron").isPresent());
        assertTrue(registry.findUser("mel").isPresent());
        assertTrue(registry.findAccount("ing-savings").isPresent());
        assertEquals("AUD", registry.account("ing-savings").currency());
        assertEquals(7, registry.account("ing-savings").settlementWindowDays());

        DeriveConfig config = loaded.config();
        assertTrue(config.configRevision().startsWith("sha256:"));
        assertEquals(4, config.transfers().windowDays());
        assertEquals(30, config.transfers().holdWindowDays());
        assertFalse(config.transfers().allowlist().isEmpty());
        assertTrue(config.categories().isDeclared("GROCERIES"));
        assertFalse(config.categories().isDeclared(RuleSet.TRANSFER));
        assertTrue(config.categories().rules().size() > 20);
    }

    @Test
    void configRevisionIsStable() {
        assertEquals(ConfigLoader.load(configDir()).config().configRevision(),
            ConfigLoader.load(configDir()).config().configRevision());
    }

    @Test
    void loadsTheSequencerFile() {
        ConfigLoader.ServerConfig server = ConfigLoader.loadSequencer(configDir());
        assertEquals("127.0.0.1", server.host());
        assertEquals(8080, server.port());
        assertTrue(server.journalSource().isAbsolute());
        assertTrue(server.journalTarget().isAbsolute());
    }

    @Test
    void aFailingFixtureIsALoadError(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("accounts.yaml"), """
            accounts:
              - ref: "ing-savings"
                currency: "AUD"
                balanceSource: statement
            """);
        Files.writeString(dir.resolve("users.yaml"), """
            users:
              - id: "ron"
                name: "Ron"
                active: true
            """);
        Files.writeString(dir.resolve("categories.yaml"), """
            categories: [GROCERIES]
            rules:
              - category: GROCERIES
                when:
                  match: "COLES"
            """);
        Files.writeString(dir.resolve("transfers.yaml"), """
            windowDays: 4
            allowlist:
              - 'Transfer'
            """);
        Files.writeString(dir.resolve("categories.tests.yaml"), """
            tests:
              - description: "COLES 1234"
                amount: -1000
                category: FOOD
            """);
        IllegalArgumentException error = org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class, () -> ConfigLoader.load(dir));
        assertTrue(error.getMessage().contains("fixture"), error.getMessage());
    }
}
