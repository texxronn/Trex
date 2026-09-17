# trex — resolved ambiguities

Rationale log for gaps and conflicts found while reviewing SPEC.md before
implementation. **All entries below are folded into SPEC.md, which is the sole
authority.** This file records *why*. Numbering (B1…) follows the
pre-implementation review.

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
   `external_id` except `n`, `state`, `flags` and `comment` (`ingestedAt`,
   `balance`, `description`, … unchanged). `comment` on a re-appended line is
   the comment of the decision that caused it, else `null` (not carried over).
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
- `external_id = transferKey = TRF-…` (`TRF-<receipt>` for T1,
  `transferId(a,b)` otherwise, per SPEC §2.4), `typeHint = TRANSFER`,
  `state = MATCHED`, `legIds = [fromLeg, toLeg]`, `n` after its legs.
- `accountRef` = account of the negative-amount (from) leg.
- `amount` = absolute value (positive cents); direction is given by
  `accountRef` → `toAccountRef`.
- `date`, `currency`, `description`, `rawDescription`, `source` copied from the
  from leg.
- `balance = 0` (not meaningful for a transfer).
- `receipt` = shared receipt for T1, else `null`.
- `confidence` = `EXACT` for T1 or manual pairing, `HIGH` for T3.
- `provenance` = `BANK` for automatic match, `AUTHORED` for manual pairing.
- `flags = []`; `corrects`, `counterpartyBsb`, `counterpartyAcct`,
  `foreignAmount`, `foreignCurrency` = `null`.
- New field **`toAccountRef`** = account of the positive-amount (to) leg;
  `null` on non-TRANSFER lines. JSON position: immediately after `accountRef`.
- New optional field **`comment`** on every line (nullable string): free-text
  note supplied via `POST /decisions`; `null` on lines not produced by a
  decision. JSON position: immediately before `ingestedAt`. Never part of
  identity.
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
  - `MARK_EXTERNAL`: `externalId`, optional `comment`.
  - `CONFIRM_TRANSFER`: `legA`, `legB`, optional `comment` (set on the TRANSFER
    line and both re-appended legs).
  - `DISMISS_DUP`: `externalId`, optional `comment` (see B7).
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
  `DISMISS_DUP` is rejected if the latest line lacks `POTENTIAL_DUP` (any state
  allowed).
- Output: MARK_EXTERNAL → leg re-appended `EXTERNAL`. CONFIRM_TRANSFER → both
  legs re-appended `MATCHED` + TRANSFER line with `confidence = EXACT`,
  `provenance = AUTHORED`, `comment`. DISMISS_DUP → re-appended with
  `flags = []`, state unchanged.
- Not in phase 1: "keep-both" (depends on B7) and "MAN-" manual entries.

**`GET /held`, `GET /review`**
- `GET /held`: latest line of every transaction whose current state is HELD.
  `GET /review`: latest line of every transaction whose current state is
  REVIEW **or** whose latest `flags` contain `POTENTIAL_DUP`. Both ordered by
  `n`. Served from the
  published in-memory snapshot; gzip per §3.6.

**Rules**
- Deciding whether a new candidate goes to `MATCHED`, `HELD`, `REVIEW` or
  `EXTERNAL` lives in trex-sequencer and will evolve into a tunable rule set.
  Phase 1's rule set is the §3.4 allowlist + tiers.
- Rule changes affect only future ingests; replay uses stored `state`, never
  re-evaluates rules.

**Followers**
- §5.2 archive: mirrors every line; idempotency by `n`, not `external_id`.
- §5.3 sqlite: see B21 — a journal mirror keyed by `n`.

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

### B7 — Potential duplicate (flag only; revisit later)
- Candidate whose `external_id` already exists with a **different** balance:
  re-append the existing transaction with **state unchanged** and
  `flags = [POTENTIAL_DUP]`. Works for any current state (incl. MATCHED).
- Same balance → `DroppedDuplicate`, nothing appended.
- Idempotent: if the latest line already carries `POTENTIAL_DUP`, nothing is
  appended; result is `Flagged`.
- The conflicting incoming balance is not stored (response only).
- Cleared by `DISMISS_DUP` decision.
- User intends to revisit this design later.

### B8 — Review set — *resolved by T + B7*: see `GET /review`.

### B14 — Same `external_id` twice in one batch
- Later occurrence is compared with the earlier one using the B7 rule (same
  balance → `DroppedDuplicate`; different → earlier appended normally, then a
  flagged re-append).

### B9 — `ingestedAt` and byte-identical journals
- A non-null `ingestedAt` is retained, never restamped.
- The sequencer stamps `ingestedAt` from an injected `java.time.Clock`.
- Tests 10 and 13 use a fixed `Clock`; they compare full journal bytes,
  `ingestedAt` included. `Candidate` is unchanged.

### B11 — Phase-1 matching rules (defensive; tune via REVIEW workflow)
- Principle: start defensive. When unsure → HELD or REVIEW, never an automatic
  guess. Rules are loosened later based on what the review workflow shows.
- **Transfer-shaped:** `rawDescription` matches any allowlist regex
  (`transfers.toml`), case-insensitive.
- **Match pool:** legs whose current state is HELD, plus new legs earlier in
  the same batch. REVIEW, EXTERNAL and MATCHED legs are never auto-matched.
  Candidates are processed in input order (deterministic).
- **T1:** same `receipt` in two different accounts, opposite sign → `EXACT`.
  Applies whether or not the legs are transfer-shaped. More than one T1
  contra → REVIEW.
