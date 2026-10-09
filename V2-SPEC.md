# trex v2 — specification (as built)

**Status.** This is the *as-built* specification of the v2 system: what the code in
`trex-v2-*` actually does. `V2-PROPOSAL.md` remains the authoritative source of intent
(`AGENTS.md`), and `V2-IMPLEMENTATION-PLAN.md` remains the build order. Where this document and
the proposal differ, **the proposal wins**; where the difference is deliberate or the proposal is
silent, `docs/V2-PARITY.md` records it. This file is descriptive, not a second authority — if it
drifts, the code and the proposal are the truth.

v1 is archived in the sibling project `../TrexV1` (`trex-core`, `trex-journal`, `trex-sequencer`,
`trex-ingest`, `trex-egress`, `trex-ws`): reference only, never imported by v2 code, and not
migrated from.

---

## 1. Invariants

1. **The log is the only truth.** Everything else — the index, review queues, projection state,
   ACKs — is disposable and reproducible by `trex index --rebuild`.
2. **Facts, decisions, and ingest events only.** A fact is what a source said; a decision is what a
   person concluded; an ingest event is what an ingest did. The writer never interprets: no
   matching, no category, no derived review flags. It does keep an identity/observation index (to
   assign `occ` and dedup identical observations) and returns a `Duplicate`/`Flagged` row outcome —
   bookkeeping, not semantics.
3. **`derive()` is pure.** Same inputs (including `asOf`) → same output. No clock, no I/O, no
   environment, no unordered iteration.
4. **Decisions win over derivation**, and every id resolves through the supersession map before a
   decision is applied.
5. **Nothing is deleted.** Undo is an appended family inverse or `REVOKE`.
6. **One writer, one atomic append, one fsync per batch.**
7. **No category is ever a property of a transaction.**
8. **`externalId` is never rewritten.** Only it and the log's history are permanent.

---

## 2. Modules

| Module | Kind | Responsibility |
|---|---|---|
| `trex-v2-core` | library | model, identity, `Clean`, `MerchantStem`, `occ`, `derive()`, workbook, state hashes. Pure. |
| `trex-v2-log` | library | framed writer/reader + recovery, line codec, evidence store, `Json`/`Yaml`, `ConfigLoader`, stream export/read (`Streams`). |
| `trex-v2-index` | library | SQLite materialiser: log → level-1 mirror → derived tables; index lock; offset; rebuild. |
| `trex-v2-sequencer` | service | the only writer: `POST /facts`, `/decisions`, `/ingest`, `/stream`, `GET /head`, `POST /maintenance/snapshot`, recovery, fsync. |
| `trex-v2-hub` | service | index owner; blotter API + UI; decision precheck/forward; reflow; ACK; SSE; proxy to the runner. |
| `trex-v2-ingest` | library/CLI | adapters → evidence + facts + ingest events; source archive; whole-file validation; day batching; feeds; re-parse. |
| `trex-v2-egress` | library/CLI | archive byte mirror; Firefly projection; export. |
| `trex-v2-runner` | service | on-demand job dispatcher + staging inbox: a loopback trigger API, `ingest`/`egress-firefly`/`journal-snapshot`/`stream`, sync/async. Never writes the log or the index. |
| `trex-v2-dist` | packaging | one shaded `trex-v2.jar`; picocli subcommands select the role. No logic. |

`trex-v2-hub` depends on `trex-v2-sequencer` for shared wire DTOs only (a type-only dependency).

---

## 3. The log

- **Encoding.** Framed JSONL: one '`\n`'-terminated UTF-8 record per line. Every line leads with
  the **envelope** `{ n, kind, v, atMs, env, source, target }`, then `at` (a readable echo of
  `atMs`) and the kind-specific body. `kind` is namespaced (`trex.fact` / `trex.decision` /
  `trex.ingest`) and `v` is `1`. `atMs` is epoch millis (UTC) and the only time logic reads; it
  echoes a client-supplied `ingestedAt` when present, so it is **not monotonic in `n`** — order by
  `n`, never by `atMs`.
  `env`/`source`/`target` are exactly 8 chars of `[A-Za-z0-9_ ]`, right-padded with spaces: `env` is
  the sequencer's `TREX_ENV`; `source` is the writing process instance, declared in `sources.yaml`
  and refused if unknown; `target` is `none` (eight spaces). A record is complete iff it ends in
  '`\n`' and parses; a partial tail is left unread and a torn tail is truncated. One batch = one
  write + one fsync.
