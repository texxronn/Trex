# V2-COMMITMENTS-PLAN.md

> **Personal project, single operator, private.** `V2-PROPOSAL.md` is the specification; this file
> is the build order for one change to it. Where this file and the proposal disagree, the proposal
> wins; the proposal is amended in the same change (§8). The session data analysis this design is
> measured on lives in `run/commitments-context.local.md` §5.

**Status:** **plan only — no code written.** Simplified model (operator, 2026-10-08): commitments
are a **first-class service of the same significance as categorization**; there is **no vendor
entity** (a commitment is a name plus a set of match rules); the grammar is **seven actions**; the
derived output is materialised like every other derived table. All open questions are resolved
(§10); **approved to build** (operator, 2026-10-08) — the implementation plan is §6.

**Authority:** `V2-PROPOSAL.md` §6.2, §6.7, §7, §9.8, §9.9, §10, §12.4, §15; `AGENTS.md`.

**Adds to the decision set:** `DECLARE_COMMITMENT`, `RETIRE_COMMITMENT`, `IGNORE_RECURRING`,
`PIN_COMMITMENT`, `UNPIN_COMMITMENT`, `NOTE_COMMITMENT`, `SETTLE_OCCURRENCE`.

---

## 1. The problem, in one paragraph

The ledger answers "what happened"; it cannot answer "what is coming". A subscription is only a
category some rows happen to match; nothing records that a series exists, so every discovery —
this is monthly, it costs $9.99, it stopped, it moved provider — is thrown away on the next
reflow. Commitments are the answer: a **named expectation of a recurring money movement**, with
detected candidates, declared rules, a derived occurrence per period, a price timeline, and an
**Expected** view of what is due today / this week / this month, green when it lands and red when
it does not. Like categorization, it is part of the system's model: curation is journaled, the
derivation is pure, the tables are disposable, and the UI is a first-class mode.

## 2. The model

### 2.1 The unit

A commitment is derived state; the journal holds its curation decisions (§2.6).

| Face | Values |
|---|---|
| origin | `detected` (a predictable cadence found in the facts) · `declared` (a person said so) |
| direction | `out` (−) · `in` (+ income) |
| cadence | `weekly · fortnightly · monthly · bimonthly · quarterly · semiannual · annual · irregular` |
| amount | `fixed` · `variable` (usage) · `range` (declared floor/ceiling) |
| kind | `subscription · services · bill · insurance · fee · tax · income · interest_earned · interest_paid · loan · other` |
| rules | an ordered set of match rules (§2.2) |
| lifecycle | `candidate · active · dormant · ended`; `lapsed` is an overlay, not a lifecycle |
| id | `commitmentId`, a slug frozen in the declaring decision; never rewritten |

Lifecycle, precisely (as resolved, 2026-10-08):

- **candidate** — detected and not yet declared or ignored. Active candidates ship to Review;
  ended ones are still listed in the registry as discoveries, so nothing is lost.
- **active** — declared (or confirmed) and expecting occurrences.
- **dormant** — tracked, silent for more than one cadence + tolerance, while the account's posted
  frontier keeps advancing (so it is silence, not missing data). An `irregular` commitment has no
  cadence and is never dormant.
- **ended** — a `RETIRE_COMMITMENT`; for a candidate only, the detector's coverage test (silent
  beyond the frontier by more than two periods: the fixture shows 11 of 25 expense series ended,
  e.g. `YOUTUBEPREMIUM`, `MICROSOFT`, `APPLE.COM/BILL`).
- **lapsed** — an overlay on an active/dormant commitment whose current expected occurrence has
  passed its window unmatched (the red state in the Expected view); older misses accumulate as
  **arrears** (§2.9).

### 2.2 Rules, not vendors (operator, 2026-10-08)

There is **no vendor entity**. A commitment is exactly: a name, its faces, and a set of **match
rules**. A rule is `{match: <regex>, account?: <ref>}` evaluated against `clean(rawDescription)`
(the same `Pattern` semantics and regex lint as `categories.yaml` / `transfers.yaml`). Direction
is the commitment's sign: only facts of that sign can match.

**Core fact fields only** (operator, 2026-10-08). A rule, and the domain it runs over, may use
`rawDescription`, `account`, `amount`/sign and `date` — fact fields — and never derived ones
(`leg`, `pairing`, `category`, `role`, `synthetic`). Derived state is ephemeral: a later reflow can
change a pairing or a category, and a commitment must not move with it. That is also what lets an
internal movement be a commitment — a home-loan repayment is a matched transfer leg and counts
like any external charge. The one conclusion that removes a fact from the domain is `noop` (a
person or a profile has said the row is not a movement — a conclusion, not a derivation).

**Categorization and commitments are siblings, not a pipeline** (operator, 2026-10-08): neither
reads the other's output, the same complex rule may be duplicated verbatim in both, and their
execution order is incidental.

- **Descriptor churn is just another rule.** `ANTHROPIC`, `ANTHROPIC* CLAUDE SUB`,
  `CLAUDE.AI SUBSCRIPTION` become three rules on one commitment. The confirm dialog prefills them
  from the candidate's observed descriptors, so this is one click.
- **A provider move is a workflow.** AGL and Origin are different descriptions, hence different
  candidates, hence two commitments: `RETIRE_COMMITMENT` ends AGL, `DECLARE_COMMITMENT` starts
  Origin. Fully independent — **no link** (operator, 2026-10-08): each history stands on its own.
- **Rules may overlap, and latest wins.** A fact is assigned to the most recently declared
  effective commitment whose rules match it — the same "latest effective wins" idiom as `PIN`.
  Overlaps are surfaced as a lint in the registry (and in Rules mode), never as a silent steal; a
  re-declare simply updates its own rule set.
- **A person always outranks the rules.** `PIN_COMMITMENT` associates a transaction with a
  commitment — the same gesture as the category `PIN` — and `UNPIN_COMMITMENT` releases it back to
  the rules. Decisions win, the latest effective pin naming a fact wins, and the fact id resolves
  through the supersession map, so an unusual account or a descriptor the rules miss is one click
  to place and never a fabricated rule. Pins accumulating on one commitment are a rule asking to be
  written (lint, like category pins).
- **Rules are hygiene-checked.** The hub precheck compiles each rule and runs the existing
  catastrophic-backtracking lint (`CatastrophicRegex`); a bad rule is a `422` naming the rule.
  A bad rule that still reaches the log through another writer (a stream, a hand-edited line)
  makes its declaration ineffective — an `INEFFECTIVE_DECISION` naming the rule — rather than
  failing the derivation; an earlier or later good declaration of the same id stands.

### 2.3 Detection (pure, deterministic)

Input: the **current posted facts** — every account, every description, whatever the derived leg
or pairing (a matched transfer leg is how a home-loan repayment is seen). Pending is excluded by
its core `observation`; `noop` is the one conclusion that removes a row; synthetic clearing rows
are not facts at all. Rules see core fact fields only (§2.2).

