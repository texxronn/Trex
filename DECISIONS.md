# trex — resolved ambiguities

Resolutions to gaps and conflicts found while reviewing SPEC.md before
implementation. SPEC.md remains the authority except where an entry below
explicitly **amends** it. Numbering (B1…) follows the pre-implementation review.

## Amendments to SPEC.md

### T — Transfer semantics: versioned journal, no aging (amends §0.3, §0.1, §2.2, §2.3, §3.2, §3.4, §3.5, §5.2, §5.3, tests 11 & 14)

**Model**
- The journal is a log of record *versions*. When a transaction's state
  changes, the sequencer appends a full copy of it with a new `n` and the new
  `state`.
- **§0.3 amended:** `n` is the journal record sequence — unique per line,
  strictly increasing. One `external_id` may appear on several lines.
- **§0.1 clarified:** `external_id` remains the sole identity of a
  *transaction*; it is not unique per journal line. The line key is `n`.
- **§2.2 amended:** `stateSnapshot` is renamed `state`. The line with the
  highest `n` for an `external_id` is **authoritative** current state.
- **§3.2 amended:** fold = `current[externalId] = line with highest n`. No
  state derivation from `legIds`, no watermark.

**No aging**
- Removed: aging, watermarks, `POST /period-complete`, WATERMARK control
  event, `EventState.AGED_OUT`, `Flag.LATE_ARRIVAL`.
- A HELD transaction leaves HELD only by automatic match or manual decision.

**States** (`EventState`): `HELD`, `MATCHED`, `REVIEW`, `EXTERNAL`.
(`EMITTED`, `AGED_OUT` removed.)

**Re-append rules**
1. A re-appended line is identical to the previous line for that
   `external_id` except `n` and `state` (`ingestedAt`, `balance`,
   `description`, … unchanged).
2. Allowed transitions: HELD→MATCHED, HELD→EXTERNAL, REVIEW→MATCHED,
   REVIEW→EXTERNAL. Any other requested transition is `Rejected`, nothing
   appended.
3. Dedup (`seenIds`) stays keyed by `external_id`; balance drift check uses the
   first line.
4. Line order inside one `appendBatch`: new legs, then re-appended legs, then
   the TRANSFER line. All in one atomic append.
5. Both legs in the same batch: each written once, directly `MATCHED` — no
   HELD line.

**TRANSFER line**
- `external_id = transferKey = TRF-…`, `state = MATCHED`,
  `legIds = [legA, legB]`, `n` after its legs.
- `accountRef` = account of the negative-amount (from) leg.
- New field **`toAccountRef`** = account of the positive-amount (to) leg;
  `null` on non-TRANSFER lines. JSON position: immediately after `accountRef`.
- New optional field **`comment`** (nullable string): free-text note,
  supplied via `POST /decisions` when a transfer is confirmed manually; `null`
  otherwise. JSON position: immediately before `ingestedAt`. Never part of
  identity (`transferId` is from leg ids only).
- TRANSFER lines are the only projectable unit for transfers.

**Manual resolution** (`POST /decisions`)
- Mark external: re-append the leg with `state = EXTERNAL`.
- Confirm transfer (manual pairing of two legs): same output as an automatic
  match — re-append both legs `MATCHED` + one TRANSFER line.
- The manual resolver is a separate service: a journal follower that calls the
  sequencer API. It is not one of the five phase-1 modules.
- **Phase 1 (T-b):** `POST /decisions` is fully implemented in trex-sequencer
  (stage 4), not a stub. Resolution is available over the sequencer API.
- `GET /held`, `GET /review` and `POST /decisions` form the resolution
  workflow; all three are fully implemented in phase 1.

**`POST /decisions` contract (T-c)**
- Request: `{ "allOrNone": false, "decisions": [ ... ] }`, each decision
  `{ "decisionRef", "action", ... }`:
  - `MARK_EXTERNAL`: `externalId`.
  - `CONFIRM_TRANSFER`: `legA`, `legB`, optional `comment`.
- Response: `{ batchHandle, batchStatus, results }` (same envelope as
  `/candidates`). Success → `Resolved(decisionRef, externalId, n)` (leg id for
  MARK_EXTERNAL, `TRF-…` id for CONFIRM_TRANSFER); failure →
  `Rejected(decisionRef, reason)`. `allOrNone` as in `/candidates`.
- All accepted decisions in one request = one atomic `appendBatch`, under the
  single write lock.
- Rejected when: unknown `external_id`; current state not HELD/REVIEW; for
  CONFIRM_TRANSFER also: same leg twice, same account, different currency,
  amounts not equal-and-opposite, or a leg already used by an earlier decision
  in the same request. `windowDays` is not enforced (human override).
- Output: MARK_EXTERNAL → leg re-appended `EXTERNAL`. CONFIRM_TRANSFER → both
  legs re-appended `MATCHED` + TRANSFER line with `confidence = EXACT`,
  `provenance = AUTHORED`, `comment`.
- Not in phase 1: "keep-both" (depends on B7) and "MAN-" manual entries.

**`GET /held`, `GET /review`**
- Return the latest line (`CanonicalEvent`) of every transaction whose current
  state is HELD / REVIEW respectively, ordered by `n`. Served from the
  published in-memory snapshot; gzip per §3.6.

**Rules**
- Deciding whether a new candidate goes to `MATCHED`, `HELD`, `REVIEW` or
  `EXTERNAL` lives in trex-sequencer and will evolve into a tunable rule set.
  Phase 1's rule set is the §3.4 allowlist + tiers.
- Rule changes affect only future ingests; replay uses stored `state`, never
  re-evaluates rules.

**Followers**
- §5.2 archive: mirrors every line; idempotency by `n`, not `external_id`.
- §5.3 sqlite: `INSERT … ON CONFLICT(external_id) DO UPDATE … WHERE
  excluded.n > events.n` (still idempotent, invariant 5). `state_snapshot`
  column becomes `state`; add `to_account_ref`.

**Tests**
- Test 11: split-batch transfer = 4 lines (leg A HELD, leg B, leg A
  re-appended, TRANSFER); same-batch = 3 lines. Projectable selector returns
  only the TRANSFER.
- Test 14: replay reproduces latest state for every `external_id`.

## Resolved

### B1 — WATERMARK control event — *superseded by T (no watermarks)*

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

### B8 — Review set — *resolved by T*: review = transactions whose latest line has `state = REVIEW`.

### B9 — `ingestedAt` and byte-identical journals
- A non-null `ingestedAt` is retained, never restamped.
- The sequencer stamps `ingestedAt` from an injected `java.time.Clock`.
- Tests 10 and 13 use a fixed `Clock`; they compare full journal bytes,
  `ingestedAt` included. `Candidate` is unchanged.

### B12, B13, B25, B26 — watermark / aging details — *moot under T*

## Open

- T-a: whether `comment` is also allowed on a manual mark-external re-append
  (currently TRANSFER only; re-append rule 1 would need an exception).
- B7, B10 (remaining TRANSFER fields), B11, B14–B23.
