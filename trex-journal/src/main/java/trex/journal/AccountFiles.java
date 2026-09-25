package trex.journal;

import trex.core.BalanceSource;
import trex.core.account.Account;
import trex.core.account.AccountRegistry;

import java.nio.file.Path;
import java.util.List;

/**
 * Reading {@code accounts.yaml}. SPEC §6.
 * <p>
 * Here for the same reason {@link RuleFiles} is: the registry itself is domain and lives in
 * trex-core, which may not touch the filesystem, while this module already owns config binding —
 * one strictly-configured YAML mapper, so an unknown key is a startup error in every file trex
 * reads rather than most of them.
 * <p>
 * It matters that there is exactly one of these. The sequencer decides whether a candidate needs
 * a balance and trex-ws decides whether a hand-entered line is allowed, and both answers come from
 * {@code balanceSource}. Two readers of the same file could disagree about an account; one cannot.
 */
public final class AccountFiles {

    record AccountsFile(List<AccountEntry> accounts) {}

    record AccountEntry(String ref, String currency, String balanceSource) {}

    private static final java.util.Set<String> CURRENCIES = java.util.Set.of("AUD", "USD", "INR");

    private AccountFiles() {}

    public static AccountRegistry load(Path configDir) {
        Path file = configDir.resolve("accounts.yaml");
        AccountsFile parsed = Yaml.read(file, AccountsFile.class);
        if (parsed.accounts() == null || parsed.accounts().isEmpty()) {
            throw new IllegalArgumentException("accounts.yaml: 'accounts' must list at least one account");
        }
        for (AccountEntry a : parsed.accounts()) {
            if (a.ref() == null || a.ref().isBlank()) {
                throw new IllegalArgumentException("accounts.yaml: every account needs a 'ref'");
            }
            if (!CURRENCIES.contains(a.currency())) {
                throw new IllegalArgumentException("accounts.yaml: unsupported currency '" + a.currency() + "'");
            }
            balanceSource(a);                 // validated here so the message names the account
        }
        return new AccountRegistry(parsed.accounts().stream()
            .map(a -> new Account(a.ref(), a.currency(), balanceSource(a)))
            .toList());
    }

    /**
     * Required, never defaulted. SPEC §6: a missing value would have to mean one of the two, and
     * the guess decides whether a gap in that account's balance chain is reported as a fault or as
     * the normal shape of the data — the kind of ambiguity §0.6 says must not be resolved silently.
     */
    static BalanceSource balanceSource(AccountEntry a) {
        String v = a.balanceSource();
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("accounts.yaml: " + a.ref()
                + " needs a 'balanceSource' (statement | declared) — see SPEC §6");
        }
        return switch (v) {
            case "statement" -> BalanceSource.STATEMENT;
            case "declared" -> BalanceSource.DECLARED;
            default -> throw new IllegalArgumentException("accounts.yaml: " + a.ref()
                + " balanceSource must be 'statement' or 'declared', not '" + v + "'");
        };
    }
}
