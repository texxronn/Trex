package trex.v2.hub.api;

import java.time.LocalDate;

/**
 * One current transaction as the blotter shows it (V2-PROPOSAL.md §10.2): the fact plus its derived
 * pairing state, category (with provenance), whether a review item names it, and its latest note
 * (§6.2) if any — so a row with an annotation is visible without opening the thread. When the fact
 * is an occurrence of a commitment, {@code commitmentId}/{@code commitmentName} carry the chip
 * (V2-COMMITMENTS-PLAN.md §2.8); several occurrences can share a lump payment's fact, and the
 * latest due date wins, deterministically.
 */
public record LedgerRow(String externalId, long n, String accountRef, LocalDate date, long amount,
                        long balance, String rawDescription, String leg, String role, String rail,
                        String transferId, String category, String categoryOrigin, String ruleId,
                        boolean hasReview, String latestNote, boolean synthetic,
                        String commitmentId, String commitmentName) {}
