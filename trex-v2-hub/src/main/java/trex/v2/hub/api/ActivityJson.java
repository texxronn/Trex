package trex.v2.hub.api;

import java.time.LocalDate;

/**
 * One row of a commitment's activity (V2-EXPECTED-UX-PLAN.md §7 Stage 2): for a detected
 * candidate, the current facts of its series ({@code status} and {@code matchedBy} null); for a
 * declared commitment, its materialised occurrences joined to the fact each carries — the status,
 * the summed movement ({@code amount} null when nothing landed), and the matched fact's account
 * and description. Oldest first; amounts are signed as the facts are.
 */
public record ActivityJson(LocalDate date, String status, String accountRef, Long amount,
                           String rawDescription, String externalId, String matchedBy) {}
