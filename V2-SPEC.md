# trex v2 — specification

**Status: authoritative** (operator decision D-C, 2026-10-09; `V2-REVIEW-FIXES-PLAN.md` §2). This
document is the specification of the v2 system — what the code in `trex-v2-*` does and must keep
doing. Where it and any other document disagree, **this one wins**; where it and the code disagree,
that is a bug in one of them, to be resolved the `AGENTS.md` way (stop, explain, propose the smallest
resolution). §18 says what the other documents are for.

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
- **Dedup** (writer): the observation key is `externalId, accountRef, date, amount, rawDescription,
  receipt, occ, balance, observation` — the whole observation minus `sourceType`, `provenance`,
  `evidenceId`, `parser`, `atMs` and `n`. Each distinct observation is in the log exactly once:

  | Incoming row | Outcome | Log effect |
  |---|---|---|
  | new `externalId` | `Appended` | one fact |
  | known id, identical observation (any source) | `Duplicate` | nothing |
  | known id — in the log **or earlier in the same batch** — new observation (balance, amount, text or pending → posted) | `Flagged` | one fact, once; review is **derived** (§6.6), never written |
  | two identical content-hash rows in one batch | `Appended` ×2 (`occ` 0, 1) | two facts |
  | two identical rows sharing a receipt in one batch | `Appended`, `Duplicate` | one fact |
  | a row failing structure or references | `Rejected` | nothing (`allOrNone` rejects the batch) |
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

**Why this rule (operator decision, 2026-10-09).** Two alternatives were weighed and rejected:
dropping the natural key, or keying on `receipt + amount` (C). Either re-mints all 4,047 receipt-keyed
ids on the fixture (every ING row) and needs a full-history repair. Dropping the key also turns a
bank's amount restatement, or a pending → posted amount change, into a silent second transaction,
because no cross-id review predicate matches unequal amounts. The hybrid keeps every existing id and
text stability, and fixes the 58 merges.

**Known risk, accepted.** A row's id depends on its siblings that day. If a purchase is ingested
alone and the bank later adds a fee or rebate *dated the same day*, re-downloading that day re-mints
the purchase as a content hash, so it lands as a new fact beside the old one. Not seen in the data:
all 58 merged groups arrived complete in one file. If it happens, the reparse repair handles it, and
a writer-side guard (reuse a matching natural-key id for the same `(account, date, receipt)`) is the
known fix. It was considered and not built.

A **transfer id** is minted when a pair forms (§6.3):
- `TRF-<receipt>` when the two legs share a receipt — for a T1 pair only the first pair carrying that
  receipt; a later pair with the same receipt takes the hashed form, so two pairs never share an id;
- otherwise `TRF-` + `sha256("tr|<min>|<max>")[0:16]` over the two legs' **current** `externalId`s,
  order-independent;
- a clearing pair hashes the real leg's id with the clearing account ref in the same way.

Because the hash is over current ids, a `SUPERSEDE` of a leg changes its pair's id (the `Ids.java`
comment that says "chain roots" is wrong on this point). `occ` follows v1: rows with identical `(account, date, amount, raw)`
in one batch take `0,1,2,…` in batch order; natural-key receipt rows are `0` and do not advance; distinct
content rows each take `0`.

---

## 5. Decisions

- **Revocable, never rewritten.** Family inverses (`UNPAIR`, `UNPIN`, `MARK_EXTERNAL`,
  `USER_UNACK`, a later `PIN` or `USER_ACK`) cover the everyday undo; `REVOKE(n)` is the general one
  and the only way back from `SUPERSEDE`, `RETIRE` and `DISMISS`. A later `REVOKE` may revoke a
  `REVOKE`.
- **Effectiveness is derived**, from order and `REVOKE`s, never stored.
- **Notes and reasons.** `NOTE` is a free annotation (`V2-ANNOTATIONS-PLAN.md`): one per target row, accumulating as a
  thread and removed only by `REVOKE`, never edited — a change is a new `NOTE` plus a `REVOKE`. A
  group annotation is a batch of `NOTE`s, one per member id. `DISMISS` and `USER_ACK` carry an
  optional `comment`; a dismissed item's reason is surfaced through `/api/dismissals`, because the
  item itself leaves the queue. Annotations are display only — never identity, never logic.
