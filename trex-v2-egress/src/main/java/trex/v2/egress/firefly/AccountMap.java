package trex.v2.egress.firefly;

import trex.v2.log.Yaml;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which Firefly account a trex {@code accountRef} means, and whether Firefly calls it an asset or a
 * liability (V2-PROPOSAL.md §11.3). Keyed by <em>name</em>, never by Firefly's numeric id: a
 * rebuilt instance changes every id and no name. The id is resolved at startup and is an
 * accelerator, not configuration.
 */
public record AccountMap(Map<String, Entry> byRef) {

    public enum Kind { ASSET, LIABILITY }

    public record Entry(String name, Kind kind, String id) {

        public Entry withId(String resolved) {
            return new Entry(name, kind, resolved);
        }
    }

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
            AccountEntry a = e.getValue();
            if (a == null || a.name() == null || a.name().isBlank()) {
                throw new IllegalArgumentException(where + ": " + e.getKey() + " has no Firefly 'name'");
            }
            Kind kind = switch (a.type() == null ? "" : a.type()) {
                case "asset" -> Kind.ASSET;
                case "liability" -> Kind.LIABILITY;
                default -> throw new IllegalArgumentException(
                    where + ": " + e.getKey() + " type must be 'asset' or 'liability', not '" + a.type() + "'");
            };
            out.put(e.getKey(), new Entry(a.name().strip(), kind, null));
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

    /** The Firefly ids of our own accounts: on a read-back, these sides compare by id, others by name. */
    public java.util.Set<String> ids() {
        java.util.Set<String> out = new java.util.TreeSet<>();
        byRef.values().forEach(e -> {
            if (e.id() != null) {
                out.add(e.id());
            }
        });
        return out;
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
