package trex.sequencer.config;

import trex.sequencer.ingest.Account;
import trex.sequencer.ingest.AccountRegistry;
import trex.sequencer.ingest.TransferRules;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sequencer configuration loaded from a config directory holding
 * {@code sequencer.toml}, {@code accounts.toml} and {@code transfers.toml}. SPEC §6.
 * Relative journal paths resolve against the config directory.
 */
public record Config(Path journalSource, Path journalTarget, int apiPort,
                     AccountRegistry registry, TransferRules rules) {

    private static final Set<String> FORMATS = Set.of("ing", "cba", "bw");
    private static final Set<String> CURRENCIES = Set.of("AUD", "USD", "INR");

    public static Config load(Path configDir) {
        Map<String, Object> sequencer = read(configDir.resolve("sequencer.toml"));
        Map<String, Object> accounts = read(configDir.resolve("accounts.toml"));
        Map<String, Object> transfers = read(configDir.resolve("transfers.toml"));

        allowOnly("sequencer.toml", sequencer, Set.of("apiPort", "journal"));
        Map<String, Object> journal = table("sequencer.toml", sequencer, "journal");
        allowOnly("sequencer.toml [journal]", journal, Set.of("source", "target"));
        Path source = configDir.resolve(string("sequencer.toml [journal]", journal, "source"));
        Path target = configDir.resolve(string("sequencer.toml [journal]", journal, "target"));
        long port = integer("sequencer.toml", sequencer, "apiPort");
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("sequencer.toml: apiPort out of range: " + port);
        }

        allowOnly("accounts.toml", accounts, Set.of("account"));
        List<Account> list = new ArrayList<>();
        for (Map<String, Object> a : tables("accounts.toml", accounts, "account")) {
            allowOnly("accounts.toml [[account]]", a, Set.of("ref", "format", "currency", "fireflyAccountId"));
            String format = string("accounts.toml [[account]]", a, "format");
            String currency = string("accounts.toml [[account]]", a, "currency");
            if (!FORMATS.contains(format)) {
                throw new IllegalArgumentException("accounts.toml: unknown format '" + format + "'");
            }
            if (!CURRENCIES.contains(currency)) {
                throw new IllegalArgumentException("accounts.toml: unsupported currency '" + currency + "'");
            }
            list.add(new Account(string("accounts.toml [[account]]", a, "ref"), format, currency,
                string("accounts.toml [[account]]", a, "fireflyAccountId")));
        }

        allowOnly("transfers.toml", transfers, Set.of("windowDays", "allowlist"));
        long windowDays = integer("transfers.toml", transfers, "windowDays");
        Object allowlist = transfers.get("allowlist");
        if (!(allowlist instanceof List<?> patterns)) {
            throw new IllegalArgumentException("transfers.toml: 'allowlist' must be an array of strings");
        }
        return new Config(source, target, (int) port, new AccountRegistry(list),
            new TransferRules(patterns.stream().map(String.class::cast).toList(), Math.toIntExact(windowDays)));
    }

    private static Map<String, Object> read(Path file) {
        try {
            return Toml.parse(Files.readString(file));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        } catch (Toml.TomlException e) {
            throw new IllegalArgumentException(file.getFileName() + ": " + e.getMessage(), e);
        }
    }

    private static void allowOnly(String where, Map<String, Object> table, Set<String> keys) {
        for (String k : table.keySet()) {
            if (!keys.contains(k)) {
                throw new IllegalArgumentException(where + ": unknown key '" + k + "'");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> table(String where, Map<String, Object> t, String key) {
        if (t.get(key) instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        throw new IllegalArgumentException(where + ": missing table [" + key + "]");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> tables(String where, Map<String, Object> t, String key) {
        if (t.get(key) instanceof List<?> l && l.stream().allMatch(Map.class::isInstance)) {
            return (List<Map<String, Object>>) l;
        }
        throw new IllegalArgumentException(where + ": missing [[" + key + "]] entries");
    }

    private static String string(String where, Map<String, Object> t, String key) {
        if (t.get(key) instanceof String s) {
            return s;
        }
        throw new IllegalArgumentException(where + ": '" + key + "' must be a string");
    }

    private static long integer(String where, Map<String, Object> t, String key) {
        if (t.get(key) instanceof Long l) {
            return l;
        }
        throw new IllegalArgumentException(where + ": '" + key + "' must be an integer");
    }
}
