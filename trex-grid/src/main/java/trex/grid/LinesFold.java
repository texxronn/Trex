package trex.grid;

import trex.core.CanonicalEvent;
import trex.web.Fold;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;

/** Keeps every journal line (the grid needs all versions for the Journal view). */
final class LinesFold implements Fold<GridData> {

    private static final AtomicLong GENERATIONS = new AtomicLong();

    private final long generation = GENERATIONS.incrementAndGet();
    private final List<CanonicalEvent> lines = new ArrayList<>();
    private final Set<String> ids = new HashSet<>();
    private final Set<String> accounts = new TreeSet<>();
    private GridData cached = new GridData(generation, List.of(), 0, List.of());

    @Override
    public void apply(CanonicalEvent line) {
        lines.add(line);
        ids.add(line.externalId());
        if (line.accountRef() != null) {
            accounts.add(line.accountRef());
        }
        if (line.toAccountRef() != null) {
            accounts.add(line.toAccountRef());
        }
    }

    @Override
    public GridData snapshot() {
        if (cached.lines().size() != lines.size()) {
            cached = new GridData(generation, List.copyOf(lines), ids.size(), List.copyOf(accounts));
        }
        return cached;
    }
}
