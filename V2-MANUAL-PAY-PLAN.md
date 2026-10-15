# V2-MANUAL-PAY-PLAN.md

> **Personal project, single operator, private.** `V2-SPEC.md` is the specification; this file is the
> build order for one change to it. Where this file and the spec disagree, the spec wins; the spec is
> amended first (Stage 1), per `AGENTS.md`.

**Status:** Stages 1a–4 implemented (2026-10-15); Stage 0 and Stage 5 remain pending. No manual declarations have been applied.
**Decisions:** D-1 through D-5 decided (operator, 2026-10-15): use the recommendations in §3; a later in-window fact replaces the settled row and clears its derived `settleN`, while the settle remains in the log.
**Authority:** `V2-SPEC.md` §5 (commitment curation), §6.5 (commitments, occurrences), §9.1 (the
status strip and its nudges), §9 (Expected); `docs/plans/V2-MANUAL-ARREARS-PLAN.md` (occurrence
states, settle); `AGENTS.md`.

---

## 1. The problem

Most declared commitments pay themselves: a direct debit, a card on file, a payroll credit. A few
need a person to act — log in, pay the bill, transfer the money. Those are the ones that get missed:
the Carbon Comms NBN occurrences of 2026-05-16 and 2026-06-16 were missed and settled after the
fact (decision n=7649).

trex already knows every due date, but it never says **"you have to do something"**:

- The expectations nudge (`expectedDueThisWeek`, §9.1) counts every declared occurrence due this ISO
  week, automatic ones included. The manual bill is one muted number among debits that need nothing.
- It is bounded by the ISO week, so a bill due Monday first appears on that Monday — no lead time.
- A missed occurrence surfaces later as `COMMITMENT_ARREARS` in Review — after the damage.

## 2. The model

**A commitment says how it is paid.** `DECLARE_COMMITMENT` gains `payment: auto | manual`
(absent = `auto`) and an optional `remindDays` (lead time; only meaningful for `manual`). Both are
curated fields: a re-declare with the same id replaces them (§5 — "the edit"), so switching an
existing commitment to manual is the registry's existing **Edit**, not a new decision kind.

**The reminder is a read, not a state.** `/api/status` gains `manualToPay`, computed by the hub
from the index the same way `expectedDueThisWeek` is (`HubQueries`, a `HubSql` count), with the hub's
`today`:

| Bucket | Occurrence of a declared, unretired `manual` commitment | Strip |
|---|---|---|
| `upcoming` | `status = 'due'` and `today ≤ due_date ≤ today + remindDays` | amber `💳 N to pay` |
| `overdue` | `status ∈ {due, missed, awaiting}` and `today − 30 days ≤ due_date < today` (D-4) | red `💳 N to pay · M overdue` |

It counts **occurrences**, never commitments or dollars (the expectations-nudge rule). An
`occurred` or `settled` occurrence is never counted.

**Mark paid clears it.** After paying, the operator marks the occurrence paid — the existing
**Settle** (`SETTLE_OCCURRENCE`; `trex_mark_paid` over MCP). It writes a decision, never a
transaction. The occurrence is `settled` and leaves the count at once.

**Mark paid never touches a transaction** (operator rule, 2026-10-15). `SETTLE_OCCURRENCE` names a
commitment and due dates only — no `externalId` — and derive passes it to the commitment matcher
alone; no fact, category, transfer, unit or Firefly posting changes. Neither does anything in this
plan. Only the derived occurrence row moves.

**The transaction lands by itself.** With the next statement or feed the payment's fact is claimed
by the commitment's rules and attaches to the same window; evidence wins on the row
(`docs/plans/V2-MANUAL-ARREARS-PLAN.md` §3.3): the occurrence becomes `occurred` with the fact's id
and amount, and the settle stays in the log. If the operator never marks it paid, the fact's arrival
clears the reminder the same way.

