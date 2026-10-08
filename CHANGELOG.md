# Changelog

All notable changes to trex. This file starts at the v2 build; v1's history lives in git and in
the archived sibling project `../TrexV1` (`SPEC.md` / `DECISIONS.md`), which is reference only and
is never imported by v2 code.

The format is loosely [Keep a Changelog](https://keepachangelog.com/); v2 has not been released, so
everything is under **Unreleased**.

## [Unreleased]

### Fixed

- **Receipt-share shaping is per-fact.** The pre-filter shaped *every* row carrying a receipt once
  *any* pair in that receipt's group had a plausible counterpart; ING receipt numbers are not unique
  across products or eras, so an unrelated row could inherit the shape. Now only the facts that
  themselves participate in a plausible pair are shaped (the proposal's wording, `derive/8`). On the
  dev fixture a 2026 card purchase carrying a 2024 mortgage repayment's receipt no longer pools, and
  `UNMATCHED_LEG` falls 8 → 6.

### Changed

- **`RESTATEMENT` is now disjoint from `POTENTIAL_DUP`.** It requires *different merchant stems*
  (a different reading of the amount), so identical-text or same-stem pairs are no longer
  double-labelled: a same-stem pair is a duplicate. On the dev fixture this takes the restatement
  queue from 44 to 5, and every remaining item is a genuinely different merchant string. The
  comparison is `MerchantStem.restatement`, used by both `derive()` and the hub's cluster
  reconstruction; `deriveVersion` was `derive/6`.

### Added

- **Attached clearing transfers** (`ATTACH_ACCOUNT`): a pruned counterparty period (statements gone)
  is reconciled by naming the transfer-shaped legs and a `clearing` account — the legs become
  transfers with an account side, never a fabricated fact. Derive also materialises the clearing side
  as a **derived** transaction row (`synthetic: true`, reserved `clr|…` id, no evidence, not a
  decision target) so every transfer has two concrete legs and per-account queries are complete; the
  clearing account's running balance lands on its declared closing. `deriveVersion` is `derive/7`.
  On the dev fixture this resolved **154** `UNMATCHED_LEG` (163 → 9) into transfers.
- **A unified account chip**, configured per account by `chip_color` in `accounts.yaml` (a palette
  name; presentation only, never identity or logic; unset falls back to a deterministic colour). One
  shared `account.js` renders the same solid chip in **Blotter, Review, Eyeball, Accounts and
  Chains**; `refdata` carries the colour. The Review queue also filters by account
  (`/api/review?account=`).
- **Notes and comments as decisions**: `NOTE` annotates a transaction — one per target row,
  accumulating as a thread and removed only by `REVOKE`; a group annotation is a batch of `NOTE`s,
  one per member id. `DISMISS` and `USER_ACK` carry an optional `comment`. Derived `note_current`
  projects the effective threads; the hub serves `GET /api/notes` and `GET /api/dismissals`; the
  Blotter and Eyeball gain a per-row **Note** (and an annotate over a selection), Review's `Dismiss`
  takes an optional reason, and a review cluster can be annotated in one fan-out.
- **The trigger runner** (`trex runner`): an on-demand job dispatcher and staging inbox, loopback
  only, proxied by the hub. Jobs are invocations of existing subcommands — `ingest`, `egress-firefly`
  (plan/verify/apply), `journal-snapshot` — with a single FIFO worker, a bounded run history, SSE
  output, and sync/async (`?sync=true&timeoutMs=`). The hub UI gains the **Jobs** mode: the staging
  inbox with an upload drop-zone, per-file Type/Account and a derived *ingested* tick, the egress
  buttons, a Snapshot-now button, the ingest history, and an ops strip (last plan/apply and
  unprojected/drifted/orphaned).
- **The uniform envelope** on every log line: `n, kind, v, atMs, env, source, target`, with
  namespaced kinds (`trex.fact`, `trex.decision`, `trex.ingest`) and `v = 1`. `env` is the
  sequencer's `TREX_ENV`; `source` is the writing process instance, declared in `sources.yaml` and
  refused if unknown; `target` is `none`. A reader skips an unknown kind (`Unknown`) and refuses a
  known kind at a higher `v`. This is a MAJOR line-format change.
- **Ingest events** (`trex.ingest` `start`/`complete`): the stream is self-documenting; the index
  derives `ingest_batch` (the markers paired) and `GET /api/ingests` exposes the history.
- **The source archive**: ingest gzips the exact bytes to
  `sources/<Y>/<M>/<D>/<HHMMSS>-<name>.gz` (`--source-archive`, `--source-name`).
- **Journal snapshots**: `POST /maintenance/snapshot` writes a dated gzip copy of the log prefix to
  `journal/trex-<ts>.jsonl.gz`; `trex snapshot`; the `journal-snapshot` runner job. A copy, never a
  rotation.
- **The runner's schedule** (`schedule.yaml`, empty by default): intervals with a phase — `every` +
  `at` (a local `HH:MM`), plus `on` (one weekday) and `zone` for a weekly job; not cron. A day-sized
  period is a calendar cadence (the local wall time survives DST), a due job already running is
  skipped, and there is no catch-up. `GET /jobs` reports `nextRun`, runs carry a `trigger`, and the
  Jobs card shows the next snapshot.
- **The Accounts mode** in the hub (`GET /api/accounts`): per-account earliest/latest, the newest
  ingest, and a facts-derived coverage strip by week or month over a 3/6/12/24-month or all-time
  window. A quiet bucket inside an account's range is a hole (check me); outside it is unanswered.
  Read-only: the strip is recomputed from the current rows per request, and statement periods are
  not recorded, so a hole never asserts "not imported".
- **Roles and the balance check** (P5, §6.9). `profiles.yaml` binds account-scoped rules that
  classify a row `noop` (recorded and visible, not a posting); `MARK_NOOP`/`UNMARK_NOOP` decisions
  override a profile in either direction. Derive excludes `noop` rows from shaping, pairing and
  units; reconciliation runs over `transaction` rows only and names every exclusion with the rule or
  decision that classified it. A new `BALANCE_BREAK` review item opens on an account whose chain
  does not close (subject the account ref, dismissable by account). The Blotter shows and filters the
  role; a new **Chains** mode shows forks with both sides and a per-side `noop` preview.
- **Transfer patterns, the pool and rails** (P6, §9.9.C). `transfers.yaml` gains ordered per-account
  `transferPatterns` (first match wins; the account's entries before `default`), each carrying a
  `rail` and an optional `shape: false` (rail-only). Shape is the pre-filter; the pool ladder
  (receipt → same-day → windowed) requires a **mutually unique** counterpart and refuses to guess a
  tie; the interim `transferStem` tier is retired, so text is never compared across accounts. Rails
  (`OSKO`/`PAYID`/`BPAY`/`BANK_TRANSFER`, direction from the sign) are derived per leg and shown in
  the Blotter; a matched pair records the payer's method. A receipt shapes/pairs only with a
  plausible counterpart. A **Transfer patterns** editor previews the legs it would pot and the pairs
  it would make.
- **Clearing accounts** (P7, §6.10). `balanceSource: clearing` with `closingBalance`/`closedAt` — a
  closed counterparty that holds no facts. A transfer pattern may name a `clearing:` account and its
  leg pairs directly (one real leg + an account side, no window, no ambiguity). Reconciliation
  reports `CLEARING` and the opening is computed backwards (`closing + Σ movements`) so the derived
  balance lands on the declared closing; the computed opening is shown in Accounts, reconcile and
  opening. Registering the real statements and dropping the `clearing:` line re-pairs with no
  leftover synthetic side.
- **Stream promotion** (P8, §14.1). `trex stream export` writes the journal as gzipped JSONL plus a
  manifest (head, counts, `configRevision`, `deriveVersion`); `trex stream ingest` appends it line
  for line to another sequencer through a new `POST /stream`, re-validating each line as it lands
  (`n` contiguity, known kind, known `accountRef`, decision cross-references). Ingest skips a prefix
  already present, refuses a gap or a `configRevision` mismatch before writing, refuses a missing
  evidence id, and reports the landed prefix when a bad line stops it. The `stream` runner job runs
  both modes; the runner mounts the journal read-only.

### Changed

- **v1 left the repository.** The six v1 modules (`trex-core`, `trex-journal`, `trex-sequencer`,
  `trex-ingest`, `trex-egress`, `trex-ws`), their documents (`SPEC.md`, `DECISIONS.md`,
  `V1-CLAUDE.md`, `notes/`, `SPEC-REVIEW.md`, the v1 README) and the v1 deployment set
  (`compose.yml`, `deploy/compose`, `deploy/dev`, `deploy/systemd`, the v1 `deploy/bin` scripts)
  are archived in the sibling project `../TrexV1`. The `v1` Maven profile and the root
  `compose.yml` are gone; the shared `deploy/config` and all of `deploy/v2` stay.
- **`--allow-apply` is opt-in**; the base compose leaves apply locked and the dev overlay unlocks
  it. The UI gates Apply behind a fresh Plan and quotes that plan's counts.
- The sequencer validates a batch's `source` against `sources.yaml` and stamps `env` from
  `TREX_ENV`.
- `logHeadN` counts every kind, so the status `n` includes ingest events.
- `REVOKE`'s wire field is `revokes` (`target` moved to the envelope).
- The index schema gained the envelope columns and `ingest_event`; an old-shaped index is dropped
  and re-mirrored.
- **`deriveVersion` is `derive/5` and `hashVersion` is `statehash/4`** (roles, rails and the balance
  check; a role and a rail are part of a row's state hash). The transfer matcher no longer compares
  text across accounts.
- `txn_current` gained `role` and `rail`, and `transfer` gained `method` and `clearing_account`; the
  affected derived tables are dropped and rebuilt on the first apply at the new shape.

### Fixed

- The status `n` under-reported the log head once ingest events existed (`logHeadN` ignored them).
- The hub's `/api/status` counts did not include `ingest_event`.
- A receipt is not unique across transfers, so two derived or decided pairs that share one could
  mint the same `TRF-<receipt>` and overwrite a transfer row, silently unprojecting both its legs;
  a taken id now falls back to the order-independent leg hash.
- The duplicate/restatement scan compared every current fact with every other (O(n²)); it now only
  compares within each `(account, date)` run, which is the only place either predicate can hold.
- The hub detected a replaced journal only when it shrank; it now also fingerprints the last indexed
  line, so a same-or-larger file materialised from different bytes forces a refold.

## [0.1.0] - 2026-09-29

The v2 system per `V2-PROPOSAL.md`, built in the stage order of `V2-IMPLEMENTATION-PLAN.md`:
`trex-v2-core`, `-log`, `-index`, `-sequencer`, `-hub`, `-egress`, `-ingest`, `-dist`.

### Changed

- **Read markers are per row.** `USER_ACK` records one row (with the row's `stateHash`), `USER_UNACK`
  is its family inverse, and `user_ack` is keyed by `(user, external_id)`; the period is only the
  Eyeball's bucketing view, never stored or cleared. `StateHash` is now a row hash
  (`statehash/2`); `txn_current` carries each row's `state_hash`; the per-period `/api/acks/diff`
  is gone (staleness is per row). `deriveVersion` is `derive/3`. See `V2-PER-ROW-ACK-PLAN.md`.

### Added

**P0 — the spine**
- Pure core model: `Fact`, `Decision`, `Config`, identity (`Ids`), `occ`, `Clean`, `MerchantStem`,
  and the whole `derive()` pipeline, with `deriveVersion`/`hashVersion`/`configRevision`.
- `trex-v2-log`: v2 fact/decision line kinds, framed reader/writer with torn-tail recovery, the
  JSON/YAML mappers, and the typed config loader (`accounts`, `users`, `categories`, `transfers`,
  optional `refdata`), which runs `categories.tests.yaml` on every load.
- `trex-v2-sequencer`: the only writer — `POST /facts` (all-or-none, whole-observation dedup;
  `Appended | Duplicate | Flagged | Rejected`) and `POST /decisions`, `GET /head`, one atomic append
  and one fsync per batch, a single-writer `FileLock`.
- `trex-v2-index`: JDBC materialiser (log → mirror → derived tables), index lock, offset, and an
  offline `index --rebuild`.
- `trex-v2-dist`: one shaded `trex-v2.jar`, picocli subcommands.
- The one-shot v1-format importer (dev tool) and the `deploy/dev` seed/reset harness.

**P1 — the hub**
- `trex-v2-hub`: index owner with automatic re-derive on journal/config change; blotter read API;
  decision path with precheck, `409` on a stale view, and forwarding to the writer; `USER_ACK` with
  state hashes and per-user invalidation; reflow preview served from the index; SSE snapshots and
  deltas; the modular UI (`blotter`, `review`, `eyeball`, `rules`).
- Reconciliation over derived state, including `DECLARED` accounts.
- The recovery drill as a test.

**P2 — the workbook**
- Rule lint (shadowed, never-fires, catastrophic regex, orphan/redundant pins), `categories.tests.yaml`
  fixtures run on load and in CI, suggestions from uncategorised clusters and pin clusters (with a
  proposed regex and its measured effect), coverage and trend.

**P3 — egress**
- Firefly plan/apply/verify convergence, the drift taxonomy with de-projection, `projection_state`,
  account preflight and provisioning, opening balances, category seeding, and the retry taxonomy.
- The archive byte mirror with an evidence copy.

**P4 — ingest, evidence, feeds, pending**
- `trex-v2-ingest`: `ing-csv`, `bw-csv`, `cba-csv`, `cba-pdf` adapters, a content-addressed evidence
  store, whole-file validation, day-atomic batching, gzip, and exit codes `0/1/2/3/64`.
- Pending settlement in `derive()` with `SETTLE`, `AMBIGUOUS_SETTLEMENT` and `STALE_PENDING`.
- Feed adapters with `source_cursor`, and the re-parse diff/apply (`SUPERSEDE`/`RETIRE`).

**Closers**
- `export csv|json|sqlite`; `GET/POST /api/cursors`; evidence indexing; `verify --evidence`; and an
  end-to-end re-parse apply test.

**Eyeball (§10.3)**
- The guided walk: the nine anomaly checks over derived state with an explicit `asOf`, review items
  scoped to the period, and transactions bucketed by **day, week or month**
  (`GET /api/eyeball?bucket=`).
- Per-row pinning with a category "brush" (choose once, then one click per row) and per-period
  `USER_ACK` close.

**Deployment**
- One jib image `trex/trex-v2`, role by subcommand; `deploy/v2/` with compose, systemd units,
  timers, `trex.env` and a README.

**Docs and harnesses**
- `deploy/v2/`, `docs/V2-PARITY.md` (the v1↔v2 ledger), `SPEC-REVIEW.md`, this changelog, and
  `V2-SPEC.md`.
- `StatementsE2ETest`: the whole pipeline over real statement files (fixture-tagged, skipped when
  absent), which prints the workbook analysis.

### Changed

- `occ` matches v1 exactly (per identical content in the batch): distinct receipt-less rows on a day
  each get `occ 0`; identical rows get `0,1,2`; receipt rows are `0` and do not advance.
- Transfer matching keeps v2's stricter rule: T2/T3 require an equal `transferStem` (a deliberate
  divergence from v1's amount/date-only matcher — see `docs/V2-PARITY.md`).
- Merchant-identity category rules are **direction-blind**, so a refund lands in the category it
  reverses and the month nets out; direction stays only where the word alone does not identify the
  money (`SALARY`/`INTEREST_EARNED` in, `INTEREST_PAID`/`FEES`/`VISA_FEES`/`CASH_WITHDRAW`/`SAVINGS` out).
- `SCHOOL_FEES` now sits above `FEES`, so a school's "fees" is not read as a bank charge.
- Rule gaps closed from a real statement run: council rates, singular `WAGE`, `UBER   *TRIP`
  spacing, `eg group`, `cwh`, `super ?cheap`, retail chains, takeaway chains, and CCS.
- Review-item details are human-readable (`3× COFFEE CART, SYDNEY on 2026-09-01`, or the differing
  text for a restatement) instead of a bare list of ids; `deriveVersion` moves to `derive/2`.

### Fixed

- `derive()` emitted duplicate `review_item` keys (`POTENTIAL_DUP`/`RESTATEMENT` were pairwise), which
  the `(subject, kind)` primary key rejected on real history; both now emit one item per cluster.
- `INEFFECTIVE_DECISION` items were emitted before the `DISMISS` pass, so a `DISMISS` naming an unknown
  id was never surfaced.
- Opening balances were never seeded into the index.
- `ingest --types` demanded `--evidence`, making the adapter list unreachable.
- `IngestClient` did not decompress gzipped sequencer responses.
- The archive/schema reader split on a semicolon inside a comment.

### Notes

- v2 is the default reactor; the v1 modules are out of it and build with `-Pv1`, so a normal
  `mvn verify` builds only the system being built.
- There is no migration (`V2-PROPOSAL.md` §16 is retired): the v1-format importer is a dev tool, and
  the v2 log starts at day 0.
- The private journal and real statement files are never committed; the v2 end-to-end and the P0
  fixture test are `@Tag("fixture")` and skip with a reason when the data is absent.
