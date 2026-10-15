package trex.v2.egress.ezb;

import trex.v2.log.Yaml;

import java.nio.file.Path;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which ezbookkeeping account a trex {@code accountRef} means, and the zone the view stamps onto
 * every row (V2-SPEC.md §11.2). Modelled on {@link trex.v2.egress.firefly.AccountMap}: the
 * Firefly/ezbookkeeping vocabulary stays out of the spine, and {@code deploy/config/ezb.yaml} is
 * keyed by the permanent ref.
 *
 * <p>{@code category} is <b>presentation only</b> — an ezbookkeeping account category, never a trex
 * category (no category is ever a property of a transaction, §1.7). The zone is per file, not per
 * account, and is what makes a unit date deterministic at import time.
 *
 * <p>ezbookkeeping account categories: {@code 1} cash, {@code 2} checking, {@code 3} credit card,
 * {@code 4} virtual, {@code 5} debt, {@code 6} receivables, {@code 7} investment, {@code 8} savings,
 * {@code 9} CD (V2-EZB-VIEW-PLAN.md §2).
 *
 * <p>{@code categoryGroups} is the optional {@code categories} map: a trex master category to the
 * ezbookkeeping <em>primary</em> its child sits under, so the charts aggregate the expense side over
 * several primaries instead of one {@code trex-expense} (§11.2). Missing keys are the caller's
 * default, never an error — the map is a grouping, not a coverage obligation.
 */
public record EzbMap(Map<String, Entry> byRef, ZoneId zone, Map<String, String> categoryGroups) {

    /** A mapped account: its ezbookkeeping display name and its account category 1..9. */
    public record Entry(String name, int category) {}

    record File(String zone, Map<String, AccountEntry> accounts, Map<String, String> categories) {}

    record AccountEntry(String name, int category) {}

    /** Without a group map: every expense child under the type's default primary. */
    public EzbMap(Map<String, Entry> byRef, ZoneId zone) {
        this(byRef, zone, Map.of());
    }

    public static EzbMap load(Path file) {
        File parsed = Yaml.read(file, File.class);
        String where = file.getFileName().toString();
        if (parsed.zone() == null || parsed.zone().isBlank()) {
            throw new IllegalArgumentException(where + ": 'zone' must name an IANA zone");
        }
        ZoneId zone;
        try {
            zone = ZoneId.of(parsed.zone().strip());
        } catch (java.time.DateTimeException e) {
            throw new IllegalArgumentException(where + ": 'zone' is not an IANA zone: '" + parsed.zone() + "'");
        }
        if (parsed.accounts() == null || parsed.accounts().isEmpty()) {
            throw new IllegalArgumentException(where + ": 'accounts' must map at least one ref");
        }
        Map<String, Entry> out = new LinkedHashMap<>();
        for (Map.Entry<String, AccountEntry> e : parsed.accounts().entrySet()) {
            AccountEntry a = e.getValue();
            if (a == null || a.name() == null || a.name().isBlank()) {
                throw new IllegalArgumentException(where + ": " + e.getKey() + " has no ezbookkeeping 'name'");
            }
            if (a.category() < 1 || a.category() > 9) {
                throw new IllegalArgumentException(where + ": " + e.getKey() + " category must be 1..9, not '"
                    + a.category() + "'");
            }
            out.put(e.getKey(), new Entry(a.name().strip(), a.category()));
        }
        Map<String, String> groups = new LinkedHashMap<>();
        if (parsed.categories() != null) {
            for (Map.Entry<String, String> group : parsed.categories().entrySet()) {
                if (group.getKey() == null || group.getKey().isBlank()) {
                    throw new IllegalArgumentException(where + ": a 'categories' key must name a trex category");
                }
                if (group.getValue() == null || group.getValue().isBlank()) {
                    throw new IllegalArgumentException(where + ": " + group.getKey()
                        + " has no ezbookkeeping primary 'name'");
                }
                groups.put(group.getKey().strip(), group.getValue().strip());
            }
        }
        return new EzbMap(Map.copyOf(out), zone, Map.copyOf(groups));
    }

    public Entry get(String ref) {
        return byRef.get(ref);
    }

    /** Refs this map does not cover; importing one of them would post into the wrong place. */
    public List<String> missing(List<String> refs) {
        return refs.stream().filter(r -> r != null && !byRef.containsKey(r)).distinct().sorted().toList();
    }

    /** The mapped refs, for a plan that lists what the view will carry. */
    public Set<String> refs() {
        return byRef.keySet();
    }
}
