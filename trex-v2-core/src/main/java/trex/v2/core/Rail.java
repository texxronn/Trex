package trex.v2.core;

import java.util.Locale;

/**
 * How a leg moved (V2-PROPOSAL.md §9.9.C.4): the rail <b>method</b> declared by a transfer pattern.
 * The <b>direction</b> is never declared — it is the sign of the amount ({@code IN} for a credit,
 * {@code OUT} for a debit), so a rail can never contradict the amount.
 *
 * <p>A method is derived per leg, {@code EXTERNAL} legs included, so the Blotter can read a payment
 * received as {@code PAYID · IN} even when no contra exists. A pattern with {@code shape: false} is
 * rail-only: it tags a leg without placing it in the matching pool.
 */
public enum Rail {
    OSKO,
    PAYID,
    BPAY,
    BANK_TRANSFER;

    /** The stored and JSON form, e.g. {@code BANK_TRANSFER}. */
    public String wire() {
        return name();
    }

    public static Rail fromWire(String wire) {
        for (Rail r : values()) {
            if (r.name().equalsIgnoreCase(wire) || r.name().equals(wire.toUpperCase(Locale.ROOT))) {
                return r;
            }
        }
        throw new IllegalArgumentException("unknown rail method '" + wire + "'");
    }

    /** {@code IN} for a credit, {@code OUT} for a debit; zero is treated as a credit. */
    public static String direction(long amount) {
        return amount < 0 ? "OUT" : "IN";
    }
}
