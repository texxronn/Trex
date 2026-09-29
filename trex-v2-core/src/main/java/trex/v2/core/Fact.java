package trex.v2.core;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * A fact: what a source said, once, never rewritten (V2-PROPOSAL.md §6.1).
 *
 * <p>It stores only what the source said plus what identity needs. Deliberately absent:
 * {@code description} (pure function of {@link Clean}), {@code state}, {@code flags},
 * {@code transferKey}, {@code legIds}, {@code confidence}, {@code corrects}, {@code typeHint}
 * and {@code currency} — every one is a conclusion or an interpretation and is derived instead.
 *
 * <p>Identity is frozen (§8.3): {@code externalId} is minted from
 * {@code (accountRef, date, receipt)} or {@code (accountRef, date, amount, rawDescription, occ)}
 * with {@code rawDescription} verbatim.
 */
public record Fact(
    long n,
    int v,
    String externalId,
    String accountRef,
    LocalDate date,
    long amount,          // cents, signed
    long balance,         // cents — provenance only, never identity or semantics
    String rawDescription,
    String receipt,       // nullable
    int occ,
    Observation observation,
    String sourceType,
    Provenance provenance,
    String evidenceId,    // nullable; content-addressed evidence (§8.1)
    String parser,        // name/version, e.g. "ing-csv/3"
    Instant ingestedAt
) implements LogLine {

    /** The current fact version on the wire. */
    public static final int VERSION = 2;

    public Fact {
        require(v == VERSION, "fact v must be " + VERSION + ", not " + v);
        Objects.requireNonNull(externalId, "externalId");
        Objects.requireNonNull(accountRef, "accountRef");
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(rawDescription, "rawDescription");
        Objects.requireNonNull(observation, "observation");
        Objects.requireNonNull(sourceType, "sourceType");
        Objects.requireNonNull(provenance, "provenance");
        Objects.requireNonNull(ingestedAt, "ingestedAt");
        require(occ >= 0, "occ must be >= 0");
    }

    /** Convenience constructor with {@code v} fixed to {@link #VERSION}. */
    public Fact(long n, String externalId, String accountRef, LocalDate date, long amount, long balance,
                String rawDescription, String receipt, int occ, Observation observation,
                String sourceType, Provenance provenance, String evidenceId, String parser, Instant ingestedAt) {
        this(n, VERSION, externalId, accountRef, date, amount, balance, rawDescription, receipt, occ,
            observation, sourceType, provenance, evidenceId, parser, ingestedAt);
    }

    @Override
    public String kind() {
        return "fact";
    }

    /** A re-observation of the same id: content moved, so this is a new line, never a rewrite. */
    public Fact withN(long newN) {
        return new Fact(newN, v, externalId, accountRef, date, amount, balance, rawDescription, receipt,
            occ, observation, sourceType, provenance, evidenceId, parser, ingestedAt);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