**Nothing new is stored beyond the two declaration fields.** The counts are a hub query over the
disposable index; `trex index --rebuild` reproduces them. Occurrence generation, matching and status
are unchanged.

---

## 3. Operator decisions

| Id | Question | Recommendation | Why it is a decision |
|---|---|---|---|
| **D-1** | Where does "manual" live? | **On the declaration** (`payment`, `remindDays`), edited by re-declare. | Alternative is a config list (`commitments.yaml`); that splits one commitment's definition across the log and config and needs a reflow path. The declaration already carries every curated field. |
| **D-2** | Lead time | **`remindDays` per commitment, default 5** when `manual` and absent. | A quarterly bill paid by bank transfer may want 10 days; a card payment 2. One global value is simpler but wrong for the first quarterly bill. |
| **D-3** | "I've paid, the bank hasn't shown it yet" | **Mark paid** (the existing Settle). The fact that lands later wins the row (§2). Requires Stage 1a's fix. | Operator decision, 2026-10-15. Today the later fact's amount is **added** to the settled expected amount (§4.0) — the fix is a precondition, not optional. |
| **D-4** | How far back is "overdue"? | **30 days.** Older unpaid occurrences stay where they are today: Catch up and `COMMITMENT_ARREARS`. | Marking an old commitment manual would otherwise light the strip red with a year of history. The reminder is about *now*; the backlog already has a home. |
| **D-5** | Does `awaiting` count as overdue? | **Yes**, for manual commitments only. | `awaiting` means the window closed before the statements reached it. For an autopay that is benign (excluded from the existing nudge); for a manual payment it is exactly the forgotten case. With statements only (no feed — BankSync paused 2026-10-15) it is common between downloads, so it is cleared by **Mark paid**, not by waiting; the statement-age nudge explains a stale account. |

---

## 4. The change

### 4.0 Fix: a fact landing on a settled occurrence (existing bug)

Measured 2026-10-15 with a probe in `CommitmentMatchTest`: a monthly bill of -100.00 settled for
2026-01-15, then a -95.00 fact on 2026-01-16. The row is `occurred` with the fact's id — correct —
but its amount is **-195.00**. `CommitmentMatcher.applySettles` sets `slot.amount` to the expected
price; `attach` then sums the fact onto it. `V2-MANUAL-ARREARS-PLAN.md` §3.3 says the row carries
"the fact id and amount".

Fix in `attach`: when a claimed fact lands in the window of a `SETTLED` occurrence, the first fact
replaces the expected amount and clears `settleN` on the derived row; any further facts in the window
sum as usual. The fact id/date/provenance are set and the row becomes `occurred`; the historical
settle remains in the log. A fact outside the window leaves the settled row unchanged. The
`applySettles` doc comment ("a later fact skips it") is wrong too and is corrected. Test: the probe
above, plus the outside-window case, kept. Changes derived output: `derive/17` → `derive/18`.

**Egress effect:** none (occurrences are not projected). The Expected occurrence amount and the
headroom's "commitments paid" line (§9.2) change for every month where a settle was followed by the
real payment: today they double count it.

### 4.1 Log and core: the two fields

- `Decision.DeclareCommitment` gains `Payment payment` (enum `AUTO | MANUAL`, wire `auto | manual`)
  and `Integer remindDays`. The compact constructor defaults `payment` to `AUTO` when null and refuses
  `remindDays` outside `1…60` and `remindDays` on an `auto` declaration.
- `LogCodec`: optional on read (every existing `DECLARE_COMMITMENT` line decodes as `auto`), written
  only when not the default — so an `auto` declaration's line is byte-identical to today's.
- `DecisionDraft` / `Sequencer`: pass through; structure validation only (the writer never interprets).
- `Derive` commitment fold → `Commitment` carries `payment` and the effective `remindDays`
  (`manual` and null → 5). Nothing else in derive reads them: occurrences, matching, arrears and the
  review kinds are untouched.

