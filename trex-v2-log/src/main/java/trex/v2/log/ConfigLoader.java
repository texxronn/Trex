package trex.v2.log;

import trex.v2.core.Hashes;
import trex.v2.core.config.Account;
import trex.v2.core.config.BalanceSource;
import trex.v2.core.config.DeriveConfig;
import trex.v2.core.config.Registry;
import trex.v2.core.config.RuleSet;
import trex.v2.core.config.TransferRules;
import trex.v2.core.config.User;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Reads the derive inputs from a config directory (V2-PROPOSAL.md §9.9 P3, plan §1.2): the account
 * registry, the users, the category rules and the transfer rules, plus a {@code configRevision}
 * hashing every file that feeds derivation.
 *
 * <p>The tuned files in {@code deploy/config} are consumed as they are: {@code categories.yaml}
 * carries both the declared names and the rules (the proposal's {@code refdata.yaml} split is the
 * target but both shapes are accepted), and the new transfer tunables fall back to the proposal's
 * defaults when absent. A load failure is a startup error, not a derivation result.
 */
public final class ConfigLoader {

    /** Everything derivation and the sequencer need from config. */
    public record Loaded(Registry registry, DeriveConfig config) {}

    private static final int DEFAULT_SETTLEMENT_WINDOW_DAYS = 7;

    private ConfigLoader() {}

    public static Loaded load(Path configDir) {
        Path accountsFile = configDir.resolve("accounts.yaml");
        Path usersFile = configDir.resolve("users.yaml");
        Path categoriesFile = configDir.resolve("categories.yaml");
        Path transfersFile = configDir.resolve("transfers.yaml");
        Path refdataFile = configDir.resolve("refdata.yaml");

        AccountsFile accounts = Yaml.read(accountsFile, AccountsFile.class);
        UsersFile users = Yaml.read(usersFile, UsersFile.class);
        RuleSet.File categories = Yaml.read(categoriesFile, RuleSet.File.class);
        TransfersFile transfers = Yaml.read(transfersFile, TransfersFile.class);

        Map<String, Account> accountMap = new LinkedHashMap<>();
        for (AccountEntry e : accounts.accounts()) {
            BalanceSource source = BalanceSource.fromWire(e.balanceSource());
            int settlement = e.settlementWindowDays() == null
                ? DEFAULT_SETTLEMENT_WINDOW_DAYS : e.settlementWindowDays();
            Account account = new Account(e.ref(), e.currency(), source, settlement);
            if (accountMap.putIfAbsent(account.ref(), account) != null) {
                throw new IllegalArgumentException(accountsFile.getFileName() + ": account '" + e.ref() + "' is declared twice");
            }
        }
        Map<String, User> userMap = new LinkedHashMap<>();
        for (UserEntry e : users.users()) {
            User user = new User(e.id(), e.name(), e.active(), e.cadence());
            if (userMap.putIfAbsent(user.id(), user) != null) {
                throw new IllegalArgumentException(usersFile.getFileName() + ": user '" + e.id() + "' is declared twice");
            }
        }
        Registry registry = new Registry(accountMap, userMap);

        List<String> declared = categories.categories();
        if (Files.exists(refdataFile)) {
            RefdataFile refdata = Yaml.read(refdataFile, RefdataFile.class);
            declared = refdata.categories();
        }
        RuleSet ruleSet = RuleSet.compile(categoriesFile.getFileName().toString(),
            new RuleSet.File(declared, categories.rules()));

        List<Pattern> allowlist = transfers.allowlist() == null ? List.of()
            : transfers.allowlist().stream()
                .map(s -> Pattern.compile(s, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE))
                .toList();
        TransferRules transferRules = new TransferRules(
            transfers.windowDays(),
            transfers.dupTolerance() == null ? TransferRules.DEFAULT_DUP_TOLERANCE : transfers.dupTolerance(),
            transfers.amountTolerance() == null ? TransferRules.DEFAULT_AMOUNT_TOLERANCE : transfers.amountTolerance(),
            transfers.holdWindowDays() == null ? TransferRules.DEFAULT_HOLD_WINDOW_DAYS : transfers.holdWindowDays(),
            transfers.restatementOverlap() == null ? TransferRules.DEFAULT_RESTATEMENT_OVERLAP : transfers.restatementOverlap(),
            allowlist);

        String configRevision = configRevision(configDir, accountsFile, categoriesFile, transfersFile, refdataFile);
        DeriveConfig config = new DeriveConfig(registry, ruleSet, transferRules, configRevision);
        return new Loaded(registry, config);
    }

    /**
     * Hash every file that can move derived state (§9.3). Sorted, name-prefixed and length-free
     * because the separators make the concatenation unambiguous.
     */
    private static String configRevision(Path dir, Path... files) {
        try {
            List<Path> present = new ArrayList<>();
            for (Path f : files) {
                if (Files.exists(f)) {
                    present.add(f);
                }
            }
            present.sort(Path::compareTo);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (Path f : present) {
                out.write(f.getFileName().toString().getBytes(StandardCharsets.UTF_8));
                out.write(0);
                out.write(Files.readAllBytes(f));
                out.write(0);
            }
            return Hashes.sha256(out.toByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot hash config in " + dir, e);
        }
    }

    // ---- YAML shapes ------------------------------------------------------------------------

    public record AccountsFile(List<AccountEntry> accounts) {}

    public record AccountEntry(String ref, String currency, String balanceSource, Integer settlementWindowDays) {}

    public record UsersFile(List<UserEntry> users) {}

    public record UserEntry(String id, String name, boolean active, String cadence) {}

    public record RefdataFile(List<String> categories) {}

    public record TransfersFile(int windowDays, List<String> allowlist, Integer dupTolerance,
                               Integer amountTolerance, Integer holdWindowDays, Double restatementOverlap) {}

    // ---- the sequencer service file (v1's sequencer.yaml) -----------------------------------

    /** Bind host/port and the journal paths, resolved against the config directory. */
    public record ServerConfig(String host, int port, Path journalSource, Path journalTarget) {}

    record SequencerFile(String bindHost, Integer bindPort, JournalPaths journal) {}
    record JournalPaths(String source, String target) {}

    /**
     * Read {@code sequencer.yaml}. Relative journal paths resolve against the config directory
     * (v1's rule); an unknown or duplicated key is a startup error. Absent host/port default to
     * loopback:8080.
     */
    public static ServerConfig loadSequencer(Path configDir) {
        Path file = configDir.resolve("sequencer.yaml");
        SequencerFile parsed = Yaml.read(file, SequencerFile.class);
        if (parsed.journal() == null || parsed.journal().source() == null || parsed.journal().target() == null) {
            throw new IllegalArgumentException(file.getFileName() + ": 'journal' needs both 'source' and 'target'");
        }
        String host = parsed.bindHost() == null || parsed.bindHost().isBlank() ? "127.0.0.1" : parsed.bindHost();
        int port = parsed.bindPort() == null ? 8080 : parsed.bindPort();
        return new ServerConfig(host, port,
            configDir.resolve(parsed.journal().source()),
            configDir.resolve(parsed.journal().target()));
    }
}