- **T2:** not implemented (undefined in SPEC).
- **T3:** both legs transfer-shaped, `|amount|` equal, opposite sign, dates
  within `windowDays`, different accounts, same currency → `HIGH`. No text
  corroboration in phase 1. More than one T3 contra → REVIEW.
- On ambiguity only the incoming leg goes to REVIEW; existing HELD legs are not
  rewritten.
- No match: transfer-shaped → HELD; otherwise → EXTERNAL. A receipt alone does
  not make a leg transfer-shaped.
- `windowDays` is required in `transfers.toml`; no default.

### B15 — Description cleaning
- One pure function: trim, collapse internal whitespace runs to a single
  space. Swappable; never used for identity (identity hashes `rawDescription`).

### B16 — Row order and reconciliation
- CSV row order is not assumed (treat as arbitrary). The ING adapter preserves
  file row order as-is; no sorting or reversal.
- Consequence accepted: `occ` follows file order. The same file always yields
  the same ids; a re-exported file that reorders identical-`Sig` rows can swap
  their `occ` (surfaces as `POTENTIAL_DUP` via differing balances).
- Reconciliation (test 6) is order-independent, per account, over leg lines
  (TRANSFER lines excluded), first line per `external_id`:
  - each leg links `prev = balance − amount` → `balance`;
  - opening = the `prev` that is no leg's `balance`; closing = the `balance`
    that is no leg's `prev`;
  - require exactly one opening and one closing (a single chain), else report
    "unreconcilable" (never guess);
  - assert `Σ amount == closing − opening`, exact `long` equality.

### B17 — Config parsing
- Hand-written TOML subset parser in trex-sequencer (JDK-only): `[table]`,
  `[[array-of-tables]]`, `key = value` with strings, integers, booleans, arrays
  of strings, `#` comments. Anything else is a config error at startup.

### B18 — Durability (fsync)
- Keep SPEC §3.1: one `force(true)` per `appendBatch` (per API call, not per
  transaction), before in-memory state is updated and success returned.
- No `fsync` policy option in `sequencer.toml`; always on.
- Follower batching is done with the follower `pollSeconds` setting (§6), not
  by delaying fsync.
- Throttled/deferred fsync rejected: acked batches lost on crash, followers
  running ahead of the truncated journal, `n` reuse corrupting mirrors.
- Group commit (shared fsync, ack after fsync) is a later optimisation, only if
  throughput is measured to be a problem.

### B19 — Request body binding
- Parse `/candidates` (and `/decisions`) body to a JSON tree, then bind each
  element individually. A malformed element → `Rejected(ref, reason)`; the rest
  follow `allOrNone` rules.
- Malformed body / wrong top-level structure → `400`, nothing appended.
- Missing `candidateRef` → `"idx-" + zeroBasedIndex`.

### B20 — Unparseable amounts in the ING adapter (amends SPEC §4)
- Values with more than 2 decimals (or otherwise not convertible to exact
  cents) are rejected, never rounded.
- The adapter parses and validates the **whole file before sending anything**.
  Any bad value → nothing is sent; the adapter prints every bad row (file,
  line, column, value) and exits non-zero.
- Reason: dropping a single row could shift `occ` for later identical-`Sig`
  rows when the fixed row is re-ingested, changing their `external_id`.
- Such rows never reach the sequencer, journal or review workflow; the fix is
  to correct the file (or parser) and re-run (idempotent).

### B21 — SQLite follower schema (amends SPEC §5.3)
- The SQLite database mirrors the **journal**, not transaction state: one row
  per journal line, primary key `n`. Every `CanonicalEvent` field is stored.
  ```sql
  CREATE TABLE IF NOT EXISTS journal (
    n INTEGER PRIMARY KEY,
    external_id TEXT NOT NULL,
    account_ref TEXT, to_account_ref TEXT, currency TEXT,
    date TEXT, amount INTEGER, balance INTEGER,
    description TEXT, raw_description TEXT,
    type_hint TEXT, transfer_key TEXT, leg_ids TEXT,      -- JSON array or NULL
    corrects TEXT, state TEXT, confidence TEXT, flags TEXT, -- flags: JSON array
    provenance TEXT, source TEXT, receipt TEXT,
    counterparty_bsb TEXT, counterparty_acct TEXT,
    foreign_amount INTEGER, foreign_currency TEXT,
    comment TEXT, ingested_at TEXT
  );
  CREATE INDEX IF NOT EXISTS journal_external_id ON journal(external_id);
  CREATE TABLE IF NOT EXISTS follower_state (k TEXT PRIMARY KEY, offset INTEGER);
  ```
- Consume = `INSERT … ON CONFLICT(n) DO NOTHING` + `follower_state` offset
  update in one transaction (exactly-once into SQLite; invariant 5).
- `PRAGMA journal_mode=WAL;`. Amounts/balances bound as INTEGER cents, never
  REAL.
- The follower holds no transaction-state logic.

### B22 — Read consistency
- After each successful commit the sequencer publishes an immutable snapshot
  of in-memory state via an `AtomicReference`. `GET /head`, `/held`, `/review`
  read the snapshot: lock-free, never a half-applied batch. Mutations stay
  under the single write lock.

### B23 — Bank-specific behavior (amends SPEC §9 "select bank-specific behavior")
- Bank-specific behavior is encapsulated in ingress adapters. The sequencer
  has none: it uses the registry only to validate `accountRef` and stamp
  `currency` (and hold `fireflyAccountId`). The registry `format` field is for
  adapters.

### B24 — SPEC §5.3 wording slip — no action.

### B12, B13, B25, B26 — watermark / aging details — *moot under T*

## Open

- B21 follow-up: optional SQL view of current state per `external_id` (latest `n`).
