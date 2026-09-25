package trex.category;

import trex.core.CanonicalEvent;
import trex.core.TypeHint;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Which transactions are structurally TRANSFER: the TRANSFER lines, plus their legs. SPEC §5.6. */
public final class Transfers {

    private Transfers() {}

    public static Set<String> ids(List<CanonicalEvent> latestLines) {
        Set<String> ids = new HashSet<>();
        for (CanonicalEvent line : latestLines) {
            if (line.typeHint() == TypeHint.TRANSFER) {
                ids.add(line.externalId());
                if (line.legIds() != null) {
                    ids.addAll(line.legIds());
                }
            }
        }
        return Set.copyOf(ids);
    }
}
