package trex.v2.hub.api;

import java.time.LocalDate;

/**
 * One member of a derived review cluster (POTENTIAL_DUP / RESTATEMENT, V2-PROPOSAL.md §9.9.F): the
 * subject rows themselves, so the queue can show what distinguishes them — the running balance, a
 * receipt, the verbatim text — rather than a summary count a person cannot act on.
 */
public record ReviewMember(String externalId, long n, LocalDate date, long amount, long balance,
                           String receipt, String rawDescription) {}