1. **Grouping key per fact** — the existing frozen `MerchantStem.stem` (the head before the
   per-transaction tail: whitespace-collapsed, upper-cased), no new normalisation layer and no
   vendors config (operator, 2026-10-08). Descriptor churn the stem does not merge (`ANTHROPIC` vs
   `CLAUDE.AI SUBSCRIPTION`) arrives as its own candidate and is merged at confirm by adding rules;
   the leftover candidate is then suppressed by rule coverage. The key names candidates for Review
   and `IGNORE_RECURRING`; it is **not** a commitment field.
2. **Group** by the stem (across accounts); facts already assigned to a commitment (by rule
   or by an effective `PIN_COMMITMENT`) are not candidates — detection proposes only what is
   unexplained.
3. **Same-day repeats** (gap 0) are excluded from the gap series; **refunds** are netted against
   the charge they reverse (the fixture: `AMZNPRIMEAU MEMBERSHIP - VISA REFUND −$9.99`;
   same-day multiples like `STAN $22 + $20` are not price changes).
4. **Cadence.** Gaps in days; each classifies to the nearest bucket of `{7, 14, 30, 61, 91, 182,
   365}` within tolerance (provisional `max(2 days, 20% of the bucket)`); regularity =
   in-tolerance gaps ÷ gaps; a series needs ≥ 3 occurrences and regularity ≥ 0.7. (Measured:
   234 merchant groups with ≥ 3 occurrences → 32 pass; histogram spikes exactly at these buckets.)
5. **Price steps.** Consecutive amount changes with |Δ| ≥ 5% or ≥ 50¢ are steps (never misses,
   never same-day multiples). Where the description carries `Foreign Currency Amount:`, compare
   the FCY amount; the AUD charge varies with FX (the fixture: `AWS`, `OPENAI`).
6. **Usage.** Residual variation beyond the step tolerance flags `variable`; the trailing range is
   recorded.
7. **Coverage.** Active if the last occurrence is within one cadence + tolerance of the account's
   posted frontier; candidate `ended` if the frontier is past it by more than two periods;
   otherwise dormant. Status is relative to the account's own coverage, never a wall clock.
8. **Suppression.** A candidate is suppressed when an effective `IGNORE_RECURRING` names its key,
   or an effective declaration names it as `fromCandidate`, or the declaration rules cover its
   facts. Output: candidate rows with key, cadence, anchor, span, occurrence count, last/current/
   previous amount, steps, regularity, status, content hash.

Every threshold is a named constant with the measurement in its javadoc (the house rule: the
measured facts live in the code that depends on them). Calibration against the fixture is
Stage 1's acceptance.

### 2.4 Cost model

Per commitment: a **price timeline of steps only**; current/previous amount, changePct and date;
cost-to-date (Σ matched amounts); annualised (52 / 26 / 12 / 6 / 4 / 2 / 1 × current; variable =
trailing median). Alerts are derived badges, not new review noise: increase, usage out of trailing
range, drift (missed twice). An `irregular` commitment has no cadence: it keeps its cost timeline
and its annualised figure is the trailing 12-month total; no drift alerts. A change can be
annotated with a `NOTE` against the matched fact, or on the commitment's own thread (§2.6).

### 2.5 Occurrences and matching

> **Superseded in part 2026-10-08:** the admission and status rules here stand, but the
> "nearest unassigned fact" allocation and `partial` shortfall are replaced by attachment to the
> fact's own window (`V2-MANUAL-ARREARS-PLAN.md` §3).

- Generated from cadence + anchor with `java.time` **calendar arithmetic** — a monthly bill on the
  30th clamps in February; never "add 30 days".
- Materialise the recent past (12 months) and a forward horizon (`asOf + 92 days`); all disposable
  (§10.2). An `irregular` commitment generates no dates — recurrence is tracked when it happens,
  never predicted: each matching fact becomes an occurrence at its own date, with no window, no
  `missed` and no arrears, and it is never dormant.
- **Precedence: pins first, then rules.** An effective `PIN_COMMITMENT` places its fact with the
  named commitment before rule matching runs; `UNPIN_COMMITMENT` releases it. A pinned fact
  allocates exactly like a rule match (§2.9); when there is no open occurrence it becomes an
  occurrence at its own date flagged `off_schedule` — "charged twice this month" is a true
  statement, never a silent match.
- **A pin never re-anchors.** A schedule that genuinely changed is a **new commitment**: retire
  the old, declare the new (operator, 2026-10-08). A pin marks what happened; it does not move the
  calendar.
- **Matching:** a due occurrence takes the nearest unassigned current fact that (a) matches one of
  the commitment's rules or is pinned to this commitment, (b) has the commitment's sign, (c) falls
  inside the date window (provisional: ± half the cadence, capped at ±7 days), and (d) has an
  amount inside the commitment's tolerance/range. Occurrences are processed in date order, so the
  assignment is deterministic; a fact belongs to at most one occurrence. An `irregular` commitment
  has no due occurrence: every matching fact becomes an occurrence at its own date.
- Status: **occurred ✓** (green, carries the matched fact), **settled** (a person concluded it was
  paid without a fact, §2.9), **due** (window open), **partial** (a fact covered part of it; the
  remainder is arrears), **missed ✗** (window closed, no match; `lapsed` while it is the current
  one). Catch-up allocation across several occurrences is §2.9; `SKIP_OCCURRENCE` is parked.

### 2.6 Curation as decisions

The only journal touches. Append-only, attributed, `REVOKE`-able, latest effective wins; a
decision naming an unknown commitment is ineffective and surfaces as `INEFFECTIVE_DECISION`,
never dropped.

| Action | Payload | Meaning |
|---|---|---|
| `DECLARE_COMMITMENT` | `commitmentId`, `name`, `direction`, `cadence`, `amountKind`, `commitmentKind`, `matches[]` (`{match, account?}`), `amount?`, `anchor?`, `fromCandidate?`, `comment?` | Declare a commitment; confirm a detected candidate (`fromCandidate` = its key; the UI prefills `matches` from its descriptors) |
| `RETIRE_COMMITMENT` | `commitmentId`, `endedAt`, `reason` | End it (cancelled, past, provider move) |
| `IGNORE_RECURRING` | `candidate`, `reason` | Silence a detected candidate for good (revocable) |
| `PIN_COMMITMENT` | `commitmentId`, `externalIds`, `comment?` | Those facts are occurrences of that commitment, whatever its rules say — the category `PIN` gesture. |
| `UNPIN_COMMITMENT` | `externalIds`, `comment?` | Release those facts back to rule matching. |
| `NOTE_COMMITMENT` | `commitmentId`, `text` | Free annotation on a commitment; accumulates as a thread, removed only by `REVOKE`, never edited — the `NOTE` gesture, targeted at a commitment. |
| `SETTLE_OCCURRENCE` | `commitmentId`, `dueDates[]`, `comment?` | Those occurrences are paid (or received): a conclusion, no fact — the off-journal settle (§2.9). |

Notes:

- **The wire calls the kind `commitmentKind`**: the log envelope already owns `kind`, and a body
  field of the same name would overwrite it (resolved in Stage 3).
- **Re-declare is the edit.** A later `DECLARE_COMMITMENT` with the same id replaces the curated
  fields and the rule set (latest effective wins); `REVOKE`-ing the later declaration restores the
  earlier. A retire followed by a declare of the same id revives it, family-inverse style.
