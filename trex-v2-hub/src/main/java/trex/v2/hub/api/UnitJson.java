package trex.v2.hub.api;

import java.time.LocalDate;

/** A projectable unit (V2-PROPOSAL.md §9.9.G): the egress's view, served by the hub. */
public record UnitJson(String unitId, String unitKind, String accountRef, LocalDate date, long amount,
                       String currency, String category, String origin, String pairing,
                       boolean retired, boolean ineffective) {}