- **Facts** (`Fact`): `externalId`, `accountRef`, `date`, `amount` (cents, signed), `balance`
  (provenance only), `rawDescription` (verbatim), `receipt` (nullable), `occ`, `observation`
  (`posted | pending`), `sourceType`, `provenance` (`BANK | AUTHORED`), `evidenceId` (nullable),
  `parser`. The time is the envelope's `atMs`.
- **Decisions** (`Decision`): `action`, `actor` (`user | migrated | system`), `user` (nullable),
  plus the action's fields; `at` is `envelope.atMs`. The complete action set is `PAIR`, `UNPAIR`,
  `MARK_EXTERNAL`, `SETTLE`, `DISMISS`, `PIN`, `UNPIN`, `SUPERSEDE`, `RETIRE`, `MARK_NOOP`,
  `UNMARK_NOOP`, `ATTACH_ACCOUNT`, `REVOKE`, `USER_ACK`, `USER_UNACK`, `NOTE`,
  `DECLARE_COMMITMENT`, `RETIRE_COMMITMENT`, `IGNORE_RECURRING`, `PIN_COMMITMENT`,
  `UNPIN_COMMITMENT`, `NOTE_COMMITMENT`, `SETTLE_OCCURRENCE`, `EXCLUDE_COMMITMENT`,
  `INCLUDE_COMMITMENT`. On the wire, `REVOKE`'s target is
  `revokes` and a commitment's kind is `commitmentKind` — the envelope owns `target` and `kind`.
- **Ingest events** (`IngestEvent`): `phase` (`start | complete`) and `batch`; a `start` also carries
  `evidence`/`file`/`account`/`sourceType`/`parser`, a `complete` the `appended`/`duplicate`/`flagged`
  counts and a `status`. The facts sit strictly between the pair, so a batch's `n` range is the
  markers themselves.
- **Forward compatibility.** A well-formed line whose `kind` this build does not know is parsed as
  `Unknown` and ignored; a known kind at a higher `v` is refused (never silently misread).
- **Recovery.** A source journal may be materialised over a target at startup; the original is never
  written. A torn tail is truncated to the last complete line.

---

## 4. Identity

`externalId` is the SHA-256 of a canonical string, first 16 lowercase hex characters:

- receipt-keyed: `nk|<accountRef>|<date>|<receipt>`
- otherwise: `ch|<accountRef>|<date>|<amount>|<rawDescription>|<occ>` (raw verbatim)

A receipt is a key only while it names one content on its day: when rows of **different**
`(amount, rawDescription)` share `(account, date, receipt)` in a batch — ING's purchase, fee and fee
rebate — each mints the content hash like a receipt-less row (one shared `occ` counter), and keeps
its `receipt` field. Identical rows sharing a receipt stay one natural key. `Ids.mint` is the one
implementation, used by the sequencer and the re-parse preview.

A transfer id is `TRF-<receipt>`, or `TRF-<sha256("tr|<minId>|<maxId>")[0:16]>` when the legs share
no receipt (order-independent). `occ` follows v1: rows with identical `(account, date, amount, raw)`
in one batch take `0,1,2,…` in batch order; natural-key receipt rows are `0` and do not advance; distinct
content rows each take `0`.

---

## 5. Decisions

- **Revocable, never rewritten.** Family inverses (`UNPAIR`, `UNPIN`, `MARK_EXTERNAL`,
  `USER_UNACK`, a later `PIN` or `USER_ACK`) cover the everyday undo; `REVOKE(n)` is the general one
  and the only way back from `SUPERSEDE`, `RETIRE` and `DISMISS`. A later `REVOKE` may revoke a
  `REVOKE`.
- **Effectiveness is derived**, from order and `REVOKE`s, never stored.
- **Notes and reasons.** `NOTE` is a free annotation (§6.2): one per target row, accumulating as a
  thread and removed only by `REVOKE`, never edited — a change is a new `NOTE` plus a `REVOKE`. A
  group annotation is a batch of `NOTE`s, one per member id. `DISMISS` and `USER_ACK` carry an
  optional `comment`; a dismissed item's reason is surfaced through `/api/dismissals`, because the
  item itself leaves the queue. Annotations are display only — never identity, never logic.