- `IGNORE_RECURRING` names the candidate key, so newer facts do **not** reopen it — that is what
  `REVOKE` is for. This is deliberately different from `DISMISS` (which reopens when newer facts
  land).
- **A pin is per fact, latest wins.** It overrides the rules in both directions and can name a
  fact the rules never matched; a pin naming a retired commitment is ineffective and visible, and
  a `REVOKE` restores the rule's answer.
- **Cadence and anchor are not edited in place** (operator, 2026-10-08): a genuine schedule change
  is retire + declare, a new commitment. A re-declare edits name, kind, rules and amount.
- **Notes accumulate.** `NOTE_COMMITMENT` is a thread (like `NOTE`), never logic, never identity;
  notes on a retired commitment are allowed and remain part of its history.
- **Settlement is a conclusion, not an observation.** `SETTLE_OCCURRENCE` marks occurrences
  `settled` (attributed, revocable); when the money should appear on an account, the honest path
  is an **authored fact** (manual cash, §12.4) and ordinary matching.
- Rules are a commitment's default; a person's `PIN_COMMITMENT` is the override.

### 2.7 Derived tables (disposable, materialised)

```sql
commitment(
  commitment_id TEXT PRIMARY KEY, name TEXT, origin TEXT, direction TEXT, cadence TEXT,
  amount_kind TEXT, kind TEXT, status TEXT,
  first_date TEXT, last_date TEXT, anchor_date TEXT,
  current_amount INTEGER, previous_amount INTEGER, change_pct REAL, change_date TEXT,
  occurrence_count INTEGER, regularity REAL, variable INTEGER,
  arrears_count INTEGER, arrears_amount INTEGER,
  declared_n INTEGER, retired_n INTEGER, ended_at TEXT, state_hash TEXT)

commitment_rule(
  commitment_id TEXT, match TEXT, account_ref TEXT, decision_n INTEGER,
  PRIMARY KEY (commitment_id, match, account_ref))

commitment_occurrence(
  commitment_id TEXT, due_date TEXT, status TEXT, window_start TEXT, window_end TEXT,
  matched_external_id TEXT, matched_date TEXT, matched_by TEXT, off_schedule INTEGER,
  settle_n INTEGER, amount INTEGER, state_hash TEXT,
  PRIMARY KEY (commitment_id, due_date))

commitment_note(
  decision_n INTEGER PRIMARY KEY, commitment_id TEXT, text TEXT, user_id TEXT, at TEXT)
```