### 4.2 Index

- `commitment` table: `payment TEXT NOT NULL DEFAULT 'auto'`, `remind_days INTEGER`.
- The new columns change the derived output, so `DeriveConfig.DERIVE_VERSION` bumps again
  (`derive/18` → `derive/19`); the hub rebuilds the index on the version change as for any reflow.

### 4.3 Hub

- `HubSql.MANUAL_TO_PAY_UPCOMING`, `MANUAL_TO_PAY_OVERDUE` — the §2 table, joining
  `commitment_occurrence` to `commitment` on `origin = 'declared' AND retired_n IS NULL AND
  payment = 'manual'`.
- `HubQueries.manualToPay(LocalDate today)` → `{upcoming, overdue}`; `HubService.status` passes the
  hub's `today` (as `expectedDueThisWeek` does — a read, not derive, so no purity concern).
- `StatusResponse.manualToPay`.
- `/api/expected` gains `toPay`: the occurrence **rows** behind those counts (same SQL, `SELECT` of
  the occurrence columns instead of `COUNT`), overdue first, then by due date; each row carries
  `bucket` (`overdue | upcoming`) and `remindDays`. Independent of `window` — the list and the strip
  can never disagree.
- `/api/commitments` carries `payment` and effective `remindDays` (so re-declare preserves the
  curated fields); `/api/expected` occurrences carry `payment`.
- `/api/decisions` precheck: nothing new (the core record refuses a bad `remindDays`; the hub maps it
  to `422`).

### 4.4 UI

- **Status strip** (`status.js`): the `💳` item goes **first** among the nudges — before the review
  count — because it is the only one that asks for an action with a deadline. Hidden at zero.
  Amber when only `upcoming`, red when `overdue > 0`. Click → Expected, scrolled to the Manual
  payments group (`#expected?section=to-pay`), Occurrences unfolded.
  Tooltip: *"Manual payments due within their reminder window, or unpaid in the last 30 days. Mark paid
  once you have paid; the transaction lands with the next statement."*