- **Attached transfers.** `ATTACH_ACCOUNT` names transfer-shaped legs and a clearing account: the
  legs are transfers with an account side, not a contra fact (a pruned counterparty, §6.10). The
  account must be `clearing`; the decision is id-scoped and `REVOKE` releases the legs to the matcher.
- **Commitment curation.** Nine actions curate commitments (§6.11). `DECLARE_COMMITMENT` declares
  one, or confirms a detected candidate (`fromCandidate`); the rules are embedded, and a re-declare
  with the same id replaces the curated fields and the rule set — the edit — while cadence and anchor
  are not edited in place (a schedule change is retire + declare). `RETIRE_COMMITMENT` ends it at
  `endedAt`. `IGNORE_RECURRING` silences a detected candidate for good: newer facts do not reopen it,
  only `REVOKE` does. `PIN_COMMITMENT`/`UNPIN_COMMITMENT` place facts on a commitment or release them
  to its rules — the category `PIN` gesture, one fact each, latest effective wins, ids resolved
  through the supersession map; a pin naming a retired or unknown commitment is ineffective and
  visible. `EXCLUDE_COMMITMENT`/`INCLUDE_COMMITMENT` declare a fact not part of a commitment — a
  one-off inside a series — per (commitment, fact), latest effective wins; an excluded pair is
  never claimed (a pin falls through to the rules) and the fact itself is untouched. A note, a
  settle and an exclusion are conclusions about the past, so they may name a retired commitment.
  `NOTE_COMMITMENT` accumulates a thread on a commitment (the `NOTE` gesture, targeted; a
  note may name a retired commitment). `SETTLE_OCCURRENCE` concludes that occurrences were paid (or
  received) off-journal — a conclusion with no fact, attributed and revocable, rendered `settled`,
  never `occurred`. Commitment ids are decision-local and never touch the fact chain; a decision
  naming an unknown one is ineffective and surfaced.
- **References and structure are checked at the writer**; a semantically wrong but well-formed
  decision is recorded and surfaced as `INEFFECTIVE_DECISION`, never dropped.
- The hub prechecks against the index and returns `422` (naming the failure), `409` (stale view),
  or `503`/`502` (no writer / writer unavailable).

---

## 6. `derive()`

Pure function of `(facts, decisions, config, asOf)`, in the §9.9 order:

replay → effective decisions → supersession / chain resolution → current transactions (`txn_current`)
→ transfer pairing → pending settlement → categorisation → commitments → review items → notes
(`note_current`) → clearing legs → projectable units → state hashes → per-user ACK validity.

Every output list is ordered, so an unchanged input yields byte-identical tables. Versions are
recorded alongside, never inside, a hash: `deriveVersion = "derive/12"`, `hashVersion = "statehash/4"`,
and `configRevision` = SHA-256 over the sorted config files that can move derived state.

**Roles.** Every current fact has a derived role, `transaction` (default) or `noop`: a `noop` row is
recorded and visible but is not a posting — no chain edge, no transfer leg, no unit, no sum. The role
comes from an account-profile rule in `profiles.yaml` (matched on the cleaned description) or a
`MARK_NOOP`/`UNMARK_NOOP` decision, and a decision wins over the profile in either direction; the role
is never stored on the line, so a change is a reflow.

**Transfer shape and pairing.** A leg is *transfer-shaped* when its own account's first matching
`transferPatterns` entry says `shape: true` (§9.9.C), or it shares a receipt with a plausible
counterpart (opposite sign, equal magnitude, same currency, within `windowDays`). A pattern also
declares the rail method (`OSKO`/`PAYID`/`BPAY`/`BANK_TRANSFER`) and may be `shape: false` (rail-only)
or name a `clearing:` account. `PAIR` decisions win. The pool ladder pairs shaped, undecided legs in
`(date, n)` order: **T1** shared receipt, **T2** same day, **T3** within `windowDays`, each requiring
equal magnitude, opposite sign, different accounts, same currency, and a **mutually unique**
counterpart. More than one candidate opens `AMBIGUOUS_TRANSFER` and pairs nothing; text is never
compared across accounts (the v1 `transferStem` tier is retired). A clearing leg pairs directly with
its `clearing:` account — one real leg and an account side, no window, no ambiguity. A matched pair
records the payer leg's rail method; the rail direction is the sign.