`commitment` carries both candidates (`origin=detected`, `status=candidate`, `declared_n` null,
id `cand|<hex>`) and declared rows; `commitment_rule` is the effective rule set (a projection of
the latest effective declaration); occurrences exist for tracked commitments (a candidate's
observed facts are the series itself). `matched_by` records how the fact was placed (`rule` or
`pin`) and `off_schedule` marks a pinned occurrence outside the calendar; `amount` is the
allocated amount (the fact's share, or the expected amount on a `settled` row) and `settle_n`
names the decision that settled it. `commitment_note` is the
effective `NOTE_COMMITMENT` thread (index on `commitment_id`), mirroring `note_current`. Fact ids
resolve through the supersession map where matched; commitment ids are decision-local and never
touch the fact chain.

### 2.8 Review and the Expected view

- **New review kind `SUSPECTED_RECURRING`**, one item per active candidate, subject = the
  grouping stem (`MerchantStem.stem`). Rendered with cadence, span, occurrence count and amounts, and the steps.
  Actions: **Confirm** (opens the declaration dialog prefilled from the candidate; posts
  `DECLARE_COMMITMENT` with `fromCandidate`, plus `PIN_COMMITMENT` for any observed fact the rules
  do not cover, in one batch) and **Ignore** (`IGNORE_RECURRING`). No generic
  `Dismiss` in the UI (ignore is the semantic — it does not reopen on the next fact).
- **New review kind `DORMANT_COMMITMENT`**, one item per tracked commitment the coverage test
  calls dormant (an `irregular` commitment is never dormant), subject = the commitment id
  (operator, 2026-10-08). Actions: **Mark ended**
  (posts `RETIRE_COMMITMENT`) and **Keep tracking** (a `DISMISS`; it stays quiet until a newer
  matched fact lands, so the next dormancy after a payment re-raises it). Nothing is ever
  auto-ended.
- **New review kind `COMMITMENT_ARREARS`**, one item per commitment with arrears (§2.9), subject =
  the commitment id, detail = count and total. Actions: **Settle** (opens the settle dialog) or
  **Snooze** (a `DISMISS`; reopens when a newer fact lands). Nothing is auto-forgiven.
- **New mode `Expected`** (first-class, parity with Review): windows **today / this week / this
  month**; one row per occurrence (date, commitment, direction, amount, green tick / red cross);
  a committed-this-month total, split out / in (in green); a **Catch up** panel — every
  occurrence in arrears across commitments, oldest first, with a running total and per-row
  **Settle** / **Assign a payment**; a **registry** of commitments (all faces, current price,
  last/next, status, arrears, steps, notes thread) with retire and re-declare actions and a lint
  panel (never-fired rules, overlaps, catastrophic regexes). One tab, two sections. The Expected
  tab is part of the regular review routine (operator, 2026-10-08) — it is where a dormant
  commitment, an arrear and a red occurrence get their decision.
- On the **Blotter/Eyeball**, a matched row shows the commitment chip (a derived join, display
  only — §10.13); the row action **Assign to commitment** posts `PIN_COMMITMENT` (Unassign posts
  `UNPIN_COMMITMENT`), the commitment analogue of inline categorisation.
- The existing Eyeball anomaly `RECURRING_MISSING` (`Eyeball.java:47,205`) stays as built; once
  commitments land it can be re-sourced from missed occurrences, as a follow-up (§10.12).

### 2.9 Arrears and catch-up (the point of the feature)

> **Superseded 2026-10-08:** `V2-MANUAL-ARREARS-PLAN.md`. A fact attaches to the occurrence its
> window contains and arrears are the holes; there is no automatic oldest-first allocation,
> `partial` shortfall or pre-payment. This section records the original build order only.

A commitment that stops being paid does not disappear; the misses accumulate and stare back.
Arrears are derived state, never stored on a fact, and clearing them is a first-class flow.

- **Arrears.** An occurrence is *in arrears* while it is `missed`, or `partial` (a fact covered
  less than its expected amount); an `irregular` commitment has no due dates and therefore no
  arrears. The current one renders lapsed; older ones accumulate as a
  backlog. Per commitment the registry shows `arrears_count` and `arrears_amount` (the expected
  total still short); across commitments the **Catch up** panel lists every occurrence in arrears,
  oldest first, with a running total.
- **A catch-up payment clears the backlog from the front.** A matching fact (by rule or pin, right
  sign, right account scope) is allocated to the commitment's **open** occurrences (`due`,
  `missed` or `partial`) **oldest first**: each takes up to its expected amount; an occurrence
  fully covered (within tolerance) becomes `occurred` and carries the shared fact id and its
  allocated amount; one covered only partly becomes `partial`, with the remainder still in
  arrears. A surplus beyond all open occurrences pre-pays future occurrences already materialised;
  anything left after that becomes an `off_schedule` occurrence — nothing is swallowed. The
  occurrence table holds one row per `(commitment, dueDate)`, so a leftover amount on a date that
  already has a row merges into it — the day's summed amount, never a second row and never a
  dropped amount (detection's same-day collapse, §2.3.3, now on the outcome side). The
  allocation is not bounded by the normal ± date window (that window only finds the occurrence an
  on-time fact belongs to) and never reaches past the first occurrence; a fact older than the
  materialised span is not an occurrence of a regular commitment at all (an `irregular`
  commitment has no span and tracks every matching fact). Applies to `fixed` and
  `range` amounts; a `variable` commitment keeps one fact per occurrence (its range is too wide to
  infer multiples). Facts are processed in `(date, n)` order, so the answer is deterministic.
- **Off-journal paid is a decision.** `SETTLE_OCCURRENCE` marks occurrences `settled` — a person
  concluded the expectation was met (cash, an account not in the journal) with no fact to show.
  `settled` renders differently from `occurred` (which carries evidence), is attributed, and
  `REVOKE` returns the occurrences to arrears. When the money *should* appear on an account, the
  honest path is an **authored fact** (manual cash, §12.4) — an observation, matched like any
  other; `SETTLE_OCCURRENCE` is for when the bookkeeping is all you want. (`SKIP_OCCURRENCE` —
  "no charge this period" — is parked; retiring a stopped commitment covers the real cases.)
- **Review.** A commitment in arrears raises `COMMITMENT_ARREARS` (subject = the commitment id,
  detail = count and total). Actions: **Settle** or **Snooze** (`DISMISS`; it reopens when a newer
  fact lands, so the next payment resets it). Nothing is auto-retired and nothing is
  auto-forgiven: the backlog is visible until a fact or a person clears it.

## 3. Touch points (as built)

### 3.1 `trex-v2-core`

- New under `trex/v2/core/derive/`: `Commitment`, `CommitmentRule`, `CommitmentOccurrence`,
  `CommitmentNote` records; enums `Cadence`, `AmountKind`, `CommitmentKind`, `CommitmentOrigin`,
  `CommitmentStatus`, `OccurrenceStatus`; a pure `Commitments` (detection, matching, cost —
  sitting beside `Workbook` rather than inside `Derive.Ctx`). Grouping uses the existing
  `MerchantStem.stem`; no new key class.
- `Action.java`: 7 constants; `Decision.java`: 7 records + the `permits` list.
- `Derive.java`: collect the effective curation decisions after the supersession map (commitment
  ids are decision-local; fact ids resolve as usual, so a pin follows a supersession); fold
  `PIN_COMMITMENT`/`UNPIN_COMMITMENT` into per-fact assignment and `SETTLE_OCCURRENCE` into
  occurrence status (a decision wins over matching); call `Commitments` as a **sibling**
  of the category stage over the current facts (§2.3 — it reads no category output and category
  reads none of it, so their order is incidental); feed
  `SUSPECTED_RECURRING`, `DORMANT_COMMITMENT` and `COMMITMENT_ARREARS` into `reviewItems()`;
  project the `NOTE_COMMITMENT` thread. Add the series-stem and commitment-id subject cases to
  the `DISMISS` staleness rule (newest fact of the series / of the commitment).
- `Derivation.java`: `commitments`, `commitmentRules`, `commitmentOccurrences`, `commitmentNotes`
  lists.
- `ReviewItem.java`: `SUSPECTED_RECURRING`, `DORMANT_COMMITMENT`, `COMMITMENT_ARREARS`.
- `DeriveConfig.java`: `DERIVE_VERSION` `derive/8` → `derive/9` when the output lands (Stage 4);
  `HASH_VERSION` unchanged — commitment/occurrence hashes are their own content hashes and
  `StateHash.forRow` is untouched in v1.

### 3.2 `trex-v2-log` and `trex-v2-sequencer`

- `LogCodec.decision(...)` / `decodeDecision(...)`: both are exhaustive switches, so they are
  compile-forced.
- `DecisionDraft`: new flat components (`commitmentId`, `name`, `direction`, `cadence`,
  `amountKind`, `kind`, `matches`, `amount`, `anchor`, `fromCandidate`, `endedAt`, `candidate`,
  `dueDates`), matching the existing one-shape-for-all style; `PIN_COMMITMENT`/`UNPIN_COMMITMENT`
  reuse `commitmentId` + `externalIds`, `NOTE_COMMITMENT` reuses `commitmentId` + `text`,
  `SETTLE_OCCURRENCE` reuses `commitmentId` + `dueDates`.
- `Sequencer.buildDecision`: structure only — required non-blank fields, enum parsing, a date for
  `endedAt`, non-empty `matches` on declare; `PIN_COMMITMENT`/`UNPIN_COMMITMENT` require known
  facts and a declared commitment; `NOTE_COMMITMENT` requires a non-blank text;
  `SETTLE_OCCURRENCE` requires a declared commitment and a non-empty list of ISO dates (the writer
  checks the shape, not the schedule); `SequencerState.fold` tracks declared/retired commitment
  ids so the commitment references can be checked on the stream path the way fact ids already are.
  A semantically wrong target still becomes `INEFFECTIVE_DECISION`.
- `Sequencer.REVIEW_KINDS`: add `SUSPECTED_RECURRING`, `DORMANT_COMMITMENT` and
  `COMMITMENT_ARREARS`, and add the missing `BALANCE_BREAK` (a
  latent inconsistency: the hub accepts it, `derive` special-cases it, but the stream path rejects
  it — a one-line drive-by fix, called out here so it is not silent).
- The hub's `precheckOne` switch is compile-forced too, so its seven structural branches land in
  Stage 3 even though the read endpoints wait for Stage 5.

### 3.3 `trex-v2-index`

- `schema.sql`: the four tables (§2.7) plus indexes on `commitment_occurrence(due_date, status)`
  and `commitment_note(commitment_id)`.
- `Sql.java`: `INSERT_*` constants; the four tables into `DERIVED_TABLES` and `ALL_TABLES`.
- `Indexer.insertDerived`: four prepared-statement batches; `derivedFingerprint`, rebuild and
  verify pick them up automatically through `DERIVED_TABLES`.
- `HubQueries.STATUS_TABLES`: add the tables so the status strip counts them.

### 3.4 `trex-v2-hub`

- `HubApi` + `HubService` + `HubHttpApi`: `GET /api/commitments` (the registry, each entry
  carrying its arrears and notes thread) and `GET /api/expected?window=today|week|month`; new DTOs
  `CommitmentJson` and `ExpectedResponse` (occurrences, arrears/catch-up, totals by direction).
- `HubSql`/`HubQueries`: selects over the new tables; `ReviewRow` gains a nullable enrichment for
  `SUSPECTED_RECURRING` (cadence, span, occurrence count, amounts, current price) so the Review
  row renders without a second fetch; the ledger query optionally joins the matched commitment for
  a chip.
- `HubService.precheckOne`: seven branches (compile-forced); `checkItem` learns
  `SUSPECTED_RECURRING`, `DORMANT_COMMITMENT` and `COMMITMENT_ARREARS`; declare prechecks
  (commitmentId slug shape, enum values, regex compile + `CatastrophicRegex`, non-empty matches);
  pin prechecks (known facts, a declared unretired commitment); note prechecks (a known
  commitment, non-blank text); settle prechecks (a declared commitment, a non-empty list of ISO
  dates). `DISMISS` stays a legal action on the wire for any kind.
- Session staleness (`asOfN`), SSE change feed and the owner/watch model are untouched.

### 3.5 `trex-v2-hub` web

- `index.html`: an **Expected** tab; `app.js`: import + register.
- `js/expected.js`: windows, occurrence ticks/crosses, monthly totals, the **Catch up** panel
  (oldest first, running total, Settle / Assign a payment), the registry, the lint panel, and the
  retire/re-declare dialogs.
- `js/api.js`: `commitments()`, `expected(window)`.
- `js/decisions.js`: `declareCommitment`, `retireCommitment`, `ignoreRecurring`,
  `pinCommitment`, `unpinCommitment`, `noteCommitment`, `settleOccurrence`.
- `js/review.js`: `KIND_LABEL`s; Confirm/Ignore for `SUSPECTED_RECURRING`; Mark ended /
  Keep tracking for `DORMANT_COMMITMENT`; Settle / Snooze for `COMMITMENT_ARREARS`.
- `js/blotter.js` / `js/eyeball.js`: an **Assign to commitment** row action (a `PIN_COMMITMENT`
  dialog over the registry; Unassign for `UNPIN_COMMITMENT`).
- `js/format.js` / `js/direction.js` / `account.js` reused; the ledger chips reuse the existing
  chip helpers.

### 3.6 Config

**None in this cut, and none needed** (operator, 2026-10-08): grouping uses the frozen
`MerchantStem.stem`, and a commitment's rules are the matching authority. A `vendors.yaml` is
rejected — multiple rules already cover every descriptor variation.

## 4. Invariant check

- **`derive()` is pure.** Detection and matching take `(facts, effective decisions, config,
  asOf)`; no clock, no I/O, deterministic ordering (commitment id, then date, then `n`).
- **Facts and decisions only.** Declares/retires/ignores/pins/notes/settles are decisions;
  candidates, rules, assignments, occurrences, prices, arrears, alert badges are derived.
  `occurred` (evidence) is never conflated with `settled` (a conclusion). No commitment is ever a
  property of a transaction; no `externalId` is rewritten.
- **Categorization and commitments are siblings** (operator, 2026-10-08): a commitment never reads
  a category and categorization never reads a commitment; an identical complex rule may exist in
  both; the stage order in `derive()` is incidental.
- **Decisions win.** Re-declare/retire/ignore/pin/note/settle are effective decisions; a pin
  overrides the rules; a settle overrides matching (and `REVOKE` restores); an unknown target is
  ineffective and visible.
- **Nothing is deleted.** A commitment is ended by an appended `RETIRE_COMMITMENT`, a rule by a
  re-declare, an assignment by `UNPIN_COMMITMENT`; no line is edited.
- **Derived tables are disposable.** `trex index --rebuild` reproduces all four tables;
  `trex verify` stays green.
- **One writer.** Declarations go hub → sequencer → one atomic append; the hub only reads.
- **The writer never interprets.** The sequencer checks structure and references only; every
  semantic judgement (is this candidate real, is that a price increase) is a derived answer or a
  person's decision.

## 5. Blast radius

| Module | File | Change |
|---|---|---|
| core | `derive/Commitment.java` | new record |
| core | `derive/CommitmentRule.java` | new record |
| core | `derive/CommitmentOccurrence.java` | new record |
| core | `derive/CommitmentNote.java` | new record |
| core | `derive/Cadence.java`, `AmountKind.java`, `CommitmentKind.java`, `CommitmentOrigin.java`, `CommitmentStatus.java`, `OccurrenceStatus.java` | new enums |
| core | `derive/Commitments.java` | new: detection, matching, cost (pure) |
| core | `Action.java` | 7 constants |
| core | `Decision.java` | 7 records + `permits` |
| core | `derive/Derivation.java` | 4 lists |
| core | `derive/Derive.java` | fold curation; call `Commitments`; review kinds; dismissal subject cases |
| core | `derive/ReviewItem.java` | `SUSPECTED_RECURRING`, `DORMANT_COMMITMENT`, `COMMITMENT_ARREARS` |
| core | `config/DeriveConfig.java` | `derive/9` bump (Stage 4) |
| log | `LogCodec.java` | encode/decode the 7 records |
| sequencer | `api/DecisionDraft.java` | new flat fields |
| sequencer | `Sequencer.java` | structural checks; `declaredCommitments` fold; `REVIEW_KINDS` |
| sequencer | `SequencerState.java` | track declared/retired commitment ids |
| index | `resources/.../schema.sql` | 4 tables + indexes |
| index | `Sql.java` | inserts; `DERIVED_TABLES`; `ALL_TABLES` |
| index | `Indexer.java` | 4 projection batches |
| hub | `HubService.java` | prechecks; read handlers |
| hub | `HubApi.java`, `HubHttpApi.java` | 2 routes |
| hub | `HubSql.java`, `HubQueries.java` | selects; status tables; review enrichment; ledger chip join |
| hub | `api/CommitmentJson.java`, `api/ExpectedResponse.java` | new DTOs |
| hub | `api/ReviewRow.java` | nullable candidate enrichment |
| hub web | `index.html`, `app.js` | Expected tab |
| hub web | `js/expected.js` | new mode |
| hub web | `js/api.js`, `js/decisions.js`, `js/review.js` | endpoints, factories, actions |
| hub web | `js/blotter.js`, `js/eyeball.js` | Assign-to-commitment row action |
| docs | `V2-SPEC.md`, `CHANGELOG.md`, `V2-PROPOSAL.md` | as-built + §8 amendments |

Tests: `core/CommitmentsTest`, `core/DeriveTest` (curation semantics,
transfer-leg commitments, dormancy, arrears and catch-up), `log/LogCodecTest` (the new records join the exhaustive
round-trip), `sequencer/SequencerTest` (structural checks), `index/IndexerTest` (rebuild ≡
incremental), `hub/CommitmentsApiTest` (endpoints), `hub/HubDecisionPathTest` (prechecks),
`hub/HubReviewTest`-adjacent (`SUSPECTED_RECURRING` / `DORMANT_COMMITMENT` /
`COMMITMENT_ARREARS` rendering).

## 6. Implementation plan

One PR per stage, branch `feat/commitments-<stage>`, merged on GitHub; local `master` is updated
by fetching the merge, never advanced locally (`AGENTS.md`). The tree builds and `mvn test` is
green after every commit; **a stage never implements a later stage's semantics**. The proposal is
the authority: its §8 amendments are applied in the stage that makes them true (named per stage),
so the spec of record never runs ahead of the code or behind it. Stages 1–2 touch no journal
grammar and no derivation output, so no version moves until Stage 4.

### Stage 0 — Land the plan (docs only)

**PR** `feat/commitments-plan` — this file only; no code. Gate: PR merged.

### Stage 1 — Pure detection, grouping and cost (core)

**PR** `feat/commitments-detector` — core only; `derive/8`, the index, the hub and the journal
grammar are untouched.

Deliverables: the `Cadence`/`AmountKind`/status enums; candidate records; the pure
`Commitments.detect(...)` grouping by `MerchantStem.stem`, with the cost timeline and step
changes; unit tests built on the fixture's measured shapes.

Acceptance: Netflix's `PAYPAL *NETFLIX AUS` detects monthly with the `$7.99 → $9.99` (2025-09,
+25%) step and no false step from a same-day double; `STAN $20 → $42 → $43.99` detects two steps;
a same-day `$22 + $20` pair is one occurrence; an FCY row (`AWS`, `OPENAI`) reads
`Foreign Currency Amount:`; an ended series (`YOUTUBEPREMIUM`) classifies `ended` against the
frontier; same inputs, same output; the candidate count is pinned as a calibration test (all
accounts and transfer series included — operator, 2026-10-08), and a home-loan transfer series is
among the candidates.

