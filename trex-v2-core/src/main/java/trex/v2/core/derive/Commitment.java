package trex.v2.core.derive;

import trex.v2.core.Hashes;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * A commitment row (V2-COMMITMENTS-PLAN.md §2.7 {@code commitment}): a named expectation of a
 * recurring money movement. Stage 1 produces only <b>detected candidates</b> — {@code origin}
 * {@link CommitmentOrigin#DETECTED}, id {@code cand|<hex>}, {@code name}/{@code declaredN}/
 * {@code retiredN} null — with the coverage status the facts imply.
 *
 * <p>{@code candidateKey} is the {@code MerchantStem.stem} the candidate grouped under. The key
 * names candidates for Review and {@code IGNORE_RECURRING} and is deliberately <b>not</b> a
 * commitment field (§2.3.1): it is null once a candidate is declared and named. A candidate's
 * {@code name} is null, because nothing has been named yet.
 *
 * <p>{@code direction} is the sign of the series, {@code out} (−) or {@code in} (+). Amounts are
 * stored signed, exactly as the facts are; the direction is what gives them meaning.
 *
 * <p>{@code previousAmount}, {@code changePct} and {@code changeDate} describe the latest price
 * step (§2.4): the amount before the current price, and the size and date of the change. They are
 * null when the series never stepped. {@code steps} is the whole step timeline — the opening price
 * point and one point per step; the amounts between steps did not change.
 *
 * <p>{@code stateHash} is the row's content hash (§2.7): a canonical serialisation of the fields
 * that make up the candidate, so a rebuild re-derives the same value. It is a method, not a
 * component, so no caller can set it out of step with the content.
 */
public record Commitment(
    String commitmentId,
    String candidateKey,
    String name,
    CommitmentOrigin origin,
    String direction,
    Cadence cadence,
    AmountKind amountKind,
    CommitmentKind kind,
    CommitmentStatus status,
    LocalDate firstDate,
    LocalDate lastDate,
    LocalDate anchorDate,
    Long currentAmount,
    Long previousAmount,
    Double changePct,
    LocalDate changeDate,
    List<PriceStep> steps,
    Long costToDate,
    Long annualised,
    int occurrenceCount,
    Double regularity,
    boolean variable,
    int arrearsCount,
    Long arrearsAmount,
    Long declaredN,
    Long retiredN,
    LocalDate endedAt) {

    public static final String OUT = "out";
    public static final String IN = "in";

    public Commitment {
        steps = steps == null ? List.of() : List.copyOf(steps);
        if (!OUT.equals(direction) && !IN.equals(direction)) {
            throw new IllegalArgumentException("direction must be 'in' or 'out', not '" + direction + "'");
        }
    }

    /** True when the commitment is an outflow; the sign of its facts. */
    public boolean outgoing() {
        return OUT.equals(direction);
    }

    /** The measured span, first to last occurrence, in days. */
    public long spanDays() {
        return ChronoUnit.DAYS.between(firstDate, lastDate);
    }

    /**
     * One price point on the cost timeline (§2.4): the series' opening amount, then a new point
     * for every step — same-day multiples are one occurrence, so they never appear as a step. On
     * the opening point {@code previousAmount} and {@code changePct} are null; on every step they
     * are the amount changed from and the change as a percentage of it (signed magnitudes).
     */
    public record PriceStep(LocalDate date, long amount, Long previousAmount, Double changePct) {}

    /** The row's content hash; never stored on the record, so it cannot drift from the content. */
    public String stateHash() {
        StringBuilder canonical = new StringBuilder();
        canonical.append(commitmentId).append('|')
            .append(candidateKey).append('|')
            .append(name).append('|')
            .append(origin.wire()).append('|')
            .append(direction).append('|')
            .append(cadence.wire()).append('|')
            .append(amountKind.wire()).append('|')
            .append(kind.wire()).append('|')
            .append(status.wire()).append('|')
            .append(firstDate).append('|')
            .append(lastDate).append('|')
            .append(anchorDate).append('|')
            .append(currentAmount).append('|')
            .append(previousAmount).append('|')
            .append(changePct).append('|')
            .append(changeDate).append('|')
            .append(costToDate).append('|')
            .append(annualised).append('|')
            .append(occurrenceCount).append('|')
            .append(regularity).append('|')
            .append(variable).append('|')
            .append(arrearsCount).append('|')
            .append(arrearsAmount).append('|')
            .append(declaredN).append('|')
            .append(retiredN).append('|')
            .append(endedAt).append('|');
        for (PriceStep step : steps) {
            canonical.append(step.date()).append(':').append(step.amount()).append(':')
                .append(step.previousAmount()).append(':').append(step.changePct()).append(';');
        }
        return Hashes.sha256(canonical.toString());
    }
}
