package trex.v2.hub.api;

import java.time.LocalDate;

/**
 * One current fact of a detected candidate's series (V2-EXPECTED-UX-PLAN.md §7 Stage 2): the
 * evidence a person sees before confirming or ignoring the candidate. The facts are grouped by
 * the frozen {@code MerchantStem.stem} exactly as the detector grouped them — synthetic clearing
 * legs and {@code noop} rows are not part of a series. Amounts are signed as the facts are.
 */
public record CandidateFactJson(String externalId, LocalDate date, String accountRef, long amount,
                                String rawDescription) {}