### Stage 2 — Occurrence matching (core)

**PR** `feat/commitments-matcher` — core only, still unwired. Gate: `mvn test`.

Deliverables: occurrence generation (calendar arithmetic) and rule-based matching; declared
schedules for bills; catch-up allocation across open occurrences (oldest first, `partial` and
pre-payment included); `occurred` / `settled` / `due` / `partial` / `missed`; the `lapsed` overlay
and the arrears totals.

Acceptance: a monthly commitment on the 31st clamps through February; an actual inside the window,
rule and amount range marks `occurred` and carries the matched id; a closed window with no match is
`missed`; a match arriving later (via a fact append) flips it at the next derive; a lump payment of
three periods clears three occurrences oldest-first and leaves no arrears; a short lump leaves the
next one `partial` with the remainder in arrears; a pre-payment covers future `due` occurrences
and any leftover becomes `off_schedule`; an `irregular` commitment records each matching
fact as an occurrence at its own date with no `missed`/arrears/dormancy; two
overlapping commitments resolve by latest declaration; a pinned fact overrides the rules and lands
on its commitment, an off-window pin becomes `off_schedule` and never moves the anchor, and
`UNPIN_COMMITMENT` releases it back; a matched transfer leg (a home-loan repayment) matches like
any other fact.

### Stage 3 — Decisions, codec and sequencer (core + log + sequencer + hub prechecks)

