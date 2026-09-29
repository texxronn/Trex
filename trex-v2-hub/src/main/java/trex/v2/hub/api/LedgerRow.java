package trex.v2.hub.api;

import java.time.LocalDate;

/**
 * One current transaction as the blotter shows it (V2-PROPOSAL.md §10.2): the fact plus its derived
 * pairing state, category (with provenance) and whether a review item names it.
 */
public record LedgerRow(String externalId, long n, String accountRef, LocalDate date, long amount,
                        long balance, String rawDescription, String leg, String transferId,
                        String category, String categoryOrigin, String ruleId, boolean hasReview) {}
