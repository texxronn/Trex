package trex.v2.core;

/** How a declared commitment is paid (V2-SPEC.md §5). */
public enum Payment {
    AUTO("auto"),
    MANUAL("manual");

    private final String wire;

    Payment(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static Payment fromWire(String wire) {
        for (Payment payment : values()) {
            if (payment.wire.equals(wire)) {
                return payment;
            }
        }
        throw new IllegalArgumentException("unknown payment: " + wire);
    }
}
