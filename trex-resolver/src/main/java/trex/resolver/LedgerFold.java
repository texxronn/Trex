package trex.resolver;

import trex.core.CanonicalEvent;
import trex.core.state.Ledger;
import trex.core.state.LedgerView;
import trex.web.Fold;

/** The resolver's journal view: the shared ledger fold (current state, HELD, REVIEW). */
final class LedgerFold implements Fold<LedgerView> {

    private final Ledger ledger = new Ledger();

    @Override
    public void apply(CanonicalEvent line) {
        ledger.apply(line);
    }

    @Override
    public LedgerView snapshot() {
        return ledger.snapshot();
    }
}
