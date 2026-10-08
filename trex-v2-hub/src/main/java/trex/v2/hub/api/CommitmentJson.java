package trex.v2.hub.api;

import java.time.LocalDate;
import java.util.List;

/**
 * One row of the commitment registry (V2-COMMITMENTS-PLAN.md §2.7, §2.8): the faces a person
 * curates and the derived cost, coverage and arrears figures, the effective rule set, the next
 * {@code due} occurrence date, and the commitment's note thread (oldest first, like {@code NOTE}).
 * Candidates ({@code origin=detected}, id {@code cand|<hex>}) and declared rows share the shape; a
 * candidate's {@code name} is null, its {@code stem} (the grouping {@code MerchantStem.stem}) is
 * set, and it carries no rules or occurrences. {@code stem} is null once a candidate is declared —
 * the name replaces it, exactly as the key stops naming the row in Review.
 *
 * <p>All amounts are signed exactly as the facts and {@code commitment} hold them: a {@code −} for
 * an outflow, a {@code +} for income.
 */
public record CommitmentJson(String commitmentId, String name, String stem, String origin, String direction,
                             String cadence, String amountKind, String kind, String status,
                             LocalDate firstDate, LocalDate lastDate, LocalDate anchorDate,
                             Long currentAmount, Long previousAmount, Double changePct,
                             LocalDate changeDate, int occurrenceCount, Double regularity,
                             boolean variable, int arrearsCount, Long arrearsAmount,
                             Long declaredN, Long retiredN, LocalDate endedAt,
                             List<RuleJson> rules, LocalDate nextDue, List<NoteJson> notes) {

    /** One rule of the effective declaration: a regex, optionally scoped to one account ref. */
    public record RuleJson(String match, String account) {}
}
