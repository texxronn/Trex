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
  adapters. *(Superseded in part by I3: `format` is removed; the source type is
  bound per run by `--source-type`.)*

### B24 — SPEC §5.3 wording slip — no action.

### B12, B13, B25, B26 — watermark / aging details — *moot under T*

## Open

- B21 follow-up: optional SQL view of current state per `external_id` (latest `n`).
- F3 follow-up: egress-follower offset semantics when a recreated journal is shorter than the persisted offset (the resolver's B5 shrink refold has no follower equivalent).

## Follower startup (F)

The dev harness (one panel per component) surfaced a startup order the units hide: both
egress followers died with `NoSuchFileException` from `FramedReader.<init>` when started
before the sequencer had created the journal, while the resolver and grid — sharing
`trex-web/JournalWatcher`, which already guards with `Files.exists` — waited quietly. Two
implementations of the same loop had disagreed, and SPEC §5.1 said nothing either way, so
SPEC was amended first (§5.1).

- **A missing journal is a state, not an error.** The follower has nothing to consume yet;
  that is indistinguishable from an empty journal, which it already handles. Crashing puts
  the chicken/egg problem in the operator's lap (start ordering, `After=`, retry loops) to
  save one `Files.exists` check.
- **No new machinery.** `JournalChanges` registers a `WatchService` on the journal's
  *parent directory*, not the file, so it is legal to register before the file exists and
  `ENTRY_CREATE` is already one of the watched kinds. Waiting is the existing wake loop
  with the pass skipped; nothing polls harder and no new flag appears.
- **F1 Wait indefinitely**, logging `waiting for journal …` once at `info`. A bounded wait
  would turn a slow-starting sequencer into a restart loop under `Restart=`, and the bound
  would have to be guessed. The cost is that a typo'd `--journal` path is quiet after its
  one line; `status`/`/head` show no progress, and the path is in that log line.
- **F2 `--once` on a missing journal exits 0**, drained nothing. `--once` is a drain, not
  an assertion that data exists; zero lines from a missing journal and zero lines from an
  empty one are the same result and scripts should not have to tell them apart. It does
  **not** wait — a one-shot that blocks is no longer a one-shot.
- **F3 The same rule covers a journal that disappears mid-run**, so the loop has one rule
  rather than a startup special case. This also covers the journal being replaced under a
  running follower by `source != target` recovery (§3.2).
- **Not decided here:** a *recreated* journal shorter than a follower's persisted offset.
  The resolver refolds from 0 on shrink (R4/B5); the egress followers keep a plain offset
  file and would sit past EOF. Left open — it is an offset-semantics question, not a
  startup one.

## Manual resolver (R)

Source: user notes in MANUAL_RESOLVER.txt (admin service; web UI; journal follower; lists HELD & REVIEW; minimal double-confirmed actions; acts via sequencer API; page updates from the journal; no auth for now). Folded into SPEC §5.4.

- R1 "confirm REVIEW" action: meaning unclear (DISMISS_DUP already exists; HELD→REVIEW is not an allowed transition). Left as TODO.
- R2 Web tech: JDK HttpServer + bundled static HTML/CSS/vanilla JS (CLAUDE.md: JDK-only, no frameworks). Compact, modern style.
- R3 Refresh: browser polling first; Server-Sent Events TODO.
- R4 No persisted resolver state: fold journal from offset 0 at startup, then tail. Journal-shrink detection added (materialize overwrites target, B5), refold from 0.
- R5 Shared components extracted rather than duplicated or depending on trex-sequencer: pure fold (Ledger, LedgerView, Projection, Reconciliation) → trex-core; Json mapper + FramedReader → new `trex-journal` module; followers drop their JournalTail copies. Reason: the resolver's HELD/REVIEW lists must match the sequencer's `/review` exactly, and framing/corruption rules should have one implementation. Amends SPEC §1, §2, §5.1.
- R6 Double confirmation: action → dialog with exact effect + optional comment → Confirm sends; result or rejection reason shown. Re-submits are safe (sequencer rejects).
- R7 Page: HELD and REVIEW lists; select two rows to pair; no suggestions, no history.
- R8 New module `trex-resolver`; configured by flags.
- R9 Narrow screens (amends §5.4 web tech). The desktop table was usable on a phone only by scrolling sideways, with the pair checkbox and the action buttons at opposite ends of the scroll. Below 720px each row becomes a record card: checkbox on a left rail, amount against its date and account, description wrapped in full instead of truncated, badges, `n` and actions on the last line. CSS only, keyed on the existing cell classes (one `col-desc` class added); the JS, the API and the double confirmation are unchanged, and no value is dropped from a row. Tablet (≤1024px) keeps the table and releases the fixed column widths. Touch gets ≥36px controls and 16px text in the comment field (iOS zooms a smaller focused field). Verified in headless Chrome at 390, 820 and 1440 px.
- Security (no auth): bind 127.0.0.1 by default; POST requires JSON content type + `X-Trex-Admin: 1` + matching Origin (CSRF); CSP `default-src 'self'`; text inserted via textContent (bank descriptions are untrusted).

### R3a — Live updates (supersedes the polling-first part of R3)
- Journal change detection via `WatchService` (JDK; inotify-backed on Linux) on the journal directory, not polling: epoll/select cannot wait on regular files (always ready; `EPERM` for epoll), and the external `inotifywait` tool would break JDK-only. FFM-direct inotify rejected: more code, Linux-only, needs native access, no benefit here.
- A slow fallback read (10 s) stays: events can coalesce/overflow and do not fire on NFS/some FUSE/bind mounts.
- SSE from the resolver pushes state on change; browser polling remains only as a fallback while the stream is down.
- Followers keep their `pollSeconds` loop (not changed in this step).

## Grid (G)

User asked for a plain, compact, read-only grid on the resolver's model (SSE, paging, column sort), explicitly not a dashboard (Firefly/Grafana cover that). Folded into SPEC §5.5.

- G1–G9 accepted as proposed: Transactions/Journal views; compact column set with toggles; server-side multi-column sort with `n` tie-break; paging pinned to `asOfN` with a "new rows" chip and Follow mode; in-memory lines with cached query results; SSE carries head info only; URL-mirrored state; exact cents with per-currency totals; read-only security.
- Basic filter & search only (account, state, type, date range, text). Declined for now: detail drawer, reconciliation/accounts/health panels, projection preview, transfers view, export.
- G10 (a): separate `trex-grid` process + shared `trex-web` module extracted from the resolver (watcher, SSE, static serving, security headers). Lets the read-only grid be exposed differently from the action-taking resolver. *(Reversed by W1: both ran on loopback, so that exposure boundary was never taken, and the split forced a cross-origin write path once the table needed to write a pin.)*
- G11 Narrow screens (amends §5.5). The grid stays a table at every width: side-scrolling a dense table is the right shape for a journal, and cards would lose the column-to-column comparison the grid exists for. Only the chrome adapts — below 720px the header wraps with the view switcher on its own full-width row, search takes a full row, the column picker becomes a bottom sheet instead of a popover that runs off-screen, and the footer wraps with the pager kept on the right. Touch gets ≥36px controls and 16px form text. CSS only.
- Fill-in: totals only in the Transactions view and excluding TRANSFER lines (legs carry the amounts; journal versions would double-count).

### F1 — Followers wake on journal change
- User approved: archive and SQLite followers use the same file-change trigger as the web followers instead of sleeping `pollSeconds`.
- One implementation, `JournalChanges` in `trex-journal` (WatchService on the journal directory), shared by the followers and `trex-web`'s `JournalWatcher`. Registered before the first pass so a change between a pass and the wait is not lost.
- `pollSeconds` remains as the fallback interval.

## Post-build review (S)

- S1 Sequencer binding: the API bound all interfaces (`0.0.0.0`) with no authentication. `sequencer.toml` now has `bindHost` (default `127.0.0.1`) and `bindPort` (replaces `apiPort`); a non-loopback host prints a warning.
- S2 Decisions are final (user): no undo decision. Mistakes are prevented by the resolver's double confirmation, not reversed.
- S3 Directory fsync: when the journal file is created (new journal or materialize copy), fsync file and parent directory so the directory entry survives a crash.
- S4 Real ING export validation: user will check against a real export before first real ingest (pending).

## Containers (D)

User asked for a Docker setup: Java 25, Temurin 25 runtime, non-root, reproducible, no
config or data in the image, Compose for local/integration runs, buildable from Maven,
usable against a remote daemon via Docker context, and no Kubernetes.

- **jib over a Dockerfile**, as the user preferred if it fits cleanly — it does. No
  Dockerfile, no build context (so no `.dockerignore`), reproducible layer timestamps
  by default, and it builds from Maven, which was a requirement rather than a bonus.
- **One image per service module, not a single multi-service image.** A combined image
  needs a packaging-only module purely to hold it, and SPEC §1 fixes the reactor at a
  named module list (nine then, ten since C2 added `trex-category` — a library every
  journal consumer shares, not packaging glue).
  Per-module jib keeps that list untouched and is how jib is meant to be used.
  Cost: six tags instead of one. Rejected alternatives: a `trex-dist` module (would
  amend SPEC §1), and a multi-stage Dockerfile over the existing shaded jars.
- **Base pinned by digest** (`eclipse-temurin@sha256:611e…`, = `25-jre`). Reproducibility
  needs an immutable base; a floating tag would silently change the output.
- **`project.build.outputTimestamp` in the parent pom.** Without it Maven stamps build
  times into jar entries, our own SNAPSHOT jars differ on every build, and the image
  digest moves even though nothing changed. Verified: two clean builds, same digest.
- **Non-root as uid 1000**, which is the Temurin base's existing `ubuntu` account. jib
  cannot set file ownership inside layers, so a named volume would stay root-owned; a
  one-shot `init` container chowns the volumes and exits. Rejected: world-writable data
  directories baked into the image.
- **Followers mount the journal `:ro`.** The single-writer invariant (§0.2) becomes a
  mount-level guarantee, not just a convention.
- **Config is never baked in.** `deploy/compose/sequencer.toml` is a container variant
  (`bindHost = "0.0.0.0"`, journal under `/var/lib/trex`) delivered as a compose
  `config`, i.e. file content rather than a bind mount, so it also works against a
  remote daemon. Ports are published on `127.0.0.1` only — the API is still unauthenticated.
- **`deploy/bin/trex-docker.sh` exists for one reason:** jib reads `DOCKER_HOST` but not
  Docker's context file, so `docker context use` alone would have Maven build locally
  while compose targeted the remote host. The script resolves the context and exports it.
- **Images must be built through the `package` phase.** A bare `mvn jib:dockerBuild`
  resolves inter-module dependencies from `~/.m2`; a stale jar there produced an image
  that built cleanly and then failed at runtime with `NoClassDefFoundError`. Hence the
  `docker` profile binding to `package`, and `-am` alongside `-pl` in the script.
- **Compose hardening matches the systemd units** (added after review): `read_only`,
  `cap_drop: [ALL]`, `no-new-privileges` and a `/tmp` tmpfs on every service, which is
  the compose equivalent of `ProtectSystem=strict` / `NoNewPrivileges` / `PrivateDevices`.
  Two scoped exceptions: `init` keeps `CAP_CHOWN` (its only job), and `egress-sqlite`
  needs an `exec` tmpfs because sqlite-jdbc unpacks a native library and `dlopen()`s it
  — tmpfs is `noexec` by default, which made the follower crash-loop with
  `NativeLibraryNotFoundException`. The exec relaxation is on that one service only.

## Logging (L)

User asked for SLF4J logging throughout the code. SPEC §1 listed the dependencies
exhaustively and ended with "JDK-only for the rest", so this needed a spec change rather
than an implementation choice. Options put to the user: `System.Logger` (no SPEC change,
JDK-only, bridgeable to SLF4J later), SLF4J in the service modules only, or SLF4J
everywhere including trex-core.

- **User chose SLF4J in every module, trex-core included.** SPEC §1 amended: `slf4j-api`
  is permitted everywhere, trex-core's "only dependency" clause is gone, and the binding
  (`slf4j-simple`, runtime scope) belongs to the six runnable modules — libraries never
  bind one.
- **Pinned to 2.0.16**, which also displaces the 1.7.36 that `sqlite-jdbc` pulls in
  transitively. That transitive api with no binding is why the egress-sqlite container
  used to print `SLF4J: Defaulting to no-operation (NOP) logger`; it no longer does.
- **The amendment carries two constraints**, because logging must not weaken the
  invariants: nothing is logged inside journal serialization (byte stability, §3.1), and
  a log statement may observe but never decide, so the fold stays deterministic.
- **No amounts or descriptions in logs.** The journal is financial data, so log lines
  carry `externalId`, `n`, states and counts only. The batch summary logs per-kind
  counts (`{Held=1, Resolved=4}`), not rows.
- **trex-core got one call site**, a guarded `trace` of each fold transition in `Ledger`.
  Its only `catch` rethrows, and log-and-throw would double-report. This is the honest
  extent of the benefit in core, as flagged before the choice was made.
- **`System.err` stays for usage messages** (exit 64) and for the ING adapter's per-row
  results on stdout: those are a CLI contract (README "ING adapter"), not logging.
- **Five silently swallowed exceptions now log** at `debug`/`warn`: best-effort directory
  fsyncs, watch-service close, and the two "response already started" paths. The 500
  handler in `HttpApi` previously discarded the stack trace with the response.
- **Tests run at `warn`** via surefire `systemPropertyVariables`, so assertions are not
  buried in INFO chatter.

## Reconciliation endpoint (R)

`Reconciliation` was production code in trex-core, covered by SPEC §7 test 6, and called
from exactly one place: a test. SPEC §4 already called it "the reconciliation tripwire …
the backstop" for a day splitting across statement files, but the backstop could only be
pulled by writing a JUnit test. Adding an endpoint changes the API contract (§3.5), so
SPEC was amended first.

- **`GET /reconcile`**, not a CLI or a grid view. It is the sequencer that holds the
  authoritative snapshot; a follower would reconcile a lagging copy. The resolver and grid
  can call it later if a UI is wanted.
- **Over the published snapshot, not a journal re-read.** `LedgerView.firstLine` already
  holds the first line per `externalId`, which is exactly the algorithm's input, so the
  endpoint takes no write lock and appends nothing (§3.2 read consistency). A test asserts
  that reconciling twice leaves `n` and the line count unchanged.
- **`balances` is returned, not left to the client.** `Reconciliation.Result.balances()` is
  a derived method, and Jackson does not serialize non-component record accessors, so the
  report carries it explicitly rather than making every caller recompute
  `sum == closing - opening`.
- **`ok` is vacuously true for an empty journal** — nothing fails to balance. The empty
  `accounts` array makes that unambiguous.
- **Unreconcilable stays unreconcilable:** `opening`/`closing` are `0` and `reconcilable`
  is false. Never guess which end of a broken chain is the opening (§7 test 6).
- **TRANSFER lines stay excluded**, so a matched internal transfer does not count as a
  third leg; there is a test for that specific case.
- The log line carries counts and the verdict only — no amounts (§1).

## Ingress source types (I)

Ingress will have many source types: ING today, CBA, WestPac and bank-sync feeds
later. Egress will have few, each specialized. SPEC §1 named the ingress module
after one bank (`trex-ingress-ing`), which would mean one module per source type.
Amends SPEC §1, §2.1, §2.2, §3.3, §4, §5.3, §5.5 (G2), §6, §8, §9.

- **I1 One ingress module, `trex-ingress`.** The CLI, HTTP client, gzip and
  day batching are shared (`trex.ingress`); each source type is one parser in a
  sub-package (`trex.ingress.ing`). Per-type modules would duplicate that code
  or need a common module; either way modules proliferate. The reactor stays at
  nine modules. Egress keeps one module per follower: few, and each has its own
  dependencies (sqlite-jdbc) and semantics.
- **I2 `--source-type`, and the field `source` becomes `sourceType`.** Not
  `--bank`: a feed is not a bank. Not `--source`: that reads as an instance.
  The positional `<source>` is the instance (for `ing-csv`, the file); the flag
  names its kind. The Candidate/CanonicalEvent field is renamed to match so the
  flag, the parser key and the journal share one unambiguous name. Changing
  `@JsonPropertyOrder` changes the journal byte format; acceptable because no
  journal exists yet. `sourceType` is not in identity (§2.4), so ids are
  unaffected either way. SQLite column `source_type`, grid column `sourceType`.
- **I3 Registry `format` removed.** The config parser validated it against
  `ing|cba|bw` and stored it on `Account`; nothing read it. A single per-account value is also wrong: one account can
  arrive through several source types (ING CSV now, a feed later). The binding
  is `--source-type` on each run. Deferred (§9): a per-account `sourceTypes`
  allowlist enforced by the sequencer, which would catch a file sent with the
  wrong `--account`. Not added now because with one source type it has nothing
  to distinguish, and an unenforced field would look like a guard.
- **I4 `--url` → `--sequencer-url`,** matching the resolver (§5.4). Not
  `--target-seq-url`: the ingress only ever talks to the sequencer, so `target`
  adds nothing.

## Config format and dependencies (Y)

Amends SPEC §1 (dependencies, module list), §6 (all config), and every config file.

- **Y1 YAML replaces TOML everywhere; the hand-written parser is deleted.** `Toml.java`
  (278 lines) plus its test (65) re-implemented a standard nobody needed re-implemented,
  and `Config.java` walked untyped maps by hand. `jackson-dataformat-yaml` binds straight
  to records and is the same family as the mapper trex-journal already owns. Strictness is
  kept, not lost: `FAIL_ON_UNKNOWN_PROPERTIES` replaces the `allowOnly` calls and
  `STRICT_DUPLICATE_DETECTION` turns a duplicated key into an error rather than last-wins.
- **Y2 The real reason is not syntax — it is that config parsing stops being
  sequencer-private.** Only the sequencer could read TOML, so any rules file for the read
  side would have forced `Toml.java` into a shared module. With the shared YAML mapper,
  the grid, the resolver and the Firefly egress read `categories.yaml` directly. The
  categorisation design (C) depends on this.
- **Y3 The journal does not move.** It stays framed JSONL: byte stability and the identity
  contract (§0.1, §3.1) are the reason the format is boring on purpose.
- **Y4 Separate files, `.yaml`, and a leftover `.toml` is a startup error.** The registry is
  edited far more often than ports and journal paths, so one merged `trex.yaml` would put
  the most-edited data next to the least-edited. A stale `accounts.toml` sitting beside the
  new file would otherwise be ignored in silence.
- **Y5 Dependency policy loosened, with two standing exceptions.** A library that *removes*
  source is preferred over hand-written code — fewer lines to maintain, review and carry as
  context. The exceptions are the identity/journal path (`Ids`, `JsonlJournal` framing,
  `FramedReader`), where a version bump could shift the identity contract, and the HTTP
  layer, where SPEC already fixes `com.sun.net.httpserver` and a framework would cost more
  than it removes. Recorded as intended, each as its own change: picocli for the six `Main`
  CLIs (~130 lines, and it already uses exit code 64 for usage), and a CSV library for
  `trex.ingress.Csv` (~100 lines) — that one gated on a golden-file comparison against a
  real export, because `rawDescription` is hashed into identity verbatim and libraries
  differ on trimming, BOMs and blank lines.
- **Y6 picocli adopted; the CSV swap tried and rejected.** picocli did *not* shrink the
  source — the six mains went 356 → 413 lines, because the hand-rolled `switch` loops were
  already compact and the help text is new content. It was kept anyway for `--help`,
  typed conversion of paths/URIs/numbers, required-option checks, and help generated from
  the same declaration the parser uses. Note picocli exits **2** on a usage error, not 64
  as assumed when this was planned, so every command pins `exitCodeOnInvalidInput = 64`.
  `trex.ingress.Csv` was then compared against Apache Commons CSV on nine inputs. Seven
  matched exactly (including embedded newlines, blank lines, untrimmed spaces and a missing
  trailing newline); two did not: a bare `\r` between rows becomes a record separator
  (silently splitting a row) and a stray quote inside an unquoted field is accepted as
  literal text. Both replace a loud failure with a quiet reinterpretation of bytes that are
  hashed into identity — the very duplicate §4 guards against. The 99 lines are validation,
  not boilerplate, so they stay. **The lesson generalises: a library is a clear win when it
  replaces a re-implemented standard (Y1), and a poor trade when the hand-written code is
  enforcing an invariant the library has no reason to care about.**

## Categorisation (C)

Master category in trex, fine-grained categorisation left to Firefly. Amends SPEC §0 (new
invariant 7), §1, §2.2, §3.2, §3.5, §5 (title, §5.3, §5.4, §5.5, new §5.6, Firefly note),
§6, §7 (tests 16, 17), §8, §9.

- **C1 Derived by journal consumers; never stamped at ingest.** Rules change; stamped labels go
  stale, and recategorising history would mean mass re-appends, which §3.2 forbids (a
  re-append may only change a named few fields). As a pure function of the latest line plus
  the rules file, a rules change recategorises everything for free and leaves the journal
  byte-identical — test 16 asserts exactly that.
- **C2 A new module, `trex-category`, not trex-core and not a copy per follower.** The
  evaluator is pure enough for core, but core's remit is identity, the fold and journal
  semantics; categorisation is not that. One shared library means the grid, the resolver and
  the Firefly egress cannot disagree. This makes the reactor ten modules, amending the
  "nine named modules" reason recorded under Containers (D).
- **C3 Rules are data, not expressions — no embedded engine.** An engine (JEXL, MVEL, CEL,
  Drools) would replace only the evaluator, roughly 120 lines, while adding a dependency, an
  eval surface reachable from a config file, and version-dependent semantics against a
  project that treats determinism as an invariant. It also moves failure from load time to
  row time. A declarative `when` tree (`all`/`any`/`not` over typed leaves) covers
  description, direction, account and amount, which is what master-level categorisation
  needs. **This is deliberately the opposite call from Y1**: there, a library replaced a
  re-implemented standard; here, no library knows this domain's fields.
- **C4 A `Categorizer` interface is the seam.** If the tree ever proves too weak, an `expr`
  leaf slots in behind the same interface without disturbing existing rules. §9 records CEL
  as the preferred engine for that, because it type-checks at load and cannot side-effect,
  which preserves the "bad rule fails at startup" property. Not built until something needs
  it.
- **C5 `TRANSFER` and `UNCATEGORIZED` are reserved.** `TRANSFER` comes from the fold, never
  from a rule: trex already makes that top-level call, and a rules file must not be able to
  contradict it. `UNCATEGORIZED` is the honest answer when nothing matches (§0.6 accuracy
  over recall) and doubles as the worklist that drives rule writing, exactly as HELD/REVIEW
  drove the transfer rules.
- **C6 (withdrawn by C11) Overrides are journalled, and `SET_CATEGORY` is repeatable —
  §3.5 finality is narrowed, not broken.** A human correction is an authored fact and belongs in the journal,
  not in a side table that a re-projection would lose. But "decisions are final" cannot hold
  for a category, because correcting a mis-categorisation is the normal case, not an
  exception. The smallest resolution: finality is about *state* transitions
  (`MARK_EXTERNAL`, `CONFIRM_TRANSFER`, `DISMISS_DUP`), which stay irreversible;
  `SET_CATEGORY` changes no state and may be reissued, with the latest line winning like any
  other version. Nothing is rewritten, so §0.2 is untouched. `categoryOverride` is carried
  across later re-appends (it is durable state, unlike `comment`).
- **C7 (withdrawn by C11) The sequencer treats the category as an opaque string.** It never loads
  `categories.yaml` and never validates the name against it — that would drag rule config
  into the write side and contradict C1. The resolver validates against the declared list
  before posting, and a reader showing an undeclared name marks it unknown. The journal
  already carries free-text `comment` from decisions, so an opaque string is not a new kind
  of payload. Cost, accepted: a hand-written `curl` can store a typo, visible in the grid.
- **C8 Firefly gets a tag, never its `category` field.** Firefly's rules own the fine-grained
  category; writing that field from trex would make the two overwrite each other. The tag
  `trex-category:<name>` keeps the levels separate. Because the value is derived, the
  egress's projection-state table records the category last projected, so a rules change
  re-projects only the affected transactions.
- **C9 The starting list is small on purpose:** `SALARY`, `INTEREST`, `GROCERIES`, `BILLS`,
  `TAXES`, `SAVINGS`, `DISCRETIONARY`. Config-defined, so adding `BIG_TICKET` or `FEES` is a
  config edit. Two traps noted while choosing it: most moves to one's own savings account are
  internal transfers, so `SAVINGS` only fires for external providers; and a size-based bucket
  overlaps every other category, so with first-match-wins its position silently decides
  outcomes.
- **C10 Explainability and a dry run are part of the feature, not extras.** The result carries
  which rule fired and why (`STRUCTURAL`/`OVERRIDE`/`RULE`/`NONE`), and the dry run prints
  per-category counts plus the most frequent uncategorised descriptions. A rule table nobody
  can interrogate becomes folklore, and rules are tuned against real data before anything
  reaches Firefly.
- **C11 No category in the journal at all — C6 and C7 withdrawn.** C1 put rule output on the
  consumer side, then C6 put a human correction back on the write side as `categoryOverride`,
  which needed a new decision, a narrowing of §3.5 finality, a new event field, a SQLite
  column, and a sequencer accepting a string it could not validate. The user rejected the
  straddle: if a category is derived by journal consumers, that holds for a human correction
  too. A correction is now a **pin** — a rule whose `when` is an `externalId` — in the same
  `categories.yaml`, so corrections and rules are one mechanism, and git supplies the audit
  trail and history that journalling was meant to provide.
  - The reconvergence argument for journalling was wrong: reproducing categories needs
    `categories.yaml` either way, so journalling overrides bought back no property.
  - Stamping is also strictly more expensive than deriving. Both require the same downstream
    re-projection when rules change; stamping additionally appends a full event copy per
    affected transaction. Its only real gain is as-of truth ("what did we call this in
    March?"), and journal + `categories.yaml` at that commit answers that without churn.
  - Rejected at the same time: a **generic mutable blob/map** on `Candidate` and
    `CanonicalEvent` with an API to rewrite it (the FIX custom-tag shape). It becomes a
    dumping ground with no compile-time impact surface, forces canonical key ordering against
    §1's deliberate `ORDER_MAP_ENTRIES_BY_KEYS` off, gives the SQLite mirror and the grid
    nothing typed to bind to, and generalises the unvalidated-string gap to every consumer.
    The half worth keeping is the **immutable** form: an `extras` map stamped once at ingest
    for fields trex does not model, never updated, never in identity — deferred to §9 until a
    source type needs it.
  - A properly journalled lifecycle would be a separate annotation line kind referring to an
    `externalId`, not a blob on the transaction (one small line instead of a full copy). Noted
    and not taken: it needs a new line kind, new fold rules and every reader updated, and git
    already answers the as-of question.
  - Cost accepted: no one-click recategorisation in the resolver. The page shows the exact
    pin snippet to paste; a machine-written `overrides.yaml` is the escape hatch in §9 if
    pins outgrow hand editing.

## Source types (S2)

### S2 — `bw-csv` (BankWest), amends SPEC §4, §7 test 1, §8, §9

Built from a real BankWest credit-card export (219 rows, Jun–Sep 2026) rather than from
the bank's documentation.

- **Debit is positive in BankWest, negative in ING.** `amount = credit − debit` here against
  `credit + debit` there. Same header idea, opposite sign convention: this is the concrete
  reason `--source-type` binds a parser (I2) instead of the registry recording a format per
  account.
- **No receipts at all**, so every row takes content-hash identity. `occ` and the day-atomic
  batching rule (§2.5, §4) are load-bearing for this source type in a way they are not for
  ING, where 146 of 147 rows carried a receipt. The sample file happens to contain no
  identical `(date, amount, narration)` rows, but a card can easily produce them — two
  coffees at one shop on one day.
- **Pending authorisations are skipped and reported, not ingested.** BankWest exports
  authorisations marked `AUTHORISATION ONLY` with a blank `Transaction Type`. The same
  purchase settles days later under a different narration, which is a different content hash.
  The journal is append-only: an ingested pending row can never be removed, only masked by a
  decision, so it would linger as a phantom transaction and double-count the spend. Skipping
  is reported alongside bad rows, so nothing is dropped in silence — the user chose this over
  ingesting them or rejecting the file. Rejecting was tempting for consistency with the
  bad-row rule, but every fresh export contains pending rows, so it would block ingest until
  they settled.
  - Safe for reconciliation because pending rows sit at the newest end of the export, leaving
    the remaining rows a contiguous balance chain. If that ever stops being true, §7 test 6
    catches it rather than the parser guessing.
- **`Account Number` is read but ignored.** The card number is in the file; the account comes
  from `--account`. Matching them would put a second, weaker identity binding in the parser
  and tempt someone to auto-create accounts, which §3.3 forbids.
- **Naming `bw-csv` / `bw-credit-card`** follows the existing `bw-` prefix and the `ing-csv` /
  `ing-credit-card` pair. Both are permanent: the source type is stamped on every event and
  the ref is part of identity for content-hashed rows.
- **Cents conversion moved to `trex.ingress.Cents`**, shared by both parsers. It is the
  §0.4 rule (`BigDecimal` at the parse boundary, scale-checked, never rounded) and having two
  copies of it would be two places to get rounding wrong.

### S3 — `cba-csv` (CommBank), amends SPEC §4, §7 test 1, §8, §9

Built from a real CommBank SmartAccess export (63 rows, Jun–Sep 2026).

- **No header row.** The first line is data. So the shape is the validation: exactly four
  fields, a parsable date, exact-cents amounts. A file of another type fails on its first row
  instead of being half-read, and a first row that both fails and contains `Date` is reported
  as looking like a header, because that is the mistake someone will actually make.
- **The amount is already signed** (`-75.00`, `+1000.00`), so there is nothing to combine.
  Three source types, three conventions: ING signs its debit column, BankWest leaves both
  columns positive, CommBank signs the single amount. Each is a parser, none is a flag.
- **No receipts**, so content-hash identity again, as for BankWest.
- **CommBank truncates long descriptions** with a trailing `...` in the export. It is hashed
  verbatim like any other text (§2.4). If CommBank ever truncates the same transaction at a
  different length it mints a different id; §7 test 6 is the backstop, as for any restatement.
  Not worth special-casing on a guess about their truncation rule.
- **Known operational cost, not a bug:** 21 of the 63 rows match the current transfer
  allowlist, because CommBank writes ordinary payments to people as `Transfer To <name>`.
  Under §3.4 those are transfer-shaped, so each will sit HELD waiting for a contra leg that
  does not exist, and needs `MARK_EXTERNAL` in the resolver. Narrowing `transfers.yaml` would
  cut that work but risks the opposite failure — a real internal transfer silently going
  EXTERNAL. §0.6 says prefer the review queue to a wrong automatic decision, so the allowlist
  stays as it is until there is evidence from real use.
- **Account refs:** `cba-smartaccess` is the account that exports CSV. The second CommBank
  account has no CSV export at all; it stays registered with no source type, which is exactly
  the "valid ref, no data" case `/reconcile` reports. The CDR (open banking) source type in
  §9 is the path for it.

### S4 — `cba-pdf` (CommBank Transaction Summary PDF), amends SPEC §1, §4, §7 test 1, §8

The user's second CommBank account has no CSV export at all; the PDF is the only source for
it. The PDF is also the better source for the account that *does* export CSV: it covers
1 Jan–23 Sep where the CSV covered Jun–Sep.

- **Measured before deciding, not after.** PDF text extraction is not a canonical byte string,
  and CommBank rows carry no receipt, so the description is hashed into identity. Both
  PDFBox and poppler's `pdftotext` were run over the real statement and compared against the
  same account's CSV export for the overlapping period: **63 of 63 descriptions reproduce
  byte-for-byte**, and the balance chain closes over all 151 PDF rows. Two unrelated
  extractors agreeing is the evidence that made this safe enough to hash; one would not have
  been.
- **The payoff is deduplication across sources.** Identical text means an identical
  `external_id`, so the same transaction ingested from the CSV and from the PDF is one
  transaction, and a PDF backfill over a CSV-covered period returns `DroppedDuplicate`
  instead of double-counting. That is what makes the PDF a safe *superset* source.
- **`rawDescription` is normalised here, and §4 says so.** Everywhere else it is verbatim
  (§2.4); a PDF has no verbatim to offer. The frozen rule — trim, join continuations with one
  space, collapse whitespace runs — is what makes two extractors agree in the first place.
- **The page-furniture rule is the whole parser.** CommBank's footer is three lines, and a
  filter that catches only the first appends the rest to whichever description straddles the
  page break. It cost two wrong descriptions out of 151 in the first probe, on exactly the
  rows at page boundaries — which is precisely the kind of error that is invisible until ids
  stop matching. Skipping from the `Created dd/mm/yy` marker to the next table header is the
  rule that survives.
- **`Locale.ENGLISH` is pinned** for the `dd MMM yyyy` month names. The platform default would
  make the parser's behaviour depend on the machine that runs it, which is the same class of
  bug as a library version in the identity path.
- **PDFBox over `pdftotext`.** A run-time external binary would leave the containers depending
  on poppler being installed and on *its* version; PDFBox keeps the tool self-contained, at
  ~3.6 MB in the ingress module only. Rejected alternatives: converting PDF to CSV outside
  trex (the converter becomes an untested piece of the identity path living outside the repo)
  and iText (AGPL).

### S5 — ING PDF evaluated and rejected; the natural key stays

Asked to add an `ing-pdf` source type, and in the same breath whether the receipt should stop
being part of identity ("treat it as bonus ref data"). Both questions were settled by joining
every ING PDF to its CSV on the receipt number and reading the pairs.

- **ING assembles the two exports differently, not just with a different separator.** For the
  same transaction the CSV and the PDF differ in field *order*, in zero-padding, in which
  fields appear at all, in label (`Receipt` vs `Receipt No` vs `RECEIPT`) and in case:
    CSV `To my account 0200029771 - Internal Transfer - Receipt 197585`
    PDF `Internal Transfer - Receipt 197585 Transfer To 200029771`
    CSV `Repayment - Direct Credit - Receipt No 197604From 0308747385`  (missing space, theirs)
    PDF `TRANSFER RECEIPT 197604 TRANSFER FROM 308747385`
  No normalisation reconciles those without encoding guesses about ING's internals.
- **Therefore the receipt is not overloaded; it is the only thing that survives.** Dropping the
  natural key for a uniform content hash would make every ING transaction ingested from both a
  PDF and a CSV count twice, because the hashed text differs. It would also move 146 of 147 ING
  rows onto `occ`, which is batch-scoped and order-dependent (§2.5), exposing nearly every ING
  row to the day-splitting hazard that receipts currently keep it clear of. The natural key
  stays exactly as §2.4 has it.
  - Left open, and free only until the first real ingest: tightening it to
    `nk|accountRef|date|receipt` would close the risk of ING recycling a 6-digit receipt number
    within one account over a long history. Zero collisions in three months of real data.
- **ING PDFs are not a source type.** Every ING account exports CSV, so the PDFs would add only
  older history, and three separate layouts would have to be parsed (`Money out $/Money in $`
  with inline receipts; Mortgage's `Debit/Credit` with `RECEIPT` on its own line; the card's
  `Money out/Money in` with **no receipts at all**). The card is the disqualifying one: with no
  receipt its rows take content-hash identity and can never dedup against the receipt-keyed CSV
  rows for the same account, so ingesting both silently double-counts — and dedup keys on the
  id, so no `POTENTIAL_DUP` flag would fire. Only the §7 test 6 tripwire would catch it, after
  the fact.
- **Contrast with `cba-pdf` (S4), which was built:** there the PDF was the *only* source for one
  account, and the extracted text matched the CSV byte-for-byte, so ids agreed. Here the CSV
  already exists everywhere and the text does not agree. Same question, opposite answer, for
  reasons that are in the data rather than in taste.

### S6 — Natural key tightened to `nk|accountRef|date|receipt` (amends SPEC §0.1, §2.4)

Taken while it was still free: no production journal exists yet, so no id moves that anyone
depends on. After the first real ingest this change would re-mint every receipt-keyed id.

- **Why:** a bank receipt is a rolling counter, not a permanent id. ING's are six digits and
  plainly not monotonic — `102406`, `126860`, `187845` within days of each other — so over a
  long enough history one can recur within the same account. Under the old key that second,
  genuine transaction would be silently dropped as a duplicate: dedup keys on the id, so
  nothing would flag it. Zero collisions in three months of real data, which is evidence about
  three months and not about ten years.
- **Why the date and not something else:** it is the one field that is stable across a bank's
  own reformatting. S5 showed ING rewrites descriptions between its CSV and PDF exports —
  order, padding, labels, case — so text cannot be in the key. Amount would work but adds
  nothing the date does not, since a recycled receipt lands on a different day.
- **Cost, accepted:** a bank restating a transaction's *date* now mints a new id. That is rarer
  than restating text, and the §7 test 6 reconciliation tripwire catches it; the natural key
  exists to survive text changes, which it still does.
- **What did not change:** T1 transfer matching still pairs legs on the shared receipt (§3.4) —
  it reads the `receipt` field, not the id. Verified on real statements after the change: two
  legs in different accounts still collapse to one `TRF-…` at `EXACT` confidence, re-ingest is
  still fully `DroppedDuplicate`, and `/reconcile` still balances.

### S7 — BankWest PDF rejected (amends SPEC §4's PDF admission rule)

Assessed the BankWest credit-card statement PDF the same way as the others. It fails on more
axes than ING did, and one of them is new.

- **No receipts** (zero occurrences), so identity would be the content hash of the description.
- **No per-row balance.** The table is Date/Description/Debit/Credit with a single closing
  balance at the end. `balance` would be 0 on every row, so the reconciliation tripwire (§7
  test 6) could never check this account — it would report it unreconcilable instead of
  balancing. That is a new class of defect: the other rejected PDFs at least kept the tripwire
  working.
- **The PDF dates transactions where the CSV posts them.** `COFFEE HOUSE AUBURN` is 07 Sep in
  the PDF and 08/09 in the CSV; `AMAZON WEB SERVICES` is 02 Sep against 03/09. So the same
  purchase differs in *date* as well as text, with no receipt to bridge it.
- **The text differs anyway:** `COFFEE HOUSE AUBURN AUBURN AUS` (single-spaced, `AUS`) against
  the CSV's `COFFEE HOUSE AUBURN      AUBURN       AU` (padded, `AU`).
- **It adds no history:** one statement period (12 Aug–09 Sep) against a CSV covering
  19 Jun–16 Sep.

Three PDFs assessed, one admitted, so §4 now states the rule rather than the three verdicts:
a PDF is admitted only when it is the only (or the broader) source for an account **and** its
rows mint the same ids as whatever else is ingested for that account — both demonstrated, not
assumed. `cba-pdf` passed both; ING and BankWest fail the second, which is the one that
silently corrupts the ledger rather than merely wasting effort.

### S8 — BankWest ships two CSV exports; the debit sign is inferred, not assumed

A second BankWest export (1,553 rows, Jan 2024–Sep 2026, against the first file's 219 rows of
Jun–Sep 2026) differs in two ways, both of which the existing parser refused rather than
mis-read.

- **The fifth header column is `Cheque Number`, not `Cheque`.** Everything else matches. Both
  spellings are now accepted; the header check stays strict otherwise, since it is what stops a
  file of another type being read as BankWest.
- **Debits are negative in this export and positive in the other.** The parser now infers the
  convention per file — all-positive or all-negative — and **rejects a file that mixes signs**.
  Inferring is sound here because the choice is observable and self-consistent: under the right
  convention the balance chain closes over all 1,553 rows, and under the wrong one it breaks
  1,516 times. Hard-coding either convention would silently invert 1,517 amounts, and amount is
  hashed into identity, so the result would be permanently wrong ids rather than a visible
  error. The `debit must not be negative` check written for the first export is what caught it.
- **The two exports agree exactly where they overlap:** all 217 non-pending rows of the first
  file appear in the second with the same date, the same signed cents and the same narration, so
  they mint the same ids and dedup. The full export is a strict superset — two more years of
  history and ten later rows.
- **The pending-row decision (S2) is now confirmed by the data.** The authorisation
  `AUTHORISATION ONLY - TELCO PAYMENT SERVS … 91.91` in the first export appears in the second
  as a settled `TELCO PAYMENT SERVS SYDNEY NS … -91.91` — same purchase, different text, and
  therefore a different content hash. Had the pending row been ingested, the journal would now
  hold both, unremovably. Skipping it was right for exactly the reason given.
- **28 rows in the full export need `occ > 0`** (identical date, amount and narration), against
  zero in the small one. Content-hash identity and the day-atomic batching rule (§2.5, §4) are
  load-bearing for this account, as S2 anticipated but could not demonstrate.

## The web service, and who owns the rules (W)

Prompted by asking how categorisation works as an ongoing monthly routine rather than a one-off.
The answer needed one structural change and a settled ownership rule.

### W1 — `trex-grid` and `trex-resolver` merge into `trex-web` (amends SPEC §1, §5.4, §5.5, §9)

- **The split's reason was never exercised.** G10 separated them so the read-only table could be
  exposed differently from the action-taking service. Both have always run published to
  `127.0.0.1`, so the boundary was theoretical — while the cost became concrete the moment the
  table needed to write a pin: two origins, and the resolver's CSRF guard rejects cross-origin
  posts by design. The choices were to widen that guard, to split the UI across two windows, or
  to stop having two origins. The third is the one that removes a problem rather than managing it.
- **What merging buys beyond that:** one journal fold instead of two of the same journal, one set
  of config files watched instead of two, one CSRF posture, one process and image (five runnable
  modules, not six), and a UI where you categorise while browsing instead of correlating two tabs.
- **What it costs, recorded honestly:** the read-only view can no longer be exposed on its own.
  With no authentication (§9) the merged service must stay on loopback. §9 keeps the way back — a
  second listener in the same process, serving read-only endpoints on another interface — so the
  boundary is recoverable without re-splitting.
- **The library becomes the service.** After the merge `trex-web` (watcher, SSE, static serving,
  security headers) has exactly one consumer, so keeping it as a separate module would be
  ceremony. Three modules collapse into one and the reactor goes from ten to eight.
- **§5.5 is kept as a pointer rather than renumbered.** `§5.6` is referenced 37 times across 24
  files; renaming it to save a gap in the numbering would be churn for nothing.

### W2 — Two rule sets, never merged (restates §3.4 and §5.6 as one principle)

`transfers.yaml` is read by the sequencer **at ingest** and decides journal *state*: HELD,
MATCHED, EXTERNAL, and whether a TRANSFER line exists. `categories.yaml` is read by consumers **at
read time** and decides nothing durable. The patterns can look alike — `Internal Transfer` matters
to both — and the files still stay apart, because one is permanent and the other is free. Merging
them would drag category tuning into the write path, where §3.2 means it could never be re-run.

### W3 — Ownership splits by file: you own rules, the service owns pins (amends §5.4, §5.6, §6)

> **Partly reversed by V2.** The ownership split stands and `comment` stays a first-class
> field; the prohibition on machine-writing `categories.yaml` does not.

- **Pins leave `categories.yaml` for `pins.yaml`.** Two facts decide it, neither of them taste:
  the YAML mapper cannot round-trip `#` comments, so a machine rewriting `categories.yaml` would
  destroy the notes that explain why `\bfees?\b` is anchored and why `INSURANCE` precedes
  `BILLS`; and rule order is a judgement — a new specific rule usually has to *precede* a general
  one — while pin order is irrelevant because pins match exact ids. A machine may only append
  where appending is always correct, which is true of pins and false of rules.
- **So the UI writes pins and generates rules.** Clicking "pin this" is friction-free because it
  is safe; a rule arrives as YAML to paste, which is also the discipline that stops the rule set
  overfitting one merchant at a time.
- **`comment` becomes a field on both**, not a `#` comment. It survives a machine rewrite, and it
  reaches the person asking "why is this GROCERIES?" through the UI rather than only the
  maintainer reading the file.
- **Both files stay in git.** The diff is the review — every over-broad pattern found so far
  (`coffee` contains "fee", `Gregory Hill` contains "rego") was caught by reading one — and the
  pair at a commit is the as-of history that §0.7 relies on instead of storing categories.

### W4 — No rules service (considered, rejected for now)

> **Revisited by V1.** The condition this decision named as missing — a writer — now exists.

A service that *executes* rules was rejected outright: categorisation is a pure function with no
failure mode today, and calling out per row would add latency, caching and an outage story to a
regex match. A service that only *serves* rule content is more defensible — one writer, one
reload point, central validation — but it still needs a local cached copy to start when the
service is down, which reinstates the file, and if its store is not the git-tracked file then the
reviewable diff is lost, which is the argument that already defeated SQLite for this data. At one
machine and one writer it earns nothing. `Categorizer` stays the seam (C4): a `ServiceCategorizer`
can replace `RuleCategorizer` later without touching a consumer.


### W5 — The web pages are light-only, and a category's colour comes from its name

Both pages shipped with a light palette and a `prefers-color-scheme: dark` override. Every device
here runs dark, so what was actually seen was the dark palette — a near-black page for a table
that is almost entirely figures. Dark is a good default for reading prose and a poor one for
scanning a dense numeric grid, where the contrast that matters is between a row and its
neighbour, not between text and background. The override is gone rather than inverted: keeping
both doubles every palette change, and the tinted chips below are only legible over a light row.

The chips are the one place colour carries information. A category is *derived* (§0.7) and
declared in `categories.yaml`, so nothing in config names a colour for one, and a hard-coded map
would need editing every time a category is added — exactly the upkeep the derived design avoids.
The hue is therefore computed from the category name: stable across reloads, identical on both
pages, free for a new category, and wrong for nobody, since the name is on the chip and the colour
only groups. Two origins stay deliberately colourless — `STRUCTURAL` is the journal's own answer
rather than a rule's, and `UNCATEGORIZED` is a legitimate outcome (§0.6) that should not look like
an achievement.

## V — the consumer API (trex-gateway)

### V1 — `trex-gateway` becomes its own process (amends SPEC §1, §5.4, adds §5.7)

Three days after merging `trex-grid` and `trex-resolver` into one service (W1), we split a service
back out. That deserves an explanation rather than a shrug.

W1 merged two processes that were **doing the same work twice** — two folds of one journal file,
two watchers, two CSRF postures — and bought nothing for it, because both ran on loopback. The
split now is the opposite shape: one process doing **two different jobs**, holding journal state
*and* serving a browser. The test is not "how many processes" but "does the boundary carry a
difference". W1's boundary carried none. This one carries the rule files.

The trigger is that rules became writable. While they were hand-edited, categorisation was a pure
function that every consumer could run for itself (§5.6), which is exactly why W4 rejected a rules
service — *"at one machine and one writer it earns nothing."* A writable rule set supplies the
writer W4 said was missing. With two consumers loading the same files on independent schedules,
"what is the current rule set" has two answers and the one that wrote last cannot tell the other;
the Firefly egress projecting under a rule set the UI never showed is the failure that produces.
One owner, one reload point, one `rulesRevision` is not buyable in-process once a second consumer
exists — and phase 1.5 brings one.

What we pay: a network hop, a sixth runnable module, and a failure mode where categorisation had
none. The last is the real cost and is answered directly — trex-web keeps the last good snapshot
and labels it stale, so a trex-gateway restart degrades the page rather than emptying it.

Rejected: keeping it as an in-process layer with an extraction seam (cheaper, and every listed
behaviour works, but it defers the ownership guarantee that is the entire point, and trex is meant
to be a design worth re-using — a seam that has never been a real boundary is not evidence that it
works); and a split where trex-gateway materialises only the table while trex-web keeps folding for
HELD/REVIEW, which restores precisely the double fold W1 removed.

### V2 — the machine may rewrite `categories.yaml` (reverses W3 and SPEC §5.4)

W3 said a machine may only append, and only to `pins.yaml`; SPEC §5.4 said a rule is *"generated,
never written"* and handed to the clipboard. Both are withdrawn. The copy-paste step was never a
safety feature — it was a workaround for two problems that turned out to be solvable:

- **Comments.** The objection was real: `trex.journal.Yaml` is a Jackson mapper with no comment
  round-trip, and 70 of `categories.yaml`'s 218 lines are `#` comments carrying the *why*. The
  error was concluding that "the machine writes the file" means "the machine re-serialises the
  file". An amendment **splices text** at a computed line span. Nothing else in the file is parsed,
  so every comment survives byte-identical and the diff is the change rather than a reformat.
- **Ordering.** The objection was that a specific rule must usually precede a general one, and that
  this is judgement. It stopped being judgement the moment the service held every line and every
  compiled rule: a candidate's match set is computable, and so is which existing rule owns each row
  it would take. Collision-free → append; collides and should win → insert before that rule;
  collides and should not win → redundant, refused with the reason.

The user asked for **full edit rights**, not insert-only: the service may rewrite and delete
entries. That raises the stakes on validate-before-swap and the revision stamp from prudent to
load-bearing, since a bad write can now damage hand-written rules rather than merely add a bad one.

### V3 — preview then apply, never silent

Every write is composed, dry-run and shown as a diff with its blast radius — *"matches 23 rows,
$1,240: 19 UNCATEGORIZED, 4 currently GROCERIES via rule #7"* — before an explicit Apply. Rejected:
applying immediately with `git diff` as the review. The numbers that decide whether a rule is right
are exactly the ones you cannot see from the pattern, and a rule that silently steals four rows
from an earlier one is the specific mistake this whole mechanism exists to prevent.

### V4 — first-match-wins survives; priority numbers rejected

Explicit `priority:` integers would make appending always correct and placement a non-question.
Rejected anyway: it changes §5.6 semantics, requires re-checking all 24 existing rules, and turns a
wrong number into a silent recategorisation of history. File order stays the decision, which also
keeps the property that reading the file top to bottom tells you what happens.

### V5 — SSE carries a revision, never rows

A rule change can move any row, so the honest event is "everything may have changed". Pushing the
materialised set to say so costs ~1.2 MiB per edit per client at current scale (measured: 1 853
rows). Frames carry `{n, offset, rulesRevision, …}` and clients refetch what they are showing —
the same shape the journal head events already use, for the same reason.

### V6 — decisions go through trex-gateway too, making it the one consumer API

trex-web was to proxy reads to trex-gateway and decisions to the sequencer. Both now go to trex-gateway,
which forwards decisions on. The hop is only worth it because something happens at it.

**What happens at it.** The `CONFIRM_TRANSFER` preconditions — two distinct rows, amounts equal and
opposite and non-zero, accounts different, currencies matching — currently live in the page's
JavaScript (§5.4). That protects exactly one consumer: a browser running our script. Any other
caller — a CLI, a fix-up script, the Firefly egress reconciling something — gets none of it and
discovers its mistake as a sequencer `Rejected`, if it is lucky, or as a decision it did not mean,
if it is not. trex-gateway holds the ledger, so it is the only place those checks can be enforced for
everyone. Moving them there is the substance of this change; the routing is a consequence.

**What does not change.** The sequencer stays authoritative (§3.5). trex-gateway forwards and never
writes the journal; its check fails fast and can only refuse what the sequencer would also refuse.
A gateway that could *approve* something the sequencer would decline would be a second authority,
which is the one thing this must not become.

**What it costs.** §5.7 loses the clean line "nothing it does is irreversible": the service that
owns the rule files now mediates permanent decisions. The blast radius of a trex-gateway bug grows
from a wrong category (free to fix) to a wrong decision (§3.5, permanent). Accepted because
forwarding is a narrow operation with an authoritative checker behind it — but it is why the
preconditions are specified as *refusals* and why the gateway gets test 22 of its own.

Two things fell out for free. `decisionRef` becomes pass-through, so a scripted consumer gets
idempotent retries where the page — which has nothing to retry with — keeps the minted `ui-<UUID>`.
And because trex-gateway is both the forwarder and the follower, it re-reads the journal immediately
after a `Resolved` instead of waiting up to `--poll-ms` for its own watcher, so the SSE frame
follows the decision rather than trailing it.

The shape this settles: **one address per role.** The sequencer writes the journal. trex-gateway is
the consumer API — everything read, decided or categorised arrives there, with one posture and one
set of checks. trex-web is a static server with a proxy and an SSE relay, and knows one upstream.


### V7 — the name is `trex-gateway`, and it understates the fold on purpose

Considered: `trex-api` (accurate about the role, generic, and the sequencer has an API too),
`trex-view` (precise about the materialised fold, but reads read-only for a service that writes
rule files and forwards permanent decisions), `trex-desk` (the metaphor fits, but every other
module is a plain functional noun).

`trex-gateway` names what callers experience — one door, checks on the way in, fan-out behind it —
and that is the property worth putting in the name now that a native app, a script and the Firefly
egress will all arrive the same way. Its known weakness is that "gateway" suggests a stateless
pass-through, while the expensive, stateful part of this service is the whole journal folded in
memory and recomputed on every rule change. Accepted with eyes open: the internal component that
holds that fold keeps the name `MaterializedView`, so the thing the module name hides is spelled
out the moment anyone opens the code.