- **Expected → Occurrences: a `💳 Manual payments` group** (`expected.js`, inside the Occurrences
  fold, above the window's table). Rendered from `toPay`, so it shows under **every** window tab
  (Today / Week / Month) and lists exactly what the strip counts. Columns: commitment, due date,
  days left (or `N days overdue`, red), expected amount, account(s) from the rules, **Mark paid**.
  Mark paid opens the existing Settle dialog for that one due date; the row leaves the group on the
  response. Hidden when empty, so an operator with no manual commitments sees today's Expected
  unchanged. The Occurrences header's live figure gains `· N to pay` when non-zero, so a folded
  section still speaks (`V2-EXPECTED-UX-PLAN.md` §4).
- The same occurrences still appear in the window's table below (with a `💳` marker): the group is a
  to-do view, the table is the calendar. Nothing is removed from the table.
- **Declare / Edit dialog** (`commitment.js`): a `Paid manually` checkbox and a `Remind N days
  before` field (shown when checked). `decisions.declareCommitment` passes the two fields.
- **Registry**: a `💳` marker on manual commitments.

### 4.5 API and MCP

No new tool. The existing ones cover it once §4.3 lands:

- `trex_status` (wraps `/api/status`, read-only) returns `manualToPay`.
- `trex_commitments` / `trex_expected` (read-only) carry `payment`.
- `trex_submit_decisions` (wraps `/api/decisions`, writes) sends the re-declare that sets `payment`.
  A re-declare replaces every curated field and the rule set, so the agent copies the rest from
  `trex_commitments` unchanged.
- `trex_mark_paid` (wraps `/api/decisions`, writes `SETTLE_OCCURRENCE`) is "I paid it".

**Egress effect (stated per `AGENTS.md`).** None. Commitments and their occurrences are never
projected to Firefly (§11); no unit, account, type or balance changes. `DERIVE_VERSION` moves only
because the `commitment` table gains columns.

---

## 5. Invariant check

| Invariant | Holds because |
|---|---|
| `derive()` pure | derive only copies two declared fields onto `Commitment`; the date arithmetic with `today` is a hub read of the index, like `expectedDueThisWeek` |
| The log holds facts, decisions, ingest events | the two fields ride on an existing decision; nothing new is written, ever, for a reminder |
| The writer never interprets | the sequencer validates shape and range only |
| Decisions win | `payment` is a decision; nothing derives it |
| Nothing deleted | switching back to `auto` is a re-declare; `REVOKE` restores the previous declaration |
| Derived tables disposable | the columns and the counts rebuild from the log |
| No category on a transaction | untouched |
| Mark paid changes no transaction | settle carries no `externalId`; §4.0's fix edits the occurrence row's amount only (Stage 1a test asserts the fact and its ledger row are unchanged) |
| No rewriting `externalId` | untouched |

---

## 6. Stages

Each stage is one PR: failing tests first, then the change, then the doc amendment; the tree green
after every commit.

| Stage | What | Kind | Gate |
|---|---|---|---|
| 0 | **Choose.** The operator lists which declared commitments are paid by hand (the registry has 26 active; Carbon Comms NBN is the first) and their lead times. No code. | operator | — |
| 1a | **Fix settle-then-fact** (§4.0). Independent of the rest; may land first. Spec: §6.5 states the rule (today only in the archived plan). | derive | Matcher tests cover in-window replacement and out-of-window preservation; `derive/17` → `derive/18`. |
| 1 | **Spec PR.** §5 (the two fields on `DECLARE_COMMITMENT`), §6.5 (`Commitment.payment`), §9.1 (`manualToPay`, its buckets, its place in the strip, the tooltip), §9 Expected (the Manual payments group in Occurrences, the marker), the API table's `/api/status` and `/api/expected` rows; the egress statement. | spec | D-1…D-5 |
| 2 | **Log + core + index.** `Payment` enum, the record fields and validation, `LogCodec` (default omitted), the derive fold, the `commitment` columns, `DERIVE_VERSION`. Tests: an old line decodes `auto` and re-encodes byte-identical; a re-declare flips `payment`; `REVOKE` of it restores the earlier value; `remindDays` on `auto` refused; rebuild reproduces the columns. | core + log + index | Stage 1 |
| 3 | **Hub.** The two SQL counts, `HubQueries.manualToPay`, `StatusResponse`, `payment` on the commitments/expected payloads, `/api/expected` `toPay`. Tests (`CommitmentsApiTest`): `toPay` rows equal the status counts under every `window`; upcoming at `due − remindDays` and not the day before; an `occurred` occurrence uncounted; a `settled` one uncounted; `awaiting` and `missed` overdue within 30 days, not at 31; an `auto` commitment never counted; a retired manual commitment never counted. | hub | Stage 2 |
| 4 | **UI.** Strip item, the Manual payments group in Expected → Occurrences with Mark paid, Declare/Edit fields, markers. Check in the running dev hub (scratch `TREX_DEV_RUN`, never the operator's `run/`). | UI | Stage 3 |
| 5 | **Use.** The Stage 0 list marked manual — through Edit, or by an agent with `trex_submit_decisions` (one re-declare each). | operator / agent | Stage 4 |

---

## 7. Later, not now

- **Amount on the reminder.** The strip counts occurrences, never dollars (§9.1's rule); the
  Expected row already shows the expected amount.

---

## 8. Rollback

Revert the PRs and `trex index --rebuild`. Declarations written with `payment: manual` stay in the
log, so a rollback after Stage 5 keeps Stage 2's codec commit (whether the old codec tolerates the
unknown fields is unverified — Stage 2 records the answer here). Before Stage 5 nothing manual is in
the log and a full revert is clean.