**PR** `feat/commitments-decisions` · **Proposal:** the §6.2 action table and §6.7 examples land
here (this stage writes them). Gate: `mvn test`; the golden log still parses.

Deliverables: the seven actions end-to-end; `DecisionDraft` fields; structural validation;
`declaredCommitments` in the sequencer fold; the compile-forced hub precheck branches (regex
compile + lint); the `REVIEW_KINDS` additions.

Acceptance: every new action round-trips in `LogCodecTest`; a decision batch with one bad draft
rejects only that row (and `allOrNone` rejects the batch); `DECLARE_COMMITMENT` with a blank
matcher or an unparseable cadence is rejected; a catastrophic regex is a `422`; a blank
`NOTE_COMMITMENT` text is rejected; an empty `dueDates` on `SETTLE_OCCURRENCE` is rejected;
`RETIRE_COMMITMENT` naming an undeclared id is accepted by the
writer (structure) and turns up as `INEFFECTIVE_DECISION` in derivation; `REVOKE` works;
`BALANCE_BREAK` now passes the stream path.

### Stage 4 — Fold, review kinds and index tables (core + index)

**PR** `feat/commitments-derive-index` · **Proposal:** §6.11, §7.1/§7.2, §9.8 and §9.9 (the sibling
stage and the review kinds) land here. Gate: `mvn test` plus `trex verify` on the dev fixture
(rebuild ≡ incremental).

Deliverables: effective curation folded; `Commitments` called from `Derive` as a sibling of
category over all current facts; `SUSPECTED_RECURRING`, `DORMANT_COMMITMENT` and
`COMMITMENT_ARREARS` items with their dismissal subject cases; the four derived tables (notes
included); the `derive/9` bump.

Acceptance: `derive` output is ordered and pure; a declared commitment persists through
`index --rebuild`; `IGNORE_RECURRING` removes the candidate and it stays gone; a
`RETIRE_COMMITMENT` stops future occurrences and clears the dormancy item; a dormant commitment
raises `DORMANT_COMMITMENT` without auto-ending; a lump catch-up clears the arrears and the item;
a `SETTLE_OCCURRENCE` clears it without a fact and `REVOKE` returns the occurrences to arrears; a
`NOTE_COMMITMENT` thread survives the rebuild; re-declare updates rules and history under the same
id; an unknown target is ineffective; `trex verify` green (rebuild ≡ incremental); the dismissal
of a candidate reopens when a newer fact lands for the series; a commitment matched on a transfer
leg does not move when a pairing pattern changes.

### Stage 5 — Hub read API and Review wiring (hub)

**PR** `feat/commitments-api`. Gate: `mvn test`; the endpoints answered against the dev index.

Deliverables: `GET /api/commitments`, `GET /api/expected`; review enrichment; the ledger chip
join; status counts; precheck polish (422 semantics, described failures).

Acceptance: `/api/expected?window=month` returns the month's occurrences with statuses, the
arrears/catch-up section and both direction totals; `/api/commitments` carries each commitment's
arrears and notes thread; `/api/review?kind=SUSPECTED_RECURRING` renders a candidate with its
cadence and amounts; `/api/review?kind=DORMANT_COMMITMENT` renders Mark-ended/Keep-tracking;
`/api/review?kind=COMMITMENT_ARREARS` renders Settle/Snooze; a stale `asOfN` is still `409`; an
unknown commitment target is `422`.

### Stage 6 — UI (web)

**PR** `feat/commitments-ui` · **Proposal:** the §10.1/§10.6 Expected-mode amendment lands here.
Gate: the manual acceptance below, on the redeployed dev stack.

Deliverables: the **Expected** mode (windows, ticks/crosses, totals, **Catch up** panel, registry,
lint panel, notes thread); Review Confirm/Ignore + Mark ended/Keep tracking + Settle/Snooze;
`decisions.js` factories; the ledger commitment chip; the Assign-to-commitment row action.

Acceptance (manual — the operator is the visual check): the month view shows a green Netflix
charge, a red missed bill and this month's committed total; the Catch up panel shows a three-deep
backlog with its total and Settle clears it; confirming a candidate creates a commitment visible
in the registry and removes the review row; ignoring one removes it for good; assigning an
unmatched charge from the Blotter turns its occurrence green; a dormant commitment appears in
Review and is ended or kept by hand; a provider move retires AGL and declares Origin as two
independent commitments; the Expected tab is walked as part of the regular review.

### Stage 7 — As built and rollout

**PR** `docs/commitments-as-built` · **Proposal:** the remaining §8 items (the tests guarantee and
the parked Firefly note).

