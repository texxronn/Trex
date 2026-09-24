package trex.gateway;

import trex.core.CanonicalEvent;
import trex.gateway.grid.LinesFold;
import trex.gateway.ledger.LedgerFold;

/**
 * Applies each journal line to both folds and publishes them together (SPEC §5.4). The watcher
 * creates a fresh instance whenever it rebuilds from offset 0, so neither half can be left holding
 * state from a journal that was replaced underneath it.
 */
public final class CombinedFold implements Fold<JournalView> {

    private final LinesFold lines = new LinesFold();
    private final LedgerFold ledger = new LedgerFold();

    @Override
    public void apply(CanonicalEvent line) {
        lines.apply(line);
        ledger.apply(line);
    }

    @Override
    public JournalView snapshot() {
        return new JournalView(lines.snapshot(), ledger.snapshot());
    }
}
