package trex.gateway;

import trex.core.state.LedgerView;
import trex.gateway.grid.GridData;

/**
 * One fold, both halves of the service. SPEC §5.4.
 * <p>
 * Browsing needs every line in order ({@link GridData}); the resolution workflow needs the latest
 * line per {@code externalId} ({@link LedgerView}). They were two processes reading the same file
 * twice; here one pass feeds both, and both always describe the same instant of the journal —
 * which a reader switching tabs would otherwise have no guarantee of.
 */
public record JournalView(GridData grid, LedgerView ledger) {}
