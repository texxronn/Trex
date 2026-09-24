package trex.egress.firefly;

import trex.journal.Yaml;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which Firefly account a trex {@code accountRef} means, and what kind of account it is.
 * SPEC §5.8.
 * <p>
 * Keyed by <em>name</em>, never by Firefly's numeric id. A rebuilt Firefly instance changes every
 * id and no name, so an id-keyed mapping breaks on the first restore — precisely when hand-editing
 * config is least welcome. The id is resolved at startup against the live instance and is an
 * accelerator, not configuration.
 * <p>
 * {@code type} is not decoration. Firefly 6.7.3 refuses a {@code transfer} that crosses between an
 * asset and a liability, so the pair of types decides whether a movement is a transfer, a
 * withdrawal or a deposit ({@link Projection}).
 */
public record AccountMap(Map<String, Entry> byRef) {

    /** What an account is to Firefly. The two values Firefly distinguishes for our purposes. */
    public enum Kind { ASSET, LIABILITY }

    /**
     * @param name what Firefly calls it — the lookup key, and a label you may change freely
     * @param kind asset or liability, as Firefly classifies it
     * @param id   Firefly's numeric id, resolved at startup; null until then
     */
    public record Entry(String name, Kind kind, String id) {

        public Entry withId(String resolved) {
            return new Entry(name, kind, resolved);
        }
    }

    /** {@code firefly.yaml} as written. Bound strictly: an unknown key is a startup error (§6). */
    record File(Map<String, AccountEntry> accounts) {}

    record AccountEntry(String name, String type) {}

    public static AccountMap load(Path file) {
        File parsed = Yaml.read(file, File.class);
        String where = file.getFileName().toString();
        if (parsed.accounts() == null || parsed.accounts().isEmpty()) {
            throw new IllegalArgumentException(where + ": 'accounts' must map at least one ref");
        }
        Map<String, Entry> out = new LinkedHashMap<>();
        for (Map.Entry<String, AccountEntry> e : parsed.accounts().entrySet()) {
            String ref = e.getKey();
            AccountEntry a = e.getValue();
            if (a == null || a.name() == null || a.name().isBlank()) {
                throw new IllegalArgumentException(where + ": " + ref + " has no Firefly 'name'");
            }
            Kind kind = switch (a.type() == null ? "" : a.type()) {
                case "asset" -> Kind.ASSET;
                case "liability" -> Kind.LIABILITY;
                default -> throw new IllegalArgumentException(
                    where + ": " + ref + " type must be 'asset' or 'liability', not '" + a.type() + "'");
            };
            out.put(ref, new Entry(a.name().strip(), kind, null));
        }
        return new AccountMap(Map.copyOf(out));
    }

    public Entry get(String ref) {
        return byRef.get(ref);
    }

    /** Refs this map does not cover; projecting one of them would post into the wrong place. */
    public List<String> missing(List<String> refs) {
        return refs.stream().filter(r -> r != null && !byRef.containsKey(r)).distinct().sorted().toList();
    }

    /** The same map with Firefly's ids filled in, having matched every name against the instance. */
    public AccountMap resolved(Map<String, String> idsByName) {
        Map<String, Entry> out = new LinkedHashMap<>();
        byRef.forEach((ref, e) -> out.put(ref, e.withId(idsByName.get(e.name()))));
        return new AccountMap(Map.copyOf(out));
    }

    /** Names this map expects that the instance does not have — a rename, usually. */
    public List<String> unresolved() {
        return byRef.entrySet().stream()
            .filter(e -> e.getValue().id() == null)
            .map(e -> e.getKey() + " -> \"" + e.getValue().name() + "\"")
            .sorted()
            .toList();
    }
}