**Commitments.** A commitment is a named expectation of a recurring money movement (subscription,
services, bill, insurance, fee, tax, income, interest_earned, interest_paid, loan, other), and its stage is a **sibling of categorisation**: it reads
the same current facts and no category output — categorisation reads none of it — so their order is
incidental and a complex rule may be duplicated in both. The nine curation decisions (§5) are
folded; candidates are detected over the current facts (transfer legs included, `noop` excluded) by
grouping on the frozen `MerchantStem.stem`: at least three occurrences; gaps within
`max(2 days, 20%)` of `{7, 14, 30, 61, 91, 182, 365}` days; regularity ≥ 0.7; same-day repeats
collapsed into one occurrence; refunds netted against the charge they reverse; a consecutive change
of `≥ 5%` or `≥ 50¢` is a price step; and a series whose every row carries
`Foreign Currency Amount:` compares the FCY price (a series where only some rows do stays on
AUD, so mixing bases cannot invent a step). Detection also **flags transient one-offs**: an
interior occurrence at least double (or at most half) its predecessor's magnitude whose successor
returns to that level counts as `outliers` — flagging only, the series and steps are unchanged;
excluding one is a decision (`V2-COMMITMENT-EXCLUSIONS-PLAN.md`). Coverage is relative to the accounts' posted
frontier, never a clock: `active` inside one cadence plus tolerance, `ended` beyond two periods,
otherwise `dormant`. A candidate is suppressed by an effective `IGNORE_RECURRING`, by a declaration
that named it as `fromCandidate`, or by declaration rules covering its facts, and only a non-ended
candidate raises review. Declared commitments generate occurrences from cadence and anchor with
calendar arithmetic (past 12 months through `asOf + 92 days`); each occurrence carries a
`± min(cadence/2, 7)`-day window, and one whose window closed with nothing covering it is `missed`.
A fact is claimed by a pin first, else by the latest declaration whose rules, sign and materialised
span admit it (facts are processed in `(date, n)` order), and every claimed fact **attaches to the
occurrence whose window contains its date** (V2-MANUAL-ARREARS-PLAN.md); several facts in one
window sum and the occurrence is `occurred` at the amount that moved. Nothing is allocated across
occurrences, nothing pre-pays, and a fact with no window becomes an `off_schedule` occurrence at
its own date. A `variable` commitment attaches the whole fact like any other; an `irregular`
commitment generates no dates and records each matching fact at its own date — never a window, a
miss, arrears or dormancy. Status is `occurred` (green, carrying the matched fact), `settled` (a
person concluded it without a fact), `due` or `missed`; `partial` is retired from automatic output
(an amount is what moved, never an inferred shortfall); `lapsed` mirrors the most recent
closed-window occurrence that is a hole — never the older backlog — and the holes accumulate as
**arrears**. A retired commitment stops at `endedAt`; a dormant one is
a question for a person (`DORMANT_COMMITMENT`), never auto-ended, and nothing is auto-forgiven.

**Review items** (`(subject, kind)` is the key): `POTENTIAL_DUP`, `RESTATEMENT`, `AMBIGUOUS_TRANSFER`,
`AMBIGUOUS_SETTLEMENT`, `UNMATCHED_LEG`, `STALE_PENDING`, `INEFFECTIVE_DECISION`, `BALANCE_BREAK` (a
statement account whose chain does not close; subject the account ref, so a `DISMISS` names the
account). Duplicate and restatement rows are grouped into clusters, so one item lists every member.
The two are **disjoint**: a same-stem pair is `POTENTIAL_DUP`; `RESTATEMENT` requires *different*
stems (a different reading of the amount), so identical or same-stem rows are never double-labelled.
The commitment kinds are `SUSPECTED_RECURRING` (one item per non-ended candidate; subject the
grouping stem, so a `DISMISS` names the series and ages on its newest fact) and `DORMANT_COMMITMENT`
and `COMMITMENT_ARREARS` (subject the commitment id, aging on the facts the commitment matched);
each is silenced only until a newer fact lands for its subject.

