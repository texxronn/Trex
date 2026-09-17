package trex.core.state;

import trex.core.CanonicalEvent;
import trex.core.EventState;
import trex.core.TypeHint;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Projectable-unit selector over latest lines. SPEC §3.4 collapse, §7 test 11. */
public final class Projection {

    private Projection() {}

    /** TRANSFER lines, plus EXTERNAL transactions that are not a leg of some TRANSFER. */
    public static List<CanonicalEvent> projectableUnits(List<CanonicalEvent> latestLines) {
        Set<String> legs = new HashSet<>();
        latestLines.stream().filter(l -> l.typeHint() == TypeHint.TRANSFER).forEach(l -> legs.addAll(l.legIds()));
        return latestLines.stream()
            .filter(l -> l.typeHint() == TypeHint.TRANSFER
                || (l.state() == EventState.EXTERNAL && !legs.contains(l.externalId())))
            .toList();
    }
}
