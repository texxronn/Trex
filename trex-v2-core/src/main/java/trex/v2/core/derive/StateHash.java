package trex.v2.core.derive;

import trex.v2.core.Hashes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The content hash of a period's derived units (V2-PROPOSAL.md §9.5, §9.9.G).
 *
 * <p>It hashes a canonical, ordered serialisation of the period's units: unit id, kind, account,
 * date, amount, currency, category, origin, pairing state, retired and ineffective. Excluded, by
 * construction: ages and stale badges, display formatting, {@code n} ordering noise, and the
 * revisions below. So a version bump re-evaluates an acknowledged period only when something
 * actually moved — "you never redo a week that did not move" holds across upgrades too.
 */
public final class StateHash {

    private StateHash() {}

    /** The hash of every unit dated within {@code period}. */
    public static String forPeriod(List<Unit> units, String period) {
        List<Unit> inPeriod = new ArrayList<>();
        for (Unit u : units) {
            if (Period.contains(period, u.date())) {
                inPeriod.add(u);
            }
        }
        return of(inPeriod);
    }

    /** The canonical hash of a unit set, order-independent at the call site (we sort). */
    public static String of(List<Unit> units) {
        List<Unit> sorted = new ArrayList<>(units);
        sorted.sort(Comparator.comparing(Unit::date).thenComparing(Unit::unitId));
        StringBuilder sb = new StringBuilder();
        for (Unit u : sorted) {
            sb.append(u.unitId()).append('|')
              .append(u.unitKind()).append('|')
              .append(u.accountRef()).append('|')
              .append(u.date()).append('|')
              .append(u.amount()).append('|')
              .append(u.currency()).append('|')
              .append(u.category()).append('|')
              .append(u.origin()).append('|')
              .append(u.pairing()).append('|')
              .append(u.retired()).append('|')
              .append(u.ineffective()).append('\n');
        }
        return Hashes.sha256(sb.toString());
    }
}
