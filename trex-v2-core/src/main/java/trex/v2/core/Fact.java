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
 * The header (§6) lives in the {@link Envelope}: {@code n}, {@code kind}, {@code v}, {@code atMs},
 * {@code env}, {@code source}, {@code target}.
 *
 * <p>Identity is frozen (§8.3): {@code externalId} is minted from
 * {@code (accountRef, date, receipt)} or {@code (accountRef, date, amount, rawDescription, occ)}
 * with {@code rawDescription} verbatim.
 */
public record Fact(
    Envelope envelope,
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
    String parser         // name/version, e.g. "ing-csv/3"
) implements LogLine {

    /** The namespaced wire kind. */
    public static final String KIND = "trex.fact";

    public Fact {
        Envelope.require(envelope);
        Objects.requireNonNull(externalId, "externalId");
        Objects.requireNonNull(accountRef, "accountRef");
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(rawDescription, "rawDescription");
        Objects.requireNonNull(observation, "observation");
        Objects.requireNonNull(sourceType, "sourceType");
        Objects.requireNonNull(provenance, "provenance");
        require(occ >= 0, "occ must be >= 0");
    }

    /** The ingest instant — the envelope's {@code atMs}. */
    public Instant ingestedAt() {
        return envelope.instant();
    }

    /** Quick construction with a default header — tests and the v1 importer. */
    public Fact(long n, String externalId, String accountRef, LocalDate date, long amount, long balance,
                String rawDescription, String receipt, int occ, Observation observation,
                String sourceType, Provenance provenance, String evidenceId, String parser, Instant ingestedAt) {
        this(Envelope.stamped(n, KIND, ingestedAt), externalId, accountRef, date, amount, balance,
            rawDescription, receipt, occ, observation, sourceType, provenance, evidenceId, parser);
    }

    /** A re-observation of the same id: content moved, so this is a new line, never a rewrite. */
    public Fact withN(long newN) {
        Envelope e = envelope;
        return new Fact(new Envelope(newN, e.kind(), e.v(), e.atMs(), e.env(), e.source(), e.target()),
            externalId, accountRef, date, amount, balance, rawDescription, receipt, occ,
            observation, sourceType, provenance, evidenceId, parser);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
