package trex.core;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** Journal record — one JSONL line. SPEC §2.2. */
@JsonPropertyOrder({ "n","externalId","accountRef","toAccountRef","currency","date","amount","balance",
    "description","rawDescription","typeHint","transferKey","legIds","corrects","state","confidence",
    "flags","provenance","sourceType","receipt","counterpartyBsb","counterpartyAcct",
    "foreignAmount","foreignCurrency","comment","ingestedAt" })
public record CanonicalEvent(
    long n,                   // journal line sequence (§0.3)
    String externalId,
    String accountRef,        // TRANSFER: the from (negative-amount) leg's account
    String toAccountRef,      // TRANSFER only: the to (positive-amount) leg's account; else null
    String currency,          // stamped from registry (account attribute; no conversion)
    LocalDate date,
    long amount,              // cents. Signed on transaction lines; TRANSFER: absolute value
    long balance,             // cents. PROVENANCE/RECONCILIATION ONLY. TRANSFER: 0
    String description,       // cleaned
    String rawDescription,
    TypeHint typeHint,
    String transferKey,       // nullable
    List<String> legIds,      // TRANSFER only: [fromLeg, toLeg]; else null
    String corrects,          // nullable; external_id this reverses/corrects
    EventState state,         // latest line (highest n) per externalId is authoritative
    Confidence confidence,    // nullable; set on TRANSFER lines
    List<Flag> flags,         // never null; empty = []
    Provenance provenance,
    String sourceType,
    String receipt,           // nullable
    String counterpartyBsb,   // nullable
    String counterpartyAcct,  // nullable
    Long foreignAmount,       // nullable; phase 2
    String foreignCurrency,   // nullable; phase 2
    String comment,           // nullable; from a POST /decisions decision
    Instant ingestedAt        // stamped on first append; never restamped
) {
    public CanonicalEvent {
        Objects.requireNonNull(externalId, "externalId");
        Objects.requireNonNull(typeHint, "typeHint");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(flags, "flags");
        flags = List.copyOf(flags);
        legIds = legIds == null ? null : List.copyOf(legIds);
    }

    /**
     * A re-appended version of this line (SPEC §3.2 re-append rule 1): identical except
     * {@code n}, {@code state}, {@code flags} and {@code comment}.
     */
    public CanonicalEvent reappend(long newN, EventState newState, List<Flag> newFlags, String newComment) {
        return new CanonicalEvent(newN, externalId, accountRef, toAccountRef, currency, date, amount, balance,
            description, rawDescription, typeHint, transferKey, legIds, corrects, newState, confidence,
            newFlags, provenance, sourceType, receipt, counterpartyBsb, counterpartyAcct,
            foreignAmount, foreignCurrency, newComment, ingestedAt);
    }
}
