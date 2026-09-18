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
- G10 (a): separate `trex-grid` process + shared `trex-web` module extracted from the resolver (watcher, SSE, static serving, security headers). Lets the read-only grid be exposed differently from the action-taking resolver.
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
  needs a tenth, packaging-only module, and SPEC §1 fixes the reactor at nine named
  modules. Per-module jib keeps SPEC.md untouched and is how jib is meant to be used.
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
