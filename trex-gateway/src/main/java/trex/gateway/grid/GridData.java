package trex.gateway.grid;

import trex.core.CanonicalEvent;

import java.util.List;

/**
 * Immutable snapshot of the journal for the grid: every line in n order, plus head info.
 * {@code generation} changes whenever the watcher rebuilt its fold, so cached query results never
 * outlive the data they were computed from.
 */
public record GridData(long generation, List<CanonicalEvent> lines, int transactions, List<String> accounts) {

    public long n() {
        return lines.isEmpty() ? 0 : lines.getLast().n();
    }
}
