package trex.v2.log;

import trex.v2.core.Hashes;
import trex.v2.core.config.Account;
import trex.v2.core.config.BalanceSource;
import trex.v2.core.config.DeriveConfig;
import trex.v2.core.config.Profiles;
import trex.v2.core.config.Registry;
import trex.v2.core.config.RuleSet;
import trex.v2.core.config.TransferRules;
import trex.v2.core.config.User;
import trex.v2.core.workbook.RuleFixtures;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

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
        Path profilesFile = configDir.resolve("profiles.yaml");

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
        Profiles profiles = loadProfiles(profilesFile, accountMap);

        List<String> declared = categories.categories();
        if (Files.exists(refdataFile)) {
            RefdataFile refdata = Yaml.read(refdataFile, RefdataFile.class);
            declared = refdata.categories();
        }
        RuleSet ruleSet = RuleSet.compile(categoriesFile.getFileName().toString(),
            new RuleSet.File(declared, categories.rules()));

        // The golden fixtures run on load: a rule regression fails here, not at row 12 000.
        Path fixturesFile = configDir.resolve("categories.tests.yaml");
        if (Files.exists(fixturesFile)) {
            FixturesFile fixtures = Yaml.read(fixturesFile, FixturesFile.class);
            List<RuleFixtures.Case> cases = (fixtures.tests() == null ? List.<FixtureEntry>of() : fixtures.tests())
                .stream()
                .map(e -> new RuleFixtures.Case(e.description(), e.amount() == null ? 0L : e.amount(),
                    e.category(), e.accountRef()))
                .toList();
            List<RuleFixtures.Failure> failures = RuleFixtures.check(ruleSet, cases);
            if (!failures.isEmpty()) {
                RuleFixtures.Failure first = failures.getFirst();
                throw new IllegalArgumentException(fixturesFile.getFileName() + ": " + failures.size()
                    + " fixture(s) failed, e.g. \"" + first.description() + "\" expected "
                    + first.expected() + " but got " + first.actual());
            }
        }

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

        String configRevision = configRevision(configDir, accountsFile, categoriesFile, transfersFile, refdataFile,
            profilesFile);
        DeriveConfig config = new DeriveConfig(registry, ruleSet, transferRules, profiles, configRevision);
        return new Loaded(registry, config);
    }

    /**
     * {@code profiles.yaml}: account-scoped noop rules (V2-PROPOSAL.md §6.9). An unknown account,
     * an unknown action or a rule without a reason is a startup error — a rule that excludes a row
     * from the ledger has to say what the row is instead.
     */
    private static Profiles loadProfiles(Path file, Map<String, Account> accounts) {
        if (!Files.exists(file)) {
            return Profiles.empty();
        }
        ProfilesFile parsed = Yaml.read(file, ProfilesFile.class);
        List<Profiles.Rule> rules = new ArrayList<>();
        if (parsed.profiles() != null) {
            for (Map.Entry<String, ProfileEntry> entry : parsed.profiles().entrySet()) {
                String accountRef = entry.getKey();
                if (!Profiles.ANY_ACCOUNT.equals(accountRef) && !accounts.containsKey(accountRef)) {
                    throw new IllegalArgumentException(file.getFileName()
                        + ": profile names unknown account '" + accountRef + "'");
                }
                List<ProfileRule> declared = entry.getValue() == null || entry.getValue().rules() == null
                    ? List.of() : entry.getValue().rules();
                for (ProfileRule rule : declared) {
                    if (rule.match() == null || rule.match().isBlank()) {
                        throw new IllegalArgumentException(file.getFileName()
                            + ": a rule under '" + accountRef + "' has no match");
                    }
                    if (rule.reason() == null || rule.reason().isBlank()) {
                        throw new IllegalArgumentException(file.getFileName()
                            + ": rule '" + rule.match() + "' has no reason");
                    }
                    String action = rule.action() == null ? "" : rule.action().trim().toUpperCase(Locale.ROOT);
                    if (!action.equals(trex.v2.core.Action.MARK_NOOP.name())) {
                        throw new IllegalArgumentException(file.getFileName() + ": rule '" + rule.match()
                            + "' has unknown action '" + rule.action() + "' (only MARK_NOOP for now)");
                    }
                    try {
                        rules.add(new Profiles.Rule(accountRef,
                            Pattern.compile(rule.match(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE),
                            rule.reason()));
                    } catch (PatternSyntaxException e) {
                        throw new IllegalArgumentException(file.getFileName() + ": rule '" + rule.match()
                            + "' is not a valid pattern: " + e.getDescription());
                    }
                }
            }
        }
        return new Profiles(rules);
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

    /** {@code categories.tests.yaml}: the golden fixtures (V2-PROPOSAL.md §10.4). */
    public record FixturesFile(List<FixtureEntry> tests) {}

    public record FixtureEntry(String description, Long amount, String category, String accountRef) {}

    public record TransfersFile(int windowDays, List<String> allowlist, Integer dupTolerance,
                               Integer amountTolerance, Integer holdWindowDays, Double restatementOverlap) {}

    /** {@code profiles.yaml}: account-scoped role rules (V2-PROPOSAL.md §6.9). */
    public record ProfilesFile(Map<String, ProfileEntry> profiles) {}

    public record ProfileEntry(List<ProfileRule> rules) {}

    public record ProfileRule(String match, String action, String reason) {}

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
