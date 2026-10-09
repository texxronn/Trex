package trex.v2.hub.api;

import java.time.LocalDate;
import java.util.List;

/**
 * One projectable unit as the egress needs it (V2-PROPOSAL.md §11.2, §11.6): a transfer row or a
 * posted EXTERNAL transaction, with everything the Firefly posting and its notes require. An
 * attestation, a leg, a pending observation and a retired fact never appear here.
 */
public record ProjectionUnit(String unitId, String unitKind, long n, String accountRef,
                             String toAccountRef, LocalDate date, long amount, String currency,
                             String category, String origin, String pairing, boolean retired,
                             boolean ineffective, String rawDescription, String unitHash,
                             List<String> legs) {}
