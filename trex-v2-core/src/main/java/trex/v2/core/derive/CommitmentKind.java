package trex.v2.core.derive;

import java.util.Locale;

/**
 * What a commitment is for (V2-COMMITMENTS-PLAN.md §2.1; V2-PROPOSAL.md §6.11). Detection cannot
 * infer the label, so a detected candidate is {@code other} until a person declares what it is.
 */
public enum CommitmentKind {
    SUBSCRIPTION,
    SERVICES,
    BILL,
    INSURANCE,
    FEE,
    TAX,
    INCOME,
    INTEREST_EARNED,
    INTEREST_PAID,
    LOAN,
    OTHER;

    /** The wire and derived-table form, e.g. {@code subscription}. */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static CommitmentKind fromWire(String wire) {
        return switch (wire) {
            case "subscription" -> SUBSCRIPTION;
            case "services" -> SERVICES;
            case "bill" -> BILL;
            case "insurance" -> INSURANCE;
            case "fee" -> FEE;
            case "tax" -> TAX;
            case "income" -> INCOME;
            case "interest_earned" -> INTEREST_EARNED;
            case "interest_paid" -> INTEREST_PAID;
            case "loan" -> LOAN;
            case "other" -> OTHER;
            default -> throw new IllegalArgumentException("unknown commitment kind '" + wire + "'");
        };
    }
}
