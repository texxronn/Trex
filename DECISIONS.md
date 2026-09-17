# trex — resolved ambiguities

Resolutions to gaps and conflicts found while reviewing SPEC.md before
implementation. SPEC.md remains the authority; entries here fill in what it
leaves unspecified. Numbering (B1…) follows the pre-implementation review.

## Resolved

### B1 — WATERMARK control event representation
- `TypeHint` gains `WATERMARK`.
- A watermark advance is journaled as a `CanonicalEvent` with
  `typeHint = WATERMARK`, `externalId = "WM-" + accountRef + "-" + date(ISO)`,
  `n = ++highWaterN` like every other event (invariant 3: distinct
  `external_id` → assigned once, preserved on replay).
- Followers ignore WATERMARK events, so `n` in follower sinks has gaps;
  nothing may assume `n` is gap-free.

### B2 — Jackson annotation in trex-core
- trex-core depends on `com.fasterxml.jackson.core:jackson-annotations` only
  (no databind), so `@JsonPropertyOrder` stays on `CanonicalEvent`.

### B3 — Journal JSON byte format
- Null fields are written (not omitted).
- Non-TRANSFER events: `legIds = null`.
- `flags` is always an array; empty → `[]`.
- `LocalDate` and `Instant` serialize as ISO-8601 strings
  (`WRITE_DATES_AS_TIMESTAMPS` off).

### B4 — Identity hashing details (frozen contract)
- Canonical strings are encoded UTF-8 before SHA-256.
- Hex is lowercase; ids take the first 16 hex chars.
- `transferId(a, b) = "TRF-" + sha256("tr|" + min(a,b) + "|" + max(a,b))[:16]`.
- `rawDescription` is the CSV-unquoted field value, untrimmed.

### B5 — Materialize when source != target
- Source is authoritative. On every startup: byte-copy source → target
  (overwriting target), verify `sha256(source) == sha256(target)` else abort,
  truncate torn tail, open target for append. Exactly SPEC.md §3.2.
- Keeping source current is the operator's responsibility.

### B6 — Stage-1 tests vs CSV slices
- Stage 1 tests 1–3 use hand-built `Candidate` fixtures.
- ING-slice versions of tests 1 and 3 are added in stage 5.
- ING is the starter adapter template; CBA/BW adapters follow the same model
  in a later phase.

### B9 — `ingestedAt` and byte-identical journals
- A non-null `ingestedAt` is retained, never restamped.
- The sequencer stamps `ingestedAt` from an injected `java.time.Clock`.
- Tests 10 and 13 use a fixed `Clock`; they compare full journal bytes,
  `ingestedAt` included. `Candidate` is unchanged.

## Open

B1 sub-points (global watermark id; repeated watermark), B7, B8, B10–B24.