**The balance check and clearing.** Reconciliation runs over `transaction` rows only; a `noop` row's
edges and amount leave the chain and are named as exclusions. An account with unexplained forks is
`broken`; a `DECLARED` (cash) account reports its gap; a `CLEARING` account (§6.10) holds no facts and
reports a computed opening (`closing + Σ real movements`), so its derived balance lands on the
declared closing.

**Pending.** A pending fact is never current, never counted, never projected; it is `OPEN`, `SETTLED`
by a derived counterpart, or `STALE` past the account's `settlementWindowDays`, and is closed by
`SETTLE` or `DISMISS`.

---

## 7. The read model

SQLite, owned by the hub. Level 1 mirrors the log (`meta`, `fact`, `decision`, `ingest_event`); level
2 is derived and rebuilt wholesale: `supersession`, `chain_resolved`, `txn_current` (carrying the
derived `role`, rail, and a `synthetic` flag — true only for a derived clearing leg, §6.10), `transfer` (carrying the payer `method` and, for a clearing pair, the
`clearing_account`), `pending`, `review_item`, `category_current`, `pin_current`, `note_current`,
`commitment`, `commitment_rule`, `commitment_occurrence`, `commitment_note`,
`commitment_exclusion`, `commitment_fact` (the fact → commitment reverse map, §6.11),
`ineffective_decision`, `unit`, `projection_state`, `user_ack`, `source_cursor`, `evidence`, and the
`ingest_batch` view (the markers paired). A derived column's shape change drops and recreates its
table and clears the derived meta, so the next apply re-derives. Every table can be dropped;
`trex index --rebuild` reproduces them. The hub holds one writer connection and a small read pool,
with `query_only` reads.

The ingest history (`GET /api/ingests`) pairs each batch with its account's **frontier** — the
newest transaction date processed for the account, clamped to today — as a read over
`txn_current`; the Jobs page shows it per row and as a per-account fetch strip, so the next
statement file can be requested by date range (`V2-INGEST-FRONTIER-PLAN.md`).

---

## 8. Sequencer API (`trex-v2-sequencer`)

| method | path | purpose |
|---|---|---|
| `POST` | `/facts` | append a batch of fact drafts (`allOrNone`, `source` required, `target?`); per-row outcome `Appended \| Duplicate \| Flagged \| Rejected`. |
| `POST` | `/decisions` | append a batch of decisions (`source` required); reference/structure validation only. |
| `POST` | `/ingest` | append one ingest event (`source` required); the writer stamps the envelope and assigns `n`. |
| `POST` | `/stream` | append streamed raw JSONL verbatim (§14.1): `n`-contiguous, re-validated per line; returns `{appended, headN, stoppedAt, error}`. |
| `GET` | `/head` | the current log head `n` and byte offset. |
| `POST` | `/maintenance/snapshot` | write a dated gzip copy of the journal prefix to the archive; `?sync=false` runs it in the background. |

Every batch carries `source` (exactly 8 chars, declared in `sources.yaml`); an unknown source is a
`400`. The sequencer stamps `env` from `TREX_ENV`. Single-writer `FileLock`; the API is
unauthenticated and binds loopback by default.

---

## 9. Hub API and UI (`trex-v2-hub`)

`/head`, `/api/status`, `/api/refdata`, `/api/ledger` (filters include `role` and `leg`),
`/api/review` (filters `kind` and `account`),
`/api/commitments` (the commitment registry: faces, a candidate's grouping `stem`, rules, current
price, next due, arrears, notes thread), `/api/commitments/activity?id=` (a candidate's observed
facts, or a declared commitment's rule-matched facts across all history — life-bounded at its end
date, oldest first),
`/api/expected?window=today|week|month` (the window's
occurrences, the arrears backlog oldest-first with a running total, and committed totals by
direction),
`/api/transfers`, `/api/units`, `/api/reconcile` (with named `noop` exclusions), `/api/chains` (the
§6.9 balance check: per-account forks and a per-side noop preview), `/api/opening`, `/api/workbook`,
`/api/projection` (GET/POST), `/api/cursors` (GET/POST), `/api/decisions` (POST), `/api/acks`
(GET/POST), `/api/notes` (GET, `?externalId=`; the thread), `/api/dismissals` (GET; effective
`DISMISS` reasons), `/api/eyeball`, `/api/ingests`, `/api/accounts`, `/api/reflow/preview` (POST),
`/api/reflow/preview/transfers` (POST), `/api/config/categories` (GET/PUT), `/api/config/transfers`
(GET/PUT), `/api/jobs*` (proxied to the runner), and `/api/events` (SSE snapshot then deltas).

