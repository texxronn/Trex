package trex.v2.core;

import java.time.Instant;
import java.util.Objects;

/**
 * The uniform envelope every log line carries (V2-PROPOSAL.md §6): `n`, namespaced `kind`, `v`,
 * `atMs`, `env`, `source`, `target`, before the kind-specific body. `atMs` is epoch millis (UTC)
 * and is the only time logic reads; `env`/`source`/`target` are 8-char codes the writer stamps.
 */
public record Envelope(long n, String kind, int v, long atMs, String env, String source, String target) {

    /** The line-format version this code writes. */
    public static final int VERSION = 1;

    public Envelope {
        require(n >= 0, "n must be >= 0");
        require(kind != null && !kind.isBlank(), "kind is required");
        require(v == VERSION, "line format version must be " + VERSION + ", not " + v);
        code(env, "env", false);
        code(source, "source", false);
        code(target, "target", true);
    }

    /** {@code env}/{@code source}/{@code target} are exactly 8 chars of {@code [A-Za-z0-9_ ]}. */
    private static void code(String value, String name, boolean mayBeBlank) {
        if (value == null || value.length() != 8) {
            throw new IllegalArgumentException(name + " must be exactly 8 chars, got "
                + (value == null ? "null" : "'" + value + "'"));
        }
        for (char c : value.toCharArray()) {
            if (!(Character.isLetterOrDigit(c) || c == '_' || c == ' ')) {
                throw new IllegalArgumentException(name + " has an illegal char: '" + c + "'");
            }
        }
        if (!mayBeBlank && value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    public Instant instant() {
        return Instant.ofEpochMilli(atMs);
    }

    /**
     * A default dev header, for quick construction (tests, the v1 importer) where the source and
     * environment are not the point. Production code builds an {@link Envelope} explicitly so its
     * {@code source}/{@code env} are real.
     */
    public static Envelope stamped(long n, String kind, Instant at) {
        return new Envelope(n, kind, VERSION, at.toEpochMilli(), "DEV1    ", "TST_0001", "        ");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    /** Not used for logic; present so records can null-check a missing envelope. */
    public static Envelope require(Envelope envelope) {
        return Objects.requireNonNull(envelope, "envelope");
    }
}
