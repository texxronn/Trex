package trex.sequencer.config;

import trex.journal.AccountFiles;
import trex.journal.Yaml;
import trex.core.BalanceSource;
import trex.core.account.Account;
import trex.core.account.AccountRegistry;
import trex.sequencer.ingest.TransferRules;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Sequencer configuration loaded from a config directory holding
 * {@code sequencer.yaml}, {@code accounts.yaml} and {@code transfers.yaml}. SPEC §6.
 * Relative journal paths resolve against the config directory.
 * <p>
 * Shape is enforced by strict binding ({@link Yaml}: unknown key, duplicate key, wrong type);
 * this class adds the value checks that binding cannot express, and names the file in each one.
 */
public record Config(Path journalSource, Path journalTarget, String bindHost, int bindPort,
                     AccountRegistry registry, TransferRules rules) {

    private static final Logger log = LoggerFactory.getLogger(Config.class);

    private static final Set<String> CURRENCIES = Set.of("AUD", "USD", "INR");

    /** The three config files, in the order they are read. */
    private static final List<String> FILES = List.of("sequencer.yaml", "accounts.yaml", "transfers.yaml");

    record SequencerFile(String bindHost, Integer bindPort, JournalPaths journal) {}

    record JournalPaths(String source, String target) {}

    record TransfersFile(Integer windowDays, List<String> allowlist) {}

    public static Config load(Path configDir) {
        rejectLeftoverToml(configDir);

        SequencerFile sequencer = Yaml.read(configDir.resolve("sequencer.yaml"), SequencerFile.class);
        AccountRegistry registry = AccountFiles.load(configDir);
        TransfersFile transfers = Yaml.read(configDir.resolve("transfers.yaml"), TransfersFile.class);

        if (sequencer.journal() == null || sequencer.journal().source() == null || sequencer.journal().target() == null) {
            throw new IllegalArgumentException("sequencer.yaml: 'journal' needs both 'source' and 'target'");
        }
        Path source = configDir.resolve(sequencer.journal().source());
        Path target = configDir.resolve(sequencer.journal().target());

        String host = sequencer.bindHost() == null ? "127.0.0.1" : sequencer.bindHost();
        if (host.isBlank()) {
            throw new IllegalArgumentException("sequencer.yaml: bindHost must not be blank");
        }
        if (sequencer.bindPort() == null) {
            throw new IllegalArgumentException("sequencer.yaml: 'bindPort' is required");
        }
        int port = sequencer.bindPort();
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("sequencer.yaml: bindPort out of range: " + port);
        }


        if (transfers.windowDays() == null) {
            throw new IllegalArgumentException("transfers.yaml: 'windowDays' is required");
        }
        if (transfers.allowlist() == null) {
            throw new IllegalArgumentException("transfers.yaml: 'allowlist' must be a list of regexes");
        }

        log.info("config loaded from {}: {} accounts, {} transfer patterns, windowDays {}, bind {}:{}",
            configDir, registry.all().size(), transfers.allowlist().size(), transfers.windowDays(), host, port);
        return new Config(source, target, host, port, registry,
            new TransferRules(transfers.allowlist(), transfers.windowDays()));
    }

    /**
     * A leftover {@code .toml} beside its {@code .yaml} replacement is an error, not something to
     * ignore: config moved to YAML (SPEC §6) and a stale registry is only noticed after an ingest.
     */
    private static void rejectLeftoverToml(Path configDir) {
        for (String yaml : FILES) {
            Path toml = configDir.resolve(yaml.replace(".yaml", ".toml"));
            if (Files.exists(toml)) {
                throw new IllegalArgumentException(
                    toml.getFileName() + " is no longer read — config is YAML (SPEC §6). Convert it to "
                        + yaml + " and delete " + toml.getFileName() + ".");
            }
        }
    }
}
