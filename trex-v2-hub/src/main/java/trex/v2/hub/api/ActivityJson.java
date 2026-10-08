package trex.v2.hub.api;

import java.time.LocalDate;

/**
 * One row of a commitment's activity (V2-EXPECTED-UX-PLAN.md §7 Stage 2): a current fact of the
 * series — for a detected candidate via its frozen stem, for a declared commitment via its
 * effective rules across **all history** (life-bounded at its end date when retired), so nothing
 * is limited to the twelve-month occurrence window. {@code matchedBy} is {@code rule} for a
 * declared match, null for a candidate group. Oldest first; amounts are signed as the facts are.
 */
public record ActivityJson(LocalDate date, String accountRef, Long amount, String rawDescription,
                           String externalId, String matchedBy) {}