The UI has eight modes: **Blotter** (SQL-backed filters including role, a commitment chip, inline
decisions — pin, pair, mark external, `noop`/`unmark noop`, **note** (a `NOTE`, single or over a
selection), and **Assign**/**Unassign** to a commitment (`PIN_COMMITMENT`/`UNPIN_COMMITMENT`) — a note
badge on any row with a thread, status strip), **Review** (the derived queue, one decision away from
clear; a cluster expands to its members as colored chips, `Dismiss` takes an optional reason, a
cluster can be annotated in one fan-out, `SUSPECTED_RECURRING` offers Confirm/Ignore,
`DORMANT_COMMITMENT` Mark ended/Keep tracking, and `COMMITMENT_ARREARS` Settle/Snooze),
**Expected** (§6.11 — today/this week/this month: one row per occurrence with a green tick
(`occurred`) or red cross (`missed`), the committed totals split out/in, the **Catch up** panel of
every occurrence in arrears, oldest first, with a running total and Settle/Assign, and the
commitment registry — candidates named by their stem with their series evidence; every row opens
an **Actions** menu whose two panes show its activity (a table and a rudimentary price timeseries) —
Review/Confirm/Ignore for a candidate (or Record ended when the series has ended, at its detected
last charge), Re-declare/Retire/Note/Settle for a declared row — with
filters over origin/status/direction/text — and a rule lint panel; the four sections fold, their headers
carrying the live figures (`V2-EXPECTED-UX-PLAN.md`); the tab is walked as part of the regular
review routine), **Eyeball** (§10.3 — open items, the nine anomaly checks
with an explicit `asOf`, and transactions bucketed by day/week/month with a per-row `Ack`/`Unack`,
a per-row pin, a per-row **note**, and the commitment chip with **Assign**/**Unassign**), **Rules**
(category editor with blast-radius preview, lint, fixtures, coverage, plus a **Transfer patterns**
editor with the same preview contract), **Accounts** (§10.5 — per-account opening, earliest/latest,
the newest ingest, and a facts-derived weekly strip; a quiet week inside the range is a hole to
check, never "not imported"), **Chains** (§6.9 — the balance check per account, forks with both sides,
the computed clearing opening, and a per-side `noop` preview), and **Jobs** (the trigger runner: the
staging inbox, egress Plan/Verify/Apply, Snapshot journal, the ingest history, and the ops strip of
staleness — last plan/apply and unprojected/drifted/orphaned unit counts).

---

## 10. Ingest and evidence (`trex-v2-ingest`)

- **Adapters** by `sourceType`: `ing-csv`, `bw-csv` (debit sign inferred per file; mixed rejected),
  `cba-csv` (header-less), `cba-pdf` (Transaction Summary PDF).
- **Whole-file validation**: any bad row sends nothing. **Day-atomic batching**: a day is never split
  across calls. Exit codes: `0` ok, `1` bad rows, `2` transport, `3` rejected, `64` usage.
- **Evidence**: files are stored content-addressed (SHA-256) and never rewritten; a re-parse replays
  stored evidence, diffs it against the facts it produced, and (with `--apply`) posts the new facts
  plus `SUPERSEDE`/`RETIRE`.
- **Feeds** carry a `source_cursor` (GET/POST `/api/cursors`).
- **Pending** facts are recorded with `observation: pending` and resolved in `derive()`.
- **Ingest events.** Each run appends `trex.ingest` `start` (after evidence) and `complete` (counts
  and `status`), so the log is self-documenting; a bad-row file still emits the pair, and a transport
  failure leaves the batch open.