- **Attached transfers.** `ATTACH_ACCOUNT` names transfer-shaped legs and a clearing account: the
  legs are transfers with an account side, not a contra fact (a pruned counterparty; `V2-ATTACH-ACCOUNT-PLAN.md`). The
  account must be `clearing`; the decision is id-scoped and `REVOKE` releases the legs to the matcher.
- **Commitment curation.** Nine actions curate commitments (§6.5). `DECLARE_COMMITMENT` declares
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

### 6.1 The pipeline, versions and time

Pure function of `(facts, decisions, config, asOf)`, in this order:

replay → effective decisions → supersession / chain resolution → current transactions (`txn_current`)
→ transfer pairing → pending settlement → categorisation → commitments → review items → notes
(`note_current`) → clearing legs → projectable units → state hashes → per-user ACK validity.

Every output list is ordered, so an unchanged input yields byte-identical tables. Versions are
recorded alongside, never inside, a hash: `deriveVersion = "derive/14"`, `hashVersion = "statehash/4"`,
and `configRevision` = SHA-256 over the sorted config files that can move derived state. A change to
derive's semantics — including any constant in §6.9 — bumps `deriveVersion`; a change to §6.8 bumps
`hashVersion`.

**The current fact of an id** is its newest observation by `n`; an id is current when that
observation is `posted` and no effective `SUPERSEDE`/`RETIRE` has closed it. Every decision naming an
id is applied through the supersession map.

**The `asOf` contract.** `asOf` is the only notion of now, and only these outputs may depend on it:
`pending` status; the `UNMATCHED_LEG`, `STALE_PENDING`, `DORMANT_COMMITMENT` and `COMMITMENT_ARREARS`
review items; the commitment faces (arrears, `lapsed`, next due) and `commitment_occurrence`
(generated `asOf − 12 months … asOf + 92 days`, and `due`/`awaiting`/`missed`); and the exclusion of
facts dated after `asOf`. Every other table and every other review kind is identical at any later
`asOf` (`DeriveTest.laterAsOfMovesOnlyTimeRelativeOutputs`). A change that adds an `asOf` dependency
adds it to this list and to that test. Where a judgement needs "how far the data reaches", it uses an
account's **frontier** — its newest current posted date at or before `asOf` — never `asOf` itself.

### 6.2 Roles

Every current fact has a derived role, `transaction` (default) or `noop`: a `noop` row is recorded and
visible but is not a posting — no chain edge, no transfer leg, no unit, no sum. The role comes from an
account-profile rule in `profiles.yaml` (matched on the cleaned description) or a
`MARK_NOOP`/`UNMARK_NOOP` decision, and a decision wins over the profile in either direction; the role
is never stored on the line, so a change is a reflow.

### 6.3 Transfers

A leg is *transfer-shaped* when its own account's first matching `transferPatterns` entry says
`shape: true`, or it shares a receipt with a plausible counterpart (opposite sign, equal magnitude,
same currency, within `windowDays`). A pattern also declares the rail method
(`OSKO`/`PAYID`/`BPAY`/`BANK_TRANSFER`) and may be `shape: false` (rail-only) or name a `clearing:`
account. `PAIR` decisions win. The pool ladder pairs shaped, undecided legs in `(date, n)` order:
**T1** shared receipt, **T2** same day, **T3** within `windowDays`, each requiring equal magnitude,
opposite sign, different accounts, same currency, and a **mutually unique** counterpart (this leg has
one candidate and that candidate no other suitor). More than one candidate opens `AMBIGUOUS_TRANSFER`
and pairs nothing; text is never compared across accounts. A clearing leg pairs directly with its
`clearing:` account — one real leg and an account side, no window, no ambiguity. A matched pair
records the payer leg's rail method; the rail direction is the sign. A T1 pair's id is
`TRF-<receipt>` for the first pair carrying that receipt and the hashed form (§4) for any later one;
two pairs never share an id (a collision is a derive error, never an overwrite).

### 6.4 Pending

A pending fact is never current, never counted, never projected. Its **settling row** is a current
posted fact on the same account, dated on or after it and within the account's
`settlementWindowDays`, of the **same sign**, `|Δamount| ≤ amountTolerance`, same currency, and
`MerchantStem.similar` at `restatementOverlap`. Exactly one → `SETTLED`; more than one → `OPEN` with
`AMBIGUOUS_SETTLEMENT`; none, and the account's frontier more than `settlementWindowDays` past it →
`STALE` with `STALE_PENDING`; otherwise `OPEN`. A `SETTLE` decision names the settling row and wins;
`DISMISS` closes the item. (The settlement knobs live in `transfers.yaml` for now.)