Deliverables: `V2-SPEC.md` as-built; `CHANGELOG.md`; local rebuild → `trex verify`; deploy to the
host. (`derive/9` and the four tables are already covered by `verify` from Stage 4.)

Acceptance: `verify` green; the fixture shows the agreed candidate count and the Expected view
matches the data analysis; curation survives `index --rebuild`.

## 7. Worked acceptance (from the fixture)

1. **Netflix price change** — `PAYPAL *NETFLIX AUS` `$7.99 → $9.99 (2025-09, +25%)`: one
   commitment, two price points, no phantom missed occurrence around the change.
2. **Anthropic descriptor churn** — `ANTHROPIC`, `ANTHROPIC* CLAUDE SUB`,
   `CLAUDE.AI SUBSCRIPTION`: one commitment, three rules; the review queue never shows three
   candidates once the first is confirmed.
3. **AGL → Origin** — the AGL commitment is `RETIRE_COMMITMENT`-ed at its last occurrence; Origin
   is declared as a new commitment; no journal line edits a commitment in place and no link is
   needed — the two histories stand on their own (operator, 2026-10-08).
4. **Salary** — a `+` direction commitment; the monthly credit marks `occurred` green, and the
   Expected month shows income in green beside outgoing commitments.
5. **A declared quarterly bill** — `DECLARE_COMMITMENT` with a quarterly cadence and anchor:
   `due` → `occurred` when the fact lands inside the window and rules, `missed` (red) when the
   window closes without it, back to `occurred` if a late fact lands within tolerance.
6. **Rebuild and verify** — `trex verify` green; `index --rebuild` reproduces all four tables;
   curation survives; detection is deterministic at the same inputs and `asOf`.
7. **Discovery is not lost** — an ended series appears in the registry as `detected · ended`, so
   `YOUTUBEPREMIUM` stopping is a fact the system remembers even without a declaration.
8. **A bill the rules miss** — a quarterly bill paid by BPAY from another account with an
   unrecognisable description: `PIN_COMMITMENT` places it on the declared commitment, the
   occurrence goes green with `matched_by = pin`, and `UNPIN_COMMITMENT` returns it to the rules.
   A pin far from the schedule shows as an `off_schedule` occurrence, never a silent adjustment.
9. **A home-loan repayment** — the debit leg from the transaction account is a matched transfer,
   yet it is a commitment: detection proposes it, the declaration matches it on core fields, and
   the occurrence goes green. Changing `transfers.yaml` (a new pattern, a different pairing) never
   moves it — the rules saw description, account and sign, not the derived leg.
10. **Dormant is a question, not a verdict** — `YOUTUBEPREMIUM` stops: the tracked commitment goes
    dormant and raises `DORMANT_COMMITMENT`; the operator marks it ended (or dismisses to keep
    tracking). The journal has one appended `RETIRE_COMMITMENT`; nothing was auto-decided. Its
    `NOTE_COMMITMENT` thread ("cancelled after price rise") survives every rebuild.
11. **The backlog clears when the payment lands** — three monthly occurrences are missed (a red
    backlog of 3, `arrears_count = 3`, `COMMITMENT_ARREARS` open). The catch-up payment arrives as
    one fact of 3× the expected amount: the oldest-first allocation marks all three `occurred`,
    the backlog goes to zero, and the review item disappears. A payment of 1.5× instead leaves one
    `occurred` and the next `partial`, with half still in arrears.
12. **Off-journal paid** — the same bill was paid in cash: `SETTLE_OCCURRENCE` marks the three
    `settled` (no fact, no fabricated amount), attributed to the person; the arrears clear and the
    UI shows settled, not occurred. `REVOKE` restores the red backlog, and recording an authored
    fact instead (§12.4) would have matched like any other payment.
13. **An irregular bill** — a declared `irregular` commitment (a notice that arrives every few
    months, never on a schedule): each matching fact becomes an occurrence at its own date; the
    registry shows the count and the trailing 12-month total; nothing is ever red, missed or
    dormant.

## 8. Proposed proposal amendments (approval needed)

1. **§6.2** — add the seven actions to the table, with one example event each in §6.7.
2. **§6.11 (new)** — "Commitments and expected transactions": the unit and faces, rules-not-vendors
   (descriptor churn is a rule; a provider move is retire + declare, independent), core-fields-only
   matching (transfer legs included), curation-as-decisions (including the per-fact pin, the
   note thread and the off-journal settle), detection and its thresholds, occurrences and
   statuses (irregular tracked by observation, never predicted), arrears and catch-up allocation
   (§2.9), dormancy as a review question, and that
   nothing here is ever a property of a transaction.
3. **§7.1/§7.2** — `commitment`, `commitment_rule`, `commitment_occurrence`, `commitment_note`
   join the derived tables.
4. **§9.8** — curation precedence: latest effective declare/retire/ignore wins; fact ids resolve
   through supersession, commitment ids are decision-local; unknown targets are ineffective.
5. **§9.9** — a new **sibling stage of category** (after current facts, before review):
   **commitments and occurrences** (candidates included); it consumes only current facts,
   effective decisions and config — never the category output, and category never consumes it, so
   their order is incidental. Review consumes it for `SUSPECTED_RECURRING`, `DORMANT_COMMITMENT`
   and `COMMITMENT_ARREARS`; the `DISMISS` staleness rule gains the series-stem and
   commitment-id subject cases.
6. **§9.9.F** — `SUSPECTED_RECURRING`, `DORMANT_COMMITMENT` and `COMMITMENT_ARREARS` join the
   review-kind table.
7. **§10.1/§10.6 (new)** — the **Expected** mode (today / week / month, green/red, totals,
   registry, lint).
8. **§15** — the new tests join the guarantees (rebuild ≡ incremental covers the new tables;
   detection determinism).
9. **§11 (optional, deferred)** — Firefly projection of commitments is out of scope for v1;
   record it as a parked item, not silently dropped.

## 9. Decisions taken

1. **A commitment is first-class**, of the same significance as categorization: journaled
   curation, a `derive()` stage, materialised derived tables, `trex verify` coverage, a UI mode
   (operator, 2026-10-08).
2. **No vendor entity** (operator, 2026-10-08): a commitment is a name plus match rules; descriptor
   churn is another rule. The earlier "vendor immutable" requirement holds by construction — there
   is no vendor to mutate.
3. **A provider move is a workflow, and the two commitments are fully independent** (operator,
   2026-10-08): `RETIRE_COMMITMENT` + `DECLARE_COMMITMENT`; no link, no successor chain.
4. **Curation is decisions**: seven actions — declare/retire/ignore, plus
   `PIN_COMMITMENT`/`UNPIN_COMMITMENT` for the per-fact override (the category `PIN` gesture),
   `NOTE_COMMITMENT` for the thread and `SETTLE_OCCURRENCE` for the off-journal settle; matchers
   are embedded in the declaration and a re-declare replaces them; a YAML registry is rejected
   because it would be the one mutable thing the journal cannot see.
5. **Candidates are derived and disposable**; Review is the only way a candidate becomes a
   commitment, and an ignore is a decision, so discovery is never lost and never silently dropped.