- **Source archive.** With `--source-archive` the exact bytes are gzipped to
  `archive/sources/<Y>/<M>/<D>/<HHMMSS>-<name>.gz`; the runner supplies `--source-name` (the staged
  file's original name).

---

## 11. Egress and export (`trex-v2-egress`)

- **Firefly** (§11): `plan` / `apply` / `verify` over the hub's projectable units. Legs are never
  projected; an `ATTESTATION` is never projected; `HELD`/`REVIEW`, `PENDING` and retired facts are
  withheld. The transaction type follows the account kinds; a re-tag preserves splits and
  `group_title` and clears a stale `category_id`; drift and de-projected units are reported, never
  deleted automatically. Projection state is an accelerator, rebuilt from Firefly when missing.
- **Archive**: a byte mirror of the journal plus an evidence copy, verified by hash.
- **Export**: `csv`, `json`, or a portable `sqlite` file.

---

## 12. Configuration (`deploy/config`)

`sequencer.yaml` (bind host/port, journal source/target), `accounts.yaml` (ref, currency,
`balanceSource` = `statement | declared | clearing`, `settlementWindowDays`, `chip_color` — the hub
chip colour, presentation only — and for a clearing account `closingBalance`/`closedAt`), `users.yaml` (non-empty; id, name, active, cadence), `categories.yaml`
(declared categories + ordered rules), `categories.tests.yaml` (golden fixtures, run on load),
`profiles.yaml` (account-scoped `MARK_NOOP` rules for roles), `transfers.yaml` (window, tolerances, and
per-account ordered `transferPatterns` with a `default` list — match, `rail`, `shape`, `clearing`),
`firefly.yaml` (account mapping), `sources.yaml` (the declared 8-char sources the sequencer accepts),
`statements.yaml` (a filename → `sourceType`/account map for the runner's upload picker), and an
optional `refdata.yaml` overriding the declared category names. The rules are the tuned contract; a
rebuild consumes them as-is. The sequencer's environment is `TREX_ENV` (not a file); the archive
location is `--archive` on the sequencer and runner.

---

## 13. CLI

One artifact, one role per subcommand (`trex-v2.jar`): `sequencer`, `hub`, `runner`, `index`,
`ingest`, `egress archive`, `egress firefly`, `snapshot`, `stream export`, `stream ingest`, `reflow`,
`verify`, `export`, `import` (dev). Exit code `64` is `EX_USAGE`. No config is baked in.

---

## 14. Operations and deployment

- **Back up three things**: the journal, the evidence store, and the config (git). The index,
  projection state, review queues and ACK copies are rebuilt, never backed up.
- **The archive** (`--archive`) holds the byte mirror (kept forever), dated **journal snapshots**
  (`journal/trex-<ts>.jsonl.gz`, a consistent copy taken from the live log — never a rotation), and
  the **source archive** (`sources/…`). Snapshots are pruned by an explicit `prune-archive`
  (planned).
- **Staging** (`trex runner`) is a transient inbox; evidence is the durable copy of the bytes.
- **`--apply` is opt-in** (`--allow-apply` / `TREX_RUNNER_ALLOW_APPLY`); the base compose leaves it
  locked, and the UI gates it behind a fresh Plan.
- **Recovery drill**: `stop hub; rm index; trex index --rebuild; trex verify`, run on a schedule.
- **One image** (`trex/trex-v2`), role by command; `deploy/v2/` carries compose and systemd units.
  The sequencer mounts the journal read-write; every other role reads it.

---

## 15. Testing

Three classes per module: contract tests (record shapes, round-trip, schema), invariant tests named
after the §15 guarantees (`deriveIsPure`, `deriveComposeDeriveIsDerive`, `rebuildEqualsIncremental`,
`decisionsWinOverReflow`, …), and regression tests carrying the measured story. The commitments
feature is pinned by `CommitmentsTest` (detection: buckets, regularity, same-day collapse, refunds,
steps, FCY, coverage, determinism), `CommitmentMatchTest` (calendar generation, the window, pins,
settle, attachment by date, holes and manual clearing, off-schedule, variable and irregular,
retirement and the span), the `DeriveTest` commitment cases (curation fold, the three review kinds
and their dismissal aging, transfer-leg commitments, dormancy, arrears), `IndexerTest` (the four
tables through rebuild ≡ incremental), `CommitmentsApiTest` (the two endpoints and the review
kinds), and the round-trip/precheck/stream cases (`LogCodecTest`, `HubDecisionPathTest`,
`SequencerTest`, `StreamTest`). Fixture-dependent tests are `@Tag("fixture")` and skip with a
printed reason, so `mvn verify` is green on a fresh clone. `docs/V2-PARITY.md` holds the v1↔v2
equivalence ledger; `StatementsE2ETest` is the end-to-end run over real statement files.

---

## 16. The runner and its schedule

`trex-v2-runner` is a loopback service the hub proxies. A **job** is an invocation of an existing
subcommand — `ingest`, `egress-firefly`, `journal-snapshot`, `stream` (mode `export | ingest`) — never
new logic: the runner starts the process, streams its output and records the exit code. One serialized
worker, a bounded in-memory run history, and a **staging inbox** for uploads. `POST /jobs/{name}/runs`
is async (`202 {runId}`) or, with `?sync=true&timeoutMs=`, returns the terminal detail or the handle.
The runner mounts the journal read-only so the `stream` export job can read it.

**The schedule** (`schedule.yaml`, empty by default) enqueues jobs through the same queue. It is
intervals only, with a phase — no cron:

```yaml
jobs:
  - job: journal-snapshot
    every: 24h               # 1h,2h,3h,4h,6h,8h,12h,24h,7d
    at: "02:30"              # local time of day — required (the phase)
    on: Sun                  # weekly only; required for 7d, refused otherwise
    zone: Australia/Sydney   # default UTC
```

- `at` pins the phase; `every` is the period. A period that is a whole number of days is a
  **calendar** cadence (the local wall time is preserved across a DST change); a sub-day period is a
  grid anchored at `at`, stepped by `every`.
- DST: a non-existent local time shifts forward; an ambiguous one fires once.
- A due job that is already active is **skipped**, not queued twice.
- **No catch-up**: a slot missed while the runner was down is missed; the manual button covers it.
- Job names are validated at load against the catalogue; a bad entry fails startup.

Runs carry a `trigger` (`manual` | `schedule`), and `GET /jobs` reports each job's `nextRun`.

---

## 17. Deltas from the proposal

Recorded, with the tests that pin them, in `docs/V2-PARITY.md`:

- the transfer matcher's account-scoped shape pre-filter, the mutually-unique tie rule, and the
  receipt plausible-counterpart guard (v1's interim `transferStem` tier is retired);
- roles and the balance check (`noop`, `BALANCE_BREAK`, the Chains view), and clearing accounts with
  a computed opening (v1 had neither);
- per-account `transferPatterns` and the derived rail method (v1 had a flat allowlist, no rail);
- stream promotion (`trex stream export|ingest`, the sequencer `/stream` path) — post-proposal;
- pending settlement and its sign convention (v1 skipped pending entirely);
- the restatement similarity and the payment-noise word list (no v1 counterpart);
- `occ` restated to v1's exact rule;
- a receipt shared on one day by different rows mints content ids (v1 merged them);
- the hub↔sequencer type-only dependency;
- the uniform envelope, namespaced kinds and `v = 1` (the pre-envelope v2 log was `v = 2` on facts
  with no header — a MAJOR line-format change);
- `trex.ingest` events and the derived `ingest_batch`, a third line kind;
- `trex runner`, the Jobs UI, `trex snapshot`, and the archive layout (post-proposal);
- `REVOKE`'s wire field renamed `target` → `revokes` (the envelope owns `target`);
- the commitments feature (§6.11): rules, not vendors; core-fields-only matching with transfer legs
  in scope; the nine curation actions; the five derived tables; the three review kinds with their
  own dismissal aging; the **Expected** mode; and **manual arrears** — a fact attaches to its own
  window, holes are the backlog, and clearing is a decision (V2-MANUAL-ARREARS-PLAN.md) —
  post-proposal, with no v1 counterpart (pinned by `CommitmentsTest`, `CommitmentMatchTest`,
  `DeriveTest`, `IndexerTest` and `CommitmentsApiTest`);
- migration (§16) is retired — there is no migration, and the importer is a dev tool.