### 6.5 Commitments

A commitment is a named expectation of a recurring money movement (subscription, services, bill,
insurance, fee, tax, income, interest_earned, interest_paid, loan, other). Its stage is a **sibling of
categorisation**: it reads the same current facts and no category output, so their order is
incidental. The nine curation decisions (§5) are folded first.

**Detection** runs over the current facts (transfer legs included, `noop` excluded), grouped on the
frozen `MerchantStem.stem`: enough occurrences, gaps within tolerance of a cadence bucket, enough
regularity (§6.9); same-day repeats collapse into one occurrence; refunds net against the charge they
reverse; a consecutive change past the step threshold is a price step; a series whose every row
carries `Foreign Currency Amount:` compares the FCY price (a mixed series stays on AUD, so mixing bases
cannot invent a step). An interior occurrence at least double (or at most half) its predecessor whose
successor returns to that level is flagged as an `outlier` — flagging only; excluding one is a
decision. Coverage is relative to the frontier of the accounts the series appears on: `active` within
one cadence plus tolerance, `ended` beyond two periods, otherwise `dormant`. A candidate is suppressed
by an effective `IGNORE_RECURRING`, by a declaration that named it `fromCandidate`, or by declaration
rules covering its facts; only a non-ended candidate raises review.

**Occurrences.** A declared regular commitment generates due dates from its anchor with calendar
arithmetic (a clamped month returns to the anchor's day), over `asOf − 12 months … asOf + 92 days`,
each with a `± min(cadence/2, 7)`-day window; where fortnightly windows touch, the boundary day
belongs to the earlier due date. A fact is claimed by a pin first, else by the latest declaration
whose rules, sign and life admit it (`(date, n)` order; claims are unbounded over history); an
excluded `(commitment, fact)` pair is never claimed. A claimed fact **attaches to the occurrence
whose window contains its date**; several facts in one window sum; nothing is allocated across
occurrences and nothing pre-pays; a claimed fact with no window is an `off_schedule` occurrence at its
own date. An `irregular` commitment generates no dates and records each matching fact at its own
date. **A commitment's frontier** is the newest frontier over its rule accounts when every rule names
one, else over the accounts of its claimed facts.

| Status | When | Colour | Counts as a hole |
|---|---|---|---|
| `occurred` | a fact attached to the window (amount = what moved) | green | no |
| `settled` | a `SETTLE_OCCURRENCE` concluded it without a fact (UI: "paid by hand") | diamond | no |
| `due` | the window is still open at `asOf` | muted | no |
| `awaiting` | the window closed by `asOf` but the commitment's frontier has not reached it | grey | no |
| `missed` | the window closed and the frontier has passed it, with nothing attached | red | **yes** |
| `partial` | retired from automatic output; kept so old rows render | — | — |

Holes accumulate as **arrears** (count and expected amount); `lapsed` is true when the most recent
closed-window occurrence is a hole. A retired commitment stops at `endedAt`; a dormant one is a
question for a person (`DORMANT_COMMITMENT`), never auto-ended, and nothing is auto-forgiven.

### 6.6 Review items

`(subject, kind)` is the key. An effective `DISMISS` silences an item until a fact newer than the
`DISMISS` lands for its subject, as the table says; `REVOKE` of the `DISMISS` restores it at once.

| Kind | Raised when | Subject | A `DISMISS` holds until |
|---|---|---|---|
| `POTENTIAL_DUP` | two current facts, same account and date, same sign, equal `MerchantStem.stem`, `|Δamount| ≤ dupTolerance`, neither a matched leg (clustered); or one current id whose posted observations differ in `balance` only | the cluster's smallest id, or the id | a newer fact on that id's chain |
| `RESTATEMENT` | two current facts, same account, date and amount, **different** stems, `MerchantStem.similar` at `restatementOverlap` (clustered) — a different reading of the **description**; or one current id whose posted observations differ in `amount` or `rawDescription` | as above | as above |
| `AMBIGUOUS_TRANSFER` | a shaped leg with more than one candidate at the winning tier | the leg id | a newer fact on the leg's chain |
| `UNMATCHED_LEG` | a shaped leg still `HELD` more than `holdWindowDays` before `asOf` | the leg id | as above |
| `AMBIGUOUS_SETTLEMENT` | a pending fact with more than one settling row (§6.4) | the pending id | as above |
| `STALE_PENDING` | a pending fact with no settling row once the frontier is past its window (§6.4) | the pending id | as above |
| `INEFFECTIVE_DECISION` | a well-formed decision that cannot apply (unknown id, cycle, retired commitment, …) | the decision `n` | for good |
| `BALANCE_BREAK` | a statement account whose transaction chain does not close (§6.7) | the account ref | a newer fact on that account |
| `SUSPECTED_RECURRING` | a non-ended, unsuppressed candidate (§6.5) | the grouping stem | a newer fact in that series |
| `DORMANT_COMMITMENT` | a declared commitment whose coverage is `dormant` | the commitment id | a newer fact the commitment matched |
| `COMMITMENT_ARREARS` | a declared commitment with arrears > 0 | the commitment id | as above |

`POTENTIAL_DUP` and `RESTATEMENT` are **disjoint** (same stem vs different stems). A cluster's
membership can change its smallest id, which re-opens a dismissed item under the new subject; the UI
always sends the subject the item carries. When a cluster already names an id for a kind, a
re-observation item of the same kind on that id is not raised twice.

### 6.7 The balance check and clearing

Reconciliation runs over `transaction` rows only; a `noop` row's edges and amount leave the chain and
are named as exclusions. A statement account's chain closes when its rows' previous balances
(`balance − amount`) and balances leave exactly one opening and one closing; otherwise it is
`broken` (`BALANCE_BREAK`), with the contested values reported as forks. A `DECLARED` (cash) account
reports its gap; a `CLEARING` account holds no facts and reports a computed opening
(`closing + Σ real movements`), so its derived balance lands on the declared closing.

### 6.8 The state hash

`stateHash` of a current row = SHA-256 over
`externalId | role | rail | leg | accountRef | date | amount | currency | category | categoryOrigin |
transferId` (empty string for a null). Ages, stale badges, formatting, `n` and the revision strings
are excluded by construction, so a version bump flags a read row only when something it shows moved.
A `USER_ACK` records the hash it saw (and the three revisions); the row reads as acked while its
current hash equals the recorded one.

### 6.9 Constants

Code constants, not config: changing one is a `deriveVersion` bump.

| Constant | Value | Home |
|---|---|---|
| Detection: minimum occurrences | 3 | `Commitments.MIN_OCCURRENCES` |
| Detection: cadence buckets (days) | 7, 14, 30, 61, 91, 182, 365 | `Commitments.CADENCE_BUCKETS` |
| Detection: gap tolerance | `max(2 days, 20% of the bucket)` | `Commitments.TOLERANCE_*` |
| Detection: minimum regularity | 0.70 | `Commitments.MIN_REGULARITY` |
| Detection: price step | `|Δ| ≥ 5%` or `≥ 50¢` | `Commitments.STEP_MIN_*` |
| Detection: one-off outlier | ×2 (or ×½), successor back within 10% | `Commitments.OUTLIER_*` |
| Coverage: ended after | 2 periods past the frontier | `Commitments.ENDED_PERIODS` |
| Occurrences: history / horizon | 12 months back, 92 days ahead | `CommitmentMatcher.PAST_MONTHS` / `HORIZON_DAYS` |
| Occurrences: window | `± min(cadence/2, 7)` days | `CommitmentMatcher.WINDOW_MAX_DAYS` |

Tunable in `transfers.yaml` (a reflow, not a version bump): `windowDays` (default 4),
`dupTolerance` (0¢), `amountTolerance` (0¢), `holdWindowDays` (30), `restatementOverlap` (0.5) and
the per-account `transferPatterns`.

---

## 7. The read model

SQLite, owned by the hub. Level 1 mirrors the log (`meta`, `fact`, `decision`, `ingest_event`); level
2 is derived and rebuilt wholesale: `supersession`, `chain_resolved`, `txn_current` (carrying the
derived `role`, rail, and a `synthetic` flag — true only for a derived clearing leg, §6.3), `transfer` (carrying the payer `method` and, for a clearing pair, the
`clearing_account`), `pending`, `review_item`, `category_current`, `pin_current`, `note_current`,
`commitment`, `commitment_rule`, `commitment_occurrence`, `commitment_note`,
`commitment_exclusion`, `commitment_fact` (the fact → commitment reverse map, §6.5),
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
| `POST` | `/stream` | append streamed raw JSONL verbatim (`V2-PROPOSAL.md` §14.1): `n`-contiguous, re-validated per line; returns `{appended, headN, stoppedAt, error}`. |
| `GET` | `/head` | the current log head `n` and byte offset. |
| `POST` | `/maintenance/snapshot` | write a dated gzip copy of the journal prefix to the archive; `?sync=false` runs it in the background. |

Every batch carries `source` (exactly 8 chars, declared in `sources.yaml`); an unknown source is a
`400`. The writer validates against the config directory **as it is now**: before each write it
checks a fingerprint of the YAML files and reloads accounts, users, categories and sources on a
change, so an edit (a new category from the Rules editor, a new account) needs no restart; a config
that does not load keeps the last good one and is logged. The sequencer stamps `env` from `TREX_ENV`. Single-writer `FileLock`; the API is
unauthenticated and binds loopback by default.

---

## 9. Hub API and UI (`trex-v2-hub`)

| Method | Path | Purpose |
|---|---|---|
| GET | `/head`, `/api/status` | log head and index lag; table counts, open review by kind, revisions, `through` and `stale` (§9.1) |
| GET | `/api/refdata` | accounts, users, declared categories, revisions |
| GET | `/api/ledger` | the blotter rows; filters include `role`, `leg`, account, category, dates |
| GET | `/api/review` | the derived queue (§6.6); filters `kind`, `account` |
| GET | `/api/dismissals` | effective `DISMISS` reasons (the item itself has left the queue) |
| GET | `/api/notes?externalId=` | a row's `NOTE` thread |
| GET | `/api/commitments` | the registry: faces, a candidate's stem, rules, current price, next due, arrears, notes |
| GET | `/api/commitments/activity?id=` | a candidate's series, or a declared commitment's claimed facts over all history |
| GET | `/api/expected?window=today\|week\|month` | the window's occurrences, the arrears backlog, committed totals, and the month's **headroom** (§9.2) |
| GET | `/api/transfers`, `/api/units` | matched transfers; projectable units |
| GET | `/api/reconcile`, `/api/chains`, `/api/opening` | the balance check (§6.7): results with `noop` exclusions, forks with a per-side preview, openings |
| GET | `/api/workbook`, `/api/eyeball`, `/api/accounts`, `/api/ingests` | the workbook; the eyeball checks at an explicit `asOf`; per-account coverage; the ingest history with each account's frontier — `?sinceN=<n>` keeps only batches completed after `n` |
| GET/POST | `/api/projection` | Firefly projection state (an accelerator; its home is Firefly) |
| GET/POST | `/api/cursors` | feed cursors (index-only until the first feed — `V2-REVIEW-FIXES-PLAN.md` §13) |
| GET/POST | `/api/acks` | per-user `USER_ACK` rows and their validity |
| POST | `/api/decisions` | precheck against the index (`422` naming the failure, `409` stale view, `503`/`502` writer down) and forward to the sequencer |
| POST | `/api/reflow/preview`, `/api/reflow/preview/transfers` | blast radius of a category or transfer-pattern edit, before it is saved |
| GET/PUT | `/api/config/categories`, `/api/config/transfers` | the rule files the Rules mode edits |
| GET | `/api/config/drift` | shipped vs base vs current for every seeded config file (§12): `[{file, state}]`, `state` ∈ `same`, `repo-newer`, `edited-here`, `both-changed`, `unknown` |
| * | `/api/jobs…` | proxied to the runner (§16) |
| GET | `/api/events` | SSE: a snapshot, then deltas |

### 9.1 The status strip

Open review count (a link to Review), or **✓ all clear — through <date>** once nothing is open; the
date is the oldest statement frontier of the `budget` accounts, so an all-clear never claims more
than the statements say.

**The statement-age nudge** (`QOL_Improvements.md` §2). `/api/status` also returns `stale`: every
account whose frontier — the newest non-synthetic statement row at or before today — is older than
its effective fetch cadence (`fetchEveryDays`; §12), oldest first, as
`{account, frontier, days, fetchEveryDays}` with `days = today − frontier` and `days > fetchEveryDays`
strictly. An account with no frontier has nothing to fetch yet and is absent; a null or `0` cadence
never nudges. The strip shows `⧗ N statements to fetch` after the all-clear or the review count —
muted, amber once any account is more than twice its cadence — with a tooltip naming each account,
its age and its frontier; a click opens Jobs at the fetch-frontier table.

### 9.2 Headroom ("left this month")

Always the calendar month of `asOf`, over the `budget` accounts:
`left = income in + income due + moved in − commitments paid − commitments due − other spend − moved out`.
Commitments count through their occurrences (`occurred`/`settled` as paid, `due`/`awaiting` at the
current price as due); every other row counts as spend or income, except: a transfer between two
budget accounts is skipped; a transfer to or from one of your own accounts outside the budget is
*moved*; a commitment that only ever claims internal transfers, or lands only outside the budget, is
skipped. `missed` commitments and unpaired (`HELD`) legs are reported apart, never folded in.

### 9.3 The UI modes

Eight modes; each one's detail lives in the plan that built it.

| Mode | What it is for | Detail |
|---|---|---|
| **Blotter** | every current row, SQL-backed filters, inline decisions (pin, pair, mark external, noop, note, assign to a commitment) | `V2-ANNOTATIONS-PLAN.md`, `V2-COMMITMENTS-PLAN.md` |
| **Review** | the derived queue, one decision away from clear; clusters expand; `Dismiss` with an optional reason | §6.6 |
| **Expected** | headroom; the window's occurrences; **Catch up** (arrears, oldest first); the commitment registry with its Actions menu; rule lint | `V2-EXPECTED-UX-PLAN.md`, `V2-MANUAL-ARREARS-PLAN.md` |
| **Eyeball** | open items, the anomaly checks at an explicit `asOf`, rows bucketed by period with `Ack`/`Unack` | `V2-PROPOSAL.md` §10.3 |
| **Rules** | the category editor and the transfer-pattern editor, each with blast-radius preview, lint, fixtures | `V2-PROPOSAL.md` §10 |
| **Accounts** | per-account opening, range, newest ingest, a facts-derived weekly strip (a quiet week is a hole to check) | `V2-PROPOSAL.md` §10.5 |
| **Chains** | the balance check per account: forks with both sides, computed clearing openings, a `noop` preview | §6.7 |
| **Jobs** | the runner: staging inbox (**Ingest inbox**, `failed`), egress Plan/Verify/Apply, snapshot, the ingest history and fetch frontier, the ops strip, the config-drift strip (§12) | §16, `V2-INGEST-FRONTIER-PLAN.md` |

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

- **Firefly** (`V2-PROPOSAL.md` §11): `plan` / `apply` / `verify` over the hub's projectable units. Legs are never
  projected; an `ATTESTATION` is never projected; `HELD`/`REVIEW`, `PENDING` and retired facts are
  withheld. The transaction type follows the account kinds; a re-tag preserves splits and
  `group_title` and clears a stale `category_id`; drift and de-projected units are reported, never
  deleted automatically. Projection state is an accelerator, rebuilt from Firefly when missing.
- **Archive**: a byte mirror of the journal plus an evidence copy, verified by hash.
- **Export**: `csv`, `json`, or a portable `sqlite` file.

---

## 12. Configuration (`deploy/config`)

`sequencer.yaml` (bind host/port, journal source/target), `accounts.yaml` (ref, currency,
`balanceSource` = `statement | declared | clearing`, `settlementWindowDays`, `fetchEveryDays`
(the statement-age nudge, §9.1 — presentation only: default 31 for a statement account, none for
declared/clearing, `0` off), `chip_color` — the hub
chip colour, presentation only — `budget` (default true, never for a clearing account: whether the
account is in Expected's "left this month"; presentation only), and for a clearing account `closingBalance`/`closedAt`), `users.yaml` (non-empty; id, name, active, cadence), `categories.yaml`
(declared categories + ordered rules), `categories.tests.yaml` (golden fixtures, run on load),
`profiles.yaml` (account-scoped `MARK_NOOP` rules for roles), `transfers.yaml` (window, tolerances, and
per-account ordered `transferPatterns` with a `default` list — match, `rail`, `shape`, `clearing`),
`firefly.yaml` (account mapping), `sources.yaml` (the declared 8-char sources the sequencer accepts),
`statements.yaml` (a filename → `sourceType`/account map for the runner's upload picker), and an
optional `refdata.yaml` overriding the declared category names. The rules are the tuned contract; a
rebuild consumes them as-is. The sequencer's environment is `TREX_ENV` (not a file); the archive
location is `--archive` on the sequencer and runner.

**Config drift** (`QOL_Improvements.md` §3). The compose `init` service never overwrites a live
config file, but on every `up` it rewrites `/etc/trex/.shipped/<file>` with the image's copy, and
when it installs a missing live file it also records that copy as `/etc/trex/.base/<file>`. The hub
compares the three per file — current vs shipped, current vs base, shipped vs base — and serves
`GET /api/config/drift`; the Jobs strip names every file that is not `same`. A `repo-newer` file
(`C == B`, `S != B`) is safe for `deploy/v2/dev.sh sync-config`, which backs the live file up under
`/etc/trex/.backup/` and installs the shipped one; `--adopt FILE` records the live file as the base
for an `unknown` first run. The same container script is documented for the host in
`docs/DEPLOYMENTS.md`. A sync needs no restart.

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
after the `V2-PROPOSAL.md` §15 guarantees (`deriveIsPure`, `deriveComposeDeriveIsDerive`, `rebuildEqualsIncremental`,
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
subcommand — `ingest`, `ingest-inbox`, `egress-firefly`, `journal-snapshot`, `stream` (mode
`export | ingest`) — never new logic: the runner starts the process, streams its output and records the exit code. One serialized
worker, a bounded in-memory run history, and a **staging inbox** for uploads. `ingest-inbox` is the
one allowed composition: the `ingest` subcommand with its items resolved from `statements.yaml`
over the settled inbox files, each filed by exit code — `0` to `done/`, `1`/`3` to `failed/`,
otherwise left for the next sweep; an empty or all-unnamed inbox is *nothing to do*, which a
scheduled run skips quietly. `POST /jobs/{name}/runs`
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
- the commitments feature (§6.5): rules, not vendors; core-fields-only matching with transfer legs
  in scope; the nine curation actions; the five derived tables; the three review kinds with their
  own dismissal aging; the **Expected** mode; and **manual arrears** — a fact attaches to its own
  window, holes are the backlog, and clearing is a decision (V2-MANUAL-ARREARS-PLAN.md) —
  post-proposal, with no v1 counterpart (pinned by `CommitmentsTest`, `CommitmentMatchTest`,
  `DeriveTest`, `IndexerTest` and `CommitmentsApiTest`);
- migration (`V2-PROPOSAL.md` §16) is retired — there is no migration, and the importer is a dev tool.

---

## 18. The documents

| Document | Role |
|---|---|
| `V2-SPEC.md` (this) | **the specification.** A change to behaviour changes this file in the same PR. |
| `AGENTS.md` | the invariants that never move, and how work is done |
| `README.md`, `CHANGELOG.md` | the entry point; what changed, for a reader who was not there |
| `QOL_Improvements.md`, `V2-REVIEW-FIXES-PLAN.md` | the **open** plans (root): written before a stage, amended as it lands |
| `V2-PROPOSAL.md` | **frozen** (2026-10-09): the history of intent and the rationale. Read it for *why*, never for *what* — where it differs from this file, this file wins. It stays at the root because code comments cite it. |
| `docs/plans/V2-*-PLAN.md` | **built** plans, each with a banner naming the PRs that built it: the record of why and of what was measured. Code comments cite them by file name. |
| `docs/reviews/` | design reviews, every finding actioned |
| `docs/proposals/` | parked ideas, nothing decided (the sequencer kernel) |
| `docs/archive/` | superseded snapshots (the 2026-10-08 proposal, the 2026-09-30 lifecycle diagram) |
| `docs/DEPLOYMENTS.md`, `docs/RELEASE.md`, `docs/V2-PARITY.md`, `deploy/v2/README.md` | operations: where it runs, how a release is cut, the v1 ↔ v2 ledger, the compose stack |

When a plan's last stage lands, its PR moves it to `docs/plans/` with a banner.
