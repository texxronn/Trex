package trex.v2.egress.firefly;

import trex.v2.egress.hub.HubClient.HubUnit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Where an orphaned unit went (V2-SPEC.md §11.1). Since §4 hashes a transfer over its legs' current
 * ids, a re-parse re-mints the unit id: without this, Firefly gets an orphan and a create — a double
 * count until a human removes the orphan. Same kind: the group is re-keyed in place. Across kinds (a
 * pair formed or broke): reported beside its successors, never rewritten (D5).
 */
final class Successors {

    record Match(String orphanId, List<String> successorIds, boolean sameKind) {}

    private Successors() {}

    static Match of(String orphanId, List<String> orphanLegs, Map<String, String> resolved, List<HubUnit> units) {
        if (orphanId.startsWith("TRF-")) {
            Set<String> legs = Set.copyOf(orphanLegs.stream().map(l -> resolved.getOrDefault(l, l)).toList());
            if (legs.isEmpty()) {
                return new Match(orphanId, List.of(), false);
            }
            for (HubUnit u : units) {
                if (u.unitKind().equals("TRANSFER") && Set.copyOf(u.legs()).equals(legs)) {
                    return new Match(orphanId, List.of(u.unitId()), true);
                }
            }
            List<String> out = new ArrayList<>();
            for (HubUnit u : units) {
                if (u.unitKind().equals("EXTERNAL") && legs.contains(u.unitId())) {
                    out.add(u.unitId());
                }
            }
            out.sort(null);
            return new Match(orphanId, out, false);
        }
        String current = resolved.getOrDefault(orphanId, orphanId);
        for (HubUnit u : units) {
            if (u.unitKind().equals("EXTERNAL") && u.unitId().equals(current) && !current.equals(orphanId)) {
                return new Match(orphanId, List.of(current), true);
            }
            if (u.unitKind().equals("TRANSFER") && u.legs().contains(current)) {
                return new Match(orphanId, List.of(u.unitId()), false);
            }
        }
        return new Match(orphanId, List.of(), false);
    }
}