6. **Grouping uses the frozen `MerchantStem.stem`** (operator, 2026-10-08): no `vendors.yaml`, no
   new normalisation layer; the rules on a commitment are the matching authority, and descriptor
   churn the stem does not merge is merged at confirm.
7. **Occurrences are calendar-based**, materialised over a bounded past + horizon, and derived at
   every refresh; nothing about an occurrence is stored on a fact. `irregular` is the exception:
   it predicts nothing and records each matching fact as an occurrence at its own date.
8. **Rules resolve by latest declaration**: the same "latest effective wins" idiom as
   `PIN`, with overlap lint in the registry.
9. **Rules and the domain use core fact fields only** (operator, 2026-10-08): description,
   account, sign, date — never `leg`, `pairing`, `category` or `role` (a reflow must not move a
   commitment). Matched transfer legs are in scope: a home-loan repayment is a commitment.
10. **Dormant is a review question** (operator, 2026-10-08): a dormant tracked commitment raises
    `DORMANT_COMMITMENT`; it is ended only by a person (`RETIRE_COMMITMENT`) or kept tracking
    (`DISMISS`). Nothing is auto-ended.
11. **A pin never re-anchors, and cadence/anchor are not edited in place** (operator, 2026-10-08):
    an off-window pin is an `off_schedule` occurrence; a genuine schedule change is a new
    commitment.
12. **Names locked** (operator): "Commitments" (registry) + "Expected" (forward view).
13. **Flat Review; the Expected tab is part of the regular review routine** (operator); the Eyeball
    `RECURRING_MISSING` re-sourcing stays a follow-up, and the Blotter chip ships without a
    `StateHash` change.
14. **Categorization and commitments are independent siblings** (operator, 2026-10-08): a complex
    rule may be duplicated verbatim across them; neither reads the other's output; execution order
    is incidental.
15. **Arrears are first-class and catch-up is automatic** (operator, 2026-10-08): misses
    accumulate visibly with totals; a lump payment allocates oldest-first across open occurrences
    (partials keep the remainder); `SETTLE_OCCURRENCE` clears a backlog without a fact (an authored
    fact is the alternative when the ledger should show the money); nothing is auto-forgiven and
    nothing is auto-retired.
16. **Unscheduled/episodic commitments are out of scope** (operator, 2026-10-08): a commitment
    implies recurrence (regular or irregular). One-off or episodic spend (traffic fines, repairs)
    is not a commitment, and no manual due-date/obligation mechanism is added; it stays in
    category/review territory. `irregular` remains for the recurring-but-unpredictable, tracked by
    observation.

## 10. Open questions — all resolved

The operator answered the first round on 2026-10-08; every item is resolved and the list is kept
for the record.

1. **Names** — resolved: **Commitments** (registry) + **Expected** (forward view).
2. **Occurrence generation** — resolved: horizon `asOf + 92 days`, past 12 months; `irregular`
   generates no dates and records each matching fact at its own date (tracked, never predicted).
3. **Off-journal paid** — resolved (2026-10-08): arrears are first-class (§2.9); a catch-up
   payment clears several occurrences oldest-first; when no fact exists, `SETTLE_OCCURRENCE` is a
   decision, and an authored fact (§12.4) is the alternative when the ledger should show it.
4. **Income** — resolved: one model, a direction facet.
5. **Usage expectations** — resolved: trailing observed range.
6. **FX** — resolved: track the FCY price where present, match on the AUD charge.
7. **Provider move** — resolved: fully independent commitments; no link.
8. **Candidate scope** — resolved: all accounts, **including transfer movements**; the domain and
   the rules see core fact fields only (derived fields are ephemeral).
9. **Lifecycle** — resolved: keeps `dormant`; a dormant tracked commitment raises
   `DORMANT_COMMITMENT` and is ended by hand.
10. **Notes** — resolved: `NOTE_COMMITMENT` (a thread); `NOTE_CLASSIFY` was dropped at the
    operator's direction (2026-10-08).
11. **Review flatness** — resolved: flat.
12. **Eyeball `RECURRING_MISSING`** — resolved: re-source later; the Commitments/Expected tab is
    part of the regular review routine.
13. **Blotter chip and `stateHash`** — resolved: chip now, `StateHash` later.
14. **Normalisation** — resolved: **no `vendors.yaml`**. Grouping is the frozen
    `MerchantStem.stem`; a commitment's multiple rules cover every descriptor variation, and what
    the stem does not merge is merged at confirm (the leftover candidate is suppressed by rule
    coverage).
15. **Pin allocation** — resolved: a pinned fact allocates exactly like a rule match (§2.9), the
    oldest open occurrence first; with no open occurrence it is `off_schedule` at its own date. A
    pin never re-anchors, and any re-anchor needs a new commitment.

## 11. Rollback

Additive: seven new actions (an older binary that meets them treats them as `Unknown` lines and
ignores them, per the codec's forward-compatible contract), four disposable derived tables, two
read endpoints and one UI tab. Rollback is "redeploy the previous image"; every declaration
remains a valid line and nothing needs migration. If the feature is abandoned mid-way, the
commitment derivation can be unhooked from `Derivation` and `deriveVersion` reverted in one
commit — the decisions stay in the journal, harmless and inactive.

## 12. Follow-ons (parked; each gets its own plan)

Direction the operator set on 2026-10-08, deliberately **not** part of this build order:

- **Push delivery.** The runner's schedule (§5.5) already triggers one-shot jobs; a `notify` job
  computes a digest from the derived state — arrears first, then due-soon and price increases —
  and delivers it to browser (SSE / Web Push), email,
  ntfy, or an agent. Two rules: the digest is derived from `(facts, decisions, config, asOf)` —
  nothing about an alert is stored on a fact — and delivery state ("sent; nothing changed since")
  is disposable bookkeeping in the `projection_state` spirit, never journal truth unless the
  person concludes something (`USER_ACK` today, perhaps a dedicated ack later). Privacy note:
  amounts and merchant names leaving the machine is a per-channel decision (self-hosted ntfy vs a
  public topic; a summary vs the full detail).
- **Projected cash-flow.** Commitments + account balances + income cadence → a projected
  low-water mark ("short on the 24th unless …"). This is what turns occurrences into decisions;
  it needs its own design (openings, reconciliation and the balance chain exist; the forecast is
  the new part).
- **MCP server.** A thin client of the **hub API** — the same boundary the egress and `trex
  verify` respect, never the journal or the index. Read tools first (`ledger`, `review`,
  `commitments`, `expected`, `accounts`, `workbook`), then a single `submit_decisions` tool that
  posts drafts to `POST /api/decisions`, so every write keeps prechecks, staleness (`409`) and
  attribution. MCP is JSON-RPC; a minimal server is JDK + Jackson over stdio (or loopback HTTP via
  the hub's `HttpServer`), no new framework. An agent acting for the person should be a distinct,
  attributed user (e.g. `agent` in `users.yaml`), not silently `ron`; write tools are opt-in.
- **Budgeting / bracketing** (operator, 2026-10-08): envelope-style budgeting built on commitments
  is parked until the monthly committed totals prove insufficient. A budget is a plan for money
  that has not moved yet — a different concern from an expectation of a specific movement — and
  merging it into the commitment model would distort both.
