# V2-REVIEW-FIXES-PLAN.md

> **Personal project, single operator, private.** `V2-PROPOSAL.md` is the specification; this file
> is the build order for the fixes raised in `CLAUDE-REVIEW.md` (and the still-open items of
> `claude-v2-proposal-spec-review.md`). Where this file and the proposal disagree, the proposal
> wins — until each stage's amendment lands.
>
> **Status:** decided — Stage 0 (this plan). Nothing is built.
> **Decisions (operator, 2026-10-09):** D-A through D-F (§2) are all accepted as recommended.
> **Authority:** `V2-PROPOSAL.md` §6.1/§6.5 (identity, dedup, row outcomes), §8.3 (identity),
> §9.1/§9.9 (derive), §12.2 (cursors); `V2-COMMITMENTS-PLAN.md`; `V2-MANUAL-ARREARS-PLAN.md`;
> `V2-JOB-RUNNER-PLAN.md`; `AGENTS.md`.
> **Goal (operator, 2026-10-09):** daily use, self-hosted — statements drop in, the derivation does
> the work, and the screen is trustworthy and calm. Stages are ordered by that goal: first what
> hides or invents data, then what raises false alarms, then friction, then documents.

---

## 1. Summary

| Stage | Fix | Source | Kind | Gate |
|---|---|---|---|---|
| 0 | Baseline measurements and failing tests | — | measure | — |
| 1 | **Receipt collisions merge distinct transactions** | new (H0) | identity | **D-A** |
| 2 | Re-observation raises review; `observation` in the dedup key | H1, M2 | derive + writer | — |
| 3 | `missed` judged against the frontier, not `asOf` | N1 | derive | **D-B** |
| 4 | The `asOf` contract, written and tested | N2, M3 | spec + test | — |
| 5 | The sequencer picks up config edits | M1 | writer | — |
| 6 | Drop-folder ingest | goal | runner | — |
| 7 | "Left this month" | goal | hub read | **D-D** |
| 8 | A daily "all clear" | goal | hub/UI | — |
| 9 | Spec and document fixes | N3–N6, N8, D1, D2 | docs | **D-C**, **D-E** |
| 10 | Feed cursors in the log | M5 | log + index | **D-F**; before the first feed |

Stages 1–3 are the **90% line's** correctness half; 6–8 its daily-use half. After Stage 8, freeze
features for a month (`CLAUDE-REVIEW.md`, "The 90% line").

Each stage is one PR: its tests first (failing), then the change, then the doc amendment, with the
tree green after every commit (`AGENTS.md`). Each derive-changing PR bumps `deriveVersion` once.

---

## 2. Operator decisions

Each recommendation is the smallest resolution. **All six accepted as recommended (operator,
2026-10-09).** Each stage's PR still writes its rule into the spec as it lands.

| Id | Question | Recommendation | Why it is a decision |
|---|---|---|---|
| **D-A** | How do rows that share a receipt on one day get distinct ids? | **Collided rows mint a content-hash id; unique-receipt rows keep their natural key** (§4.3). | Identity rules are frozen (`Ids.java`, §8.3, `AGENTS.md`). |
| **D-B** | What does an occurrence show when `asOf` is past its window but the statement is not? | **A new status, `awaiting`** (grey, "waiting for the statement"); never arrears. | A new status value in a derived table and the UI. |
| **D-C** | Which document is in charge? | **`V2-SPEC.md` authoritative; `V2-PROPOSAL.md` frozen as history.** | It edits `AGENTS.md`'s authority section. |
| **D-D** | Add "left this month", given budgeting was parked on 2026-10-08? | **Yes, as a read over existing tables — a headroom figure, not envelope budgeting.** | It revisits the 2026-10-08 call (`V2-COMMITMENTS-PLAN.md` §11). |
| **D-E** | Rename `SETTLE_OCCURRENCE` / `settled` (N7)? | **No code rename. Relabel in the UI only ("Mark paid" / "paid by hand").** | The log is permanent; a wire rename needs a codec alias forever. |
| **D-F** | Where does a feed cursor live? | **In the log: an optional `cursor` on the `trex.ingest complete` event; `source_cursor` derived from it.** | It moves a durable home named in proposal §12.2. |

---

## 3. Stage 0 — baseline and failing tests

Run on the private fixture journal in a scratchpad `TREX_DEV_RUN` (never `run/` or
`deploy/dev/run`). Record each number in this plan's amendment so every later stage proves a delta,
not a hope:

- `txn_current` row count per account; `review_item` count per kind; accounts with `BALANCE_BREAK`.
- `UNMATCHED_LEG` items whose counterpart shares a receipt with a hidden row (Stage 1).
- `commitment_occurrence` rows by status, and `COMMITMENT_ARREARS` items, at `asOf` = today and at
  `asOf` = the newest frontier (Stage 3).
- Count of ids with more than one posted observation, by which fields differ (Stage 1/2).

Already measured (fixture journal `trex.jsonl`, 6,147 facts, 2026-10-08):

| measure | value |
|---|---|
| distinct `externalId`s | 6,035 |
| ids with more than one posted observation | **58** (all `ing-csv`) |
| …on `ing-credit-card`, three rows each | 54 |
| …on `ing-variable-rate`, two rows each | 4 |
| fields that differ | always `amount` + `rawDescription` (balance-only: **0**) |
| facts hidden from `txn_current` | **112** (= 6,147 − 6,035) |

---

## 4. Stage 1 — receipt collisions (new finding H0; gate D-A)

### 4.1 The defect

ING puts one receipt number on several *different* transactions of the same day:

| n | account | date | amount | receipt | text |
|---|---|---|---|---|---|
| 2870 | ing-credit-card | 2025-01-03 | +1.92 | 171689 | International Transaction Fee Rebate |
| 2871 | ing-credit-card | 2025-01-03 | −1.92 | 171689 | International Transaction Fee |
| 2872 | ing-credit-card | 2025-01-03 | −64.01 | 171689 | MUMBAI TRAVEL RETAIL P - Visa Purchase |
| 1103 | ing-variable-rate | 2026-02-09 | +299.00 | 900077 | Transfer |
| 1104 | ing-variable-rate | 2026-02-09 | −299.00 | 900077 | Orange Advantage annual fee |

The natural key is `nk|account|date|receipt` (`Ids.java:30`), so each group mints **one** id. The
sequencer appends the second and third rows as `Flagged` re-observations of that id
(`Sequencer.java:359-373`), and derive keeps only the latest (`Derive.java:313`, `latestById`). The
fee and rebate disappear; on `ing-variable-rate` the `+299.00` transfer leg disappears every year.
The log is intact (no data is lost), but 112 transactions are invisible to every derived view, and
H1 (Stage 2) means nothing says so.

The proposal anticipated receipts recurring *across days* ("the date is in the key because
receipts recur"), not *within* a day.

### 4.2 Why this blocks the goal

Hidden rows break the balance chain, leave transfer counterparts `HELD` (an `UNMATCHED_LEG` after
30 days), and understate fees. Each is a number on the screen that cannot be trusted.

### 4.3 The fix (recommended for D-A)

**Rule.** In one batch, count rows by `(accountRef, date, receipt)`. A row whose receipt is unique
on that day keeps `nk|account|date|receipt`. When two or more rows share it, **every one of them**
mints the content-hash id `ch|account|date|amount|rawDescription|occ`, with `occ` from the existing
content rule. The `receipt` field is still stored on each fact, so T1 pairing and `TRF-<receipt>`
are unchanged.

- **Complete.** Day-atomic batching guarantees a day is never split across calls
  (`DayBatcher`), so the per-batch count sees the whole day.
- **Deterministic.** An overlapping re-fetch of the same statement mints the same ids, so the
  re-ingest is all `Duplicate`.
- **No id is rewritten.** Existing natural-key ids of unique receipts are unchanged. The 58
  collided ids stay in the log; they are simply never minted again.
- **Where.** `Sequencer` pre-pass before `assignOcc`; `Ids.externalId` gains an explicit
  `receiptShared` argument (and its javadoc states the rule), so the frozen formats are unchanged
  and the choice between them is the only new thing.

**Repair of history** (existing machinery, no new decision kind). `trex reparse --apply` over the
stored evidence of the affected files produces, per collided id `X`:

- one `SUPERSEDE X → X'`, where `X'` is the new id of the row matching `X`'s latest observation, so
  the chain root (and with it the Firefly unit identity) survives;
- an `Appended` fact for each other row of the group.

Verify `Reparse` emits exactly that (supersede the matching row, append the rest). If it would
instead `RETIRE X` and append all rows, extend it to prefer `SUPERSEDE` when a new row matches the
retired one's content — the projection depends on it.

### 4.3a As built (2026-10-09)

- **Rule refined:** the trigger is two or more *different* `(amount, rawDescription)` sharing
  `(account, date, receipt)`, not two or more rows — identical rows sharing a receipt stay one
  natural key (`SequencerTest.sameBatchTwoIdenticalNaturalKeyRowsCollapse` pins that). Collided
  rows share the content rule's `occ` counter, so a receipt-less row of identical content on the same
  day never mints the same id. One implementation, `Ids.mint`, serves the sequencer and the re-parse
  preview.
- **Re-parse made idempotent.** `Reparse.diff` treated an already-superseded id as a row no longer
  read, so a second `--reparse --apply` retired it — cutting the chain that pins, pairs and units on
  the old id resolve through. It now skips ids closed by an effective `SUPERSEDE`/`RETIRE`
  (`Reparse.closedIds`, revocations honoured).
- **Measured** (private `Final/` statements, E2E at `asOf` 2026-10-01): current facts 6,029 → 6,141;
  units 5,354 → 5,466; review counts unchanged. A repair of a master-built journal (54 + 4
  `SUPERSEDE`, 170 facts) yields the same rows as a fresh ingest, and a second pass changes nothing.
- **Config follow-on.** The restored `+$299` loan `Transfer` settling each Orange Advantage fee line
  broke the loan chain, because only the fee line was a `noop` reference (a workaround for this very
  bug). `profiles.yaml` now marks the settling line `noop` too, with an anchored match; the chain
  closes and the review queue is unchanged.
- **Build note.** An incremental `package` kept a stale `Ids.class` in the shaded jar; build release
  images from `mvn clean package`.

### 4.4 Tests

- `SequencerTest.aReceiptSharedOnOneDayMintsDistinctIds` — the three-row group above → three ids,
  all `Appended`.
- `SequencerTest.aUniqueReceiptKeepsItsNaturalKey` — pins that no existing id moves.
- `SequencerTest.reIngestOfACollidedDayIsAllDuplicate`.
- `ReparseTest.aCollidedIdIsSupersededByItsMatchingRow`.
- Fixture acceptance: after the reparse, `txn_current` grows by 112; record the change in
  `BALANCE_BREAK` and `UNMATCHED_LEG` against the Stage 0 baseline.

### 4.5 Docs

Proposal §6.1/§8.3 and `V2-SPEC.md` §4 state the rule; `docs/V2-PARITY.md` records it as a v2 delta
(v1 surfaced these as `POTENTIAL_DUP`; v2 must distinguish them). No `deriveVersion` bump: identity
is minted by the writer.

---

## 5. Stage 2 — re-observation review (H1) and the dedup key (M2)

### 5.1 H1: a changed re-observation is silent

After Stage 1, a second *posted* observation of one id with different content is a genuine anomaly
(a bank restating a row), and proposal §6.5 promises a review item for it. Today derive compares
different ids only (`Derive.java:1602-1648`).

**Predicate** (new, in §9.9.F). For each id, take its posted observations in `n` order. If any two
differ:

- in `amount` or `rawDescription` → `RESTATEMENT`, subject the id;
- in `balance` only → `POTENTIAL_DUP`, subject the id.

Pending observations are excluded, so pending → posted is never an item. The latest observation
stays current (unchanged); the item says so and lists each `n`. Resolution is `DISMISS` (accept the
latest), or a reparse if the parser was wrong. Measured noise after Stage 1: **0** balance-only
cases on the fixture, and the 58 amount/text cases become distinct ids — so the predicate should
fire on nothing today. That is the point: a safety net that is silent until something is wrong.

Derive needs all observations per id, not just `latestById`; keep a `Map<String, List<Fact>>` built
in the same replay pass (sorted by `n`; no unordered iteration).

### 5.2 M2: `observation` is not in the dedup key

`ObsKey` (`SequencerState.java:35`) omits `observation`, so a posted row identical to its pending
row — same text, date, amount, balance — is a `Duplicate` and the fact stays pending until
`STALE_PENDING`. Add `observation` to `ObsKey` and to §6.5's key. The state is folded from the log
on recovery, so no sidecar migrates; old lines simply produce keys with their own observation.

### 5.2a As built (2026-10-09)

- `Derive.addReObservations` after the clusters; `derive/13`.
- Measured at `asOf` 2026-10-01: a fresh ingest of the private `Final/` statements raises nothing
  new (`RESTATEMENT` 5, as before). The **unrepaired** master-built journal raises 63 (5 + the 58
  collided ids of Stage 1), and the repaired one 5 — so a live journal that still needs the Stage 1
  repair now says so in the review queue.
- `observation` joins `ObsKey`; `SequencerTest.aPostedRowIdenticalToItsPendingRowIsAppended`.

### 5.3 Tests

- `DeriveTest.aReObservationWithNewTextRaisesRestatement`
- `DeriveTest.aReObservationWithOnlyANewBalanceRaisesPotentialDup`
- `DeriveTest.pendingThenPostedOfOneIdRaisesNothing`
- `SequencerTest.aPostedRowIdenticalToItsPendingRowIsAppended`
- Invariant tests stay green (`deriveIsPure`, `rebuildEqualsIncremental`).

`deriveVersion` → `derive/13`.

---

## 6. Stage 3 — `missed` against the frontier (N1; gate D-B)

### 6.1 The defect

Coverage (`active`/`dormant`/`ended`) is judged against the accounts' posted frontier
(`Commitments.java:138`), but an occurrence is born `missed` when `windowEnd < asOf`
(`CommitmentMatcher.java:297`). Statements lag by weeks, so between ingests every window that
closed in the gap is `missed`, counts as arrears, and raises `COMMITMENT_ARREARS` — for bills that
were paid.

### 6.2 The fix

- **A commitment's frontier** = the latest posted frontier (already computed in
  `Derive.commitments()`, `Derive.java:911`) over its accounts. Its accounts are, in order: the
  `accountRef`s of its rules when every rule names one; else the accounts of the facts it has
  claimed (from `commitment_fact`); else none.
- **Closed** iff `windowEnd < min(asOfDate, commitmentFrontier)`. With no accounts, fall back to
  `asOfDate` (nothing is known, so nothing can be awaited).
- **Status** (D-B recommended): `windowEnd < asOfDate` but not `< commitmentFrontier` → **`awaiting`**.
  `awaiting` is never a hole: not arrears, not `lapsed`, no `COMMITMENT_ARREARS`. It becomes
  `occurred` or `missed` once the statement lands. (Alternative: keep `due`. Rejected because
  `due` says "the window is open", which is false and hides that data is missing.)
- Pass the frontier map into `Commitments.match`; no new table, a new `OccurrenceStatus` value.
- **UI.** Expected shows `awaiting` grey with "waiting for <account> statement (through <frontier>)",
  linking to the Jobs frontier strip. This is the honest message the goal needs.

### 6.2a As built (2026-10-09)

- `CommitmentMatcher.await` runs after attachment, before arrears: an unmatched `MISSED` slot whose
  window ends on or after the commitment frontier becomes `AWAITING`. Arrears, `lapsed` and
  `COMMITMENT_ARREARS` read only `MISSED`, so they follow without change. Every current row (noop
  included) moves an account's frontier. `derive/14`.
- The newest of a commitment's accounts decides (`theNewestOfACommitmentsAccountsDecides`), so a
  bill that moved off a closed card is not held awaiting forever.
- Three older matcher tests assumed `missed` from `asOf` alone; each now carries a row showing the
  statement reached the window, which is the premise they meant.
- **Measured** on the private `Final/` journal with Netflix, AWS and NIB declared, at `asOf`
  2026-10-31 (statements through ~2026-10-01): the three October occurrences are `awaiting`; on
  master they were `missed`, with 3 false `COMMITMENT_ARREARS` items.
- The Expected UI shows `⧗ awaiting statement` (muted) with a hint to fetch it from Jobs.

### 6.3 Tests

- `CommitmentMatchTest.aWindowClosedBeyondTheFrontierIsAwaitingNotMissed`
- `CommitmentMatchTest.awaitingBecomesMissedOnceTheFrontierPassesTheWindow`
- `CommitmentMatchTest.awaitingBecomesOccurredWhenTheStatementLands`
- `DeriveTest.awaitingRaisesNoArrears`
- Fixture: record `missed` and `COMMITMENT_ARREARS` before/after against Stage 0.

`deriveVersion` → `derive/14` (or fold into `derive/13` if it ships in the same PR). Bump
`hashVersion` only if occurrence status feeds a state hash (check `StateHash` inputs).
`V2-MANUAL-ARREARS-PLAN.md` and spec §6 gain the `awaiting` rule.

---

## 7. Stage 4 — the `asOf` contract (N2, M3)

Proposal §9.1/§15.17 promise that a later `asOf` moves only stale/age statuses. That is no longer
true, and should not be: commitments are time-relative by nature. Replace the promise with a list.

**Outputs that may depend on `asOf`** (everything else must not):

- `pending` status (`OPEN`/`STALE`);
- `UNMATCHED_LEG` items (M3);
- `commitment_occurrence` (generation span `asOf − 12 months` … `asOf + 92 days`, and `due` /
  `awaiting` / `missed`), `commitment` arrears and `lapsed`;
- the commitment review items (`DORMANT_COMMITMENT`, `COMMITMENT_ARREARS`);
- facts dated after `asOf` (excluded from matching).

**Test.** `DeriveTest.laterAsOfMovesOnlyTimeRelativeOutputs`: derive one log at `asOf` and
`asOf + 45 days`; assert every table outside the list is byte-identical, and that the listed ones are
the only ones allowed to differ. A future stage that adds an `asOf` dependency has to add it to the
list in the same PR.

Docs only otherwise; no version bump.

**As built (2026-10-09).** The test passes on master as is — no derive change was needed. It is
not vacuous: the fixture's shaped `HELD` leg raises `UNMATCHED_LEG` only at the later `asOf`.
Spec §6 carries the list; proposal guarantee 17 and the §8 reflow table point at it.

---

## 8. Stage 5 — the sequencer picks up config edits (M1)

### 8.1 The defect

`SequencerService.java:60` builds the `Sequencer` once with the registry, the category `RuleSet` and
the sources. The hub reloads on file change (`IndexRefresher`). `PUT /api/config/categories` adds a
category, the hub's precheck passes, and the sequencer answers 400 until restarted; a new account or
user behaves the same.

### 8.2 The fix

- The sequencer holds its validation config as one immutable record (`registry`, `categories`,
  `sources`) in a single field, swapped whole.
- At the start of each write (inside the writer lock), compare a cheap fingerprint of the config
  files it reads (name, size, mtime). On change, `ConfigLoader.load` + `SourceRegistry.load`; on
  success swap; on failure keep the last good config and log the error. No watcher thread, and a
  batch is always validated against one consistent config.
- `GET /head` reports the sequencer's `configRevision`; `/api/status` shows a warning when it differs
  from the hub's.

### 8.3 Tests

- `SequencerTest.aCategoryAddedOnDiskIsAcceptedWithoutRestart`
- `SequencerTest.aNewAccountOnDiskIsAcceptedWithoutRestart`
- `SequencerTest.aBrokenConfigKeepsTheLastGood`

Also drop `transfers.yaml` from the sequencer's config list in proposal §5.3 (the writer never reads
it), and give category names one home: `categories.yaml` (spec §12), with `refdata.yaml` the
optional override — the proposal text follows.

---

## 9. Stage 6 — drop-folder ingest (goal)

### 9.1 The gap

The runner has a staging inbox and a schedule, but nothing ingests a file just because it arrived:
a person picks it in the UI. For "statements fall in and it churns", each manual step is where the
habit breaks.

### 9.2 The design

A new catalogue job, **`ingest-inbox`**: the existing `ingest` job with its items resolved
automatically. No new ingest logic.

1. List the staging inbox (top level only, regular files, sorted by name).
2. Resolve each against `statements.yaml` (first match wins). Unmatched files stay where they are
   and are listed as "needs a type".
3. Run the existing `ingest` step per matched file, with `--source-archive` and `--source-name`.
4. On exit `0` move the file to `staging/done/<YYYY-MM-DD>/`; on exit `1` (bad rows) or `3`
   (rejected) move it to `staging/failed/` and keep the output; on `2` (transport) leave it for the
   next sweep.
5. Idempotent by construction: a crash between ingest and move re-ingests the file next time, and
   every row is a `Duplicate`.

**Trigger.** A `schedule.yaml` entry (`every: 1h`), plus the existing manual button. A file-watch
trigger can come later if an hour feels slow; the interval scheduler already exists.

**Getting files into the inbox** is deployment: mount the staging volume where you save downloads
(Syncthing, SMB share, or `scp`). Document one recommended path in `docs/DEPLOYMENTS.md`.

**`statements.yaml` patterns.** The current entries are exact names with dates in them
(`BW_20240101_20261001.csv`, `CBA_SmartAccess_20241002_20261002.pdf`), so the next download will not
match. Change to globs (`BW_*.csv`, `CBA_SmartAccess_*.pdf`) and check the match order.

**Spec amendment.** Spec §16 says a job is "an invocation of an existing subcommand — never new
logic". `ingest-inbox` is the `ingest` subcommand with items resolved from the map and files moved
afterwards; state that as the one allowed composition.

### 9.3 Tests

- `JobCatalogueTest.inboxSweepIngestsMatchedFilesAndMovesThemToDone`
- `JobCatalogueTest.inboxSweepLeavesUnmatchedFiles`
- `JobCatalogueTest.inboxSweepMovesABadRowFileToFailed`
- `JobCatalogueTest.inboxSweepLeavesAFileOnTransportFailure`
- `ScheduleTest` accepts an `ingest-inbox` entry.
- Jobs UI: an inbox strip (waiting / needs a type / failed), feeding Stage 8.

---

## 10. Stage 7 — "left this month" (goal; gate D-D)

### 10.1 What it is, and what it is not

One figure on Expected that answers "am I okay this month?". It is a **read** over tables that
already exist — `commitment_occurrence`, `commitment_fact`, `txn_current` — not a budget: no
envelopes, no plan for money that has not moved, nothing stored. That keeps it outside the
2026-10-08 parking decision's concern, which is why D-D asks rather than assumes.

### 10.2 Definition

For the calendar month containing `asOf`, over accounts with `budget: true` (a new
`accounts.yaml` flag, default `true`; set `false` for savings, offset and loan accounts):

```
left = income received (unclaimed + claimed, this month)
     + income still expected (in occurrences `due`/`awaiting`)
     − committed out paid (out occurrences `occurred`/`settled`)
     − committed out still due (out occurrences `due`/`awaiting`, at expected amount)
     − uncommitted spend so far (transaction-role outflows not claimed by a commitment)
```

- Matched and attached transfer legs are excluded, so a card repayment is never counted twice.
- `noop` rows are excluded (they are not postings).
- `missed` occurrences are shown separately ("not seen yet: $X"), never silently added or dropped.
- The figure carries its freshness: "through <oldest frontier of the budget accounts>". With
  Stage 3 this is honest even when a statement is late.

### 10.3 Changes and tests

- `ExpectedResponse` gains `Headroom(long left, long incomeIn, long incomeDue, long committedPaid,
  long committedDue, long uncommittedSpend, long missed, LocalDate through)`; computed in
  `HubQueries` for `window=month`.
- `ExpectedApiTest.leftThisMonthNetsIncomeCommitmentsAndSpend`
- `ExpectedApiTest.leftThisMonthIgnoresTransferLegsAndNoop`
- `ExpectedApiTest.leftThisMonthReportsItsFrontier`
- No `deriveVersion` change (a read). `accounts.yaml` moves `configRevision`, which only re-derives.

---

## 11. Stage 8 — the daily "all clear" (goal)

A finish line for the daily routine: Expected → left this month → clear review → done.

- `/api/status` gains `attention`: open (undismissed) review items by kind, inbox `needsType` and
  `failed` counts, and the oldest frontier age per budget account.
- The header shows a count badge; at zero it says **All clear — through <date>**.
- Review mode with an empty queue shows the same state instead of an empty table.
- `Dismiss` stays one click (the reason is optional). No new decision kinds.
- Tests: `StatusApiTest.attentionCountsOpenItemsAndInbox`, `StatusApiTest.allClearWhenNothingIsOpen`.

---

## 12. Stage 9 — spec and document fixes

Gated on **D-C**. If `V2-SPEC.md` becomes authoritative, edit it and freeze the proposal; otherwise
write each item back into the proposal and keep the spec descriptive.

| Item | Change |
|---|---|
| N4 | `RESTATEMENT`: "a different reading of the **description**", in spec §6 and proposal ~line 1652. |
| N3 | A constants table: every commitment threshold (3 occurrences, 20%, 0.7, 5%/50¢, 2× outlier, `±min(cadence/2, 7)`, 12 months, 92 days), its code home, and the rule "a code constant; changing it bumps `deriveVersion`". Add the fortnightly boundary tie rule (earliest due date wins). |
| N5 | Split spec §6 commitments into sub-sections, with one status table: status, trigger, clock (`asOf` or frontier), review kind. |
| N6 | One table, per review kind: subject, what silences a `DISMISS`, what re-opens it. State the cluster rule (subject = smallest member id; a membership change re-opens), closing prior-review L7. |
| N8 | Spec §9 endpoints as a table (method, path, purpose, plan doc); move UI-mode detail to the plan docs. |
| D2 | Add to the spec: the dedup key (with `observation`, Stage 2), the per-row outcome table, the review-item predicates (with Stage 2's), the `stateHash` inputs. |
| D-E | Relabel `SETTLE_OCCURRENCE` in the UI ("Mark paid"; status "paid by hand"); wire names unchanged. |
| Prior D1 list | Retire the migration text (§15.5, §16), the P0–P5 roadmap (§17/§18), the §12.6 cross-reference, the four-mode UI list, the period-stale wording, the `derive/2` example, the pending sign/similarity (prior M4), and the §11.6 high-water resume (prior M6). |
| `AGENTS.md` | If D-C: the authority section names `V2-SPEC.md`. Update the stale memory note that still names v1's `SPEC.md`/`DECISIONS.md`. |

---

## 13. Stage 10 — feed cursors in the log (M5; gate D-F; before the first feed)

`source_cursor` lives only in the index (`HubService.putCursors`; `CursorStore` javadoc), so
`rm index/` loses every cursor — against invariant 1. No feed exists yet, so this waits, but it must
land before the first one.

- `trex.ingest` `complete` gains an optional `cursor` (string): "this run fetched up to here" is
  what an ingest did, so it belongs in an ingest event.
- `source_cursor` becomes derived: the latest `complete` event's cursor per source.
- `POST /api/cursors` is removed; `FeedRunner` writes the cursor through its `complete` event.
- Check the codec's rule for an unknown *field* on a known kind at `v = 1`; if an older reader would
  refuse it, this is a `v` bump and the spec says so.
- Tests: `IndexerTest.cursorSurvivesRebuild`, `FeedRunnerTest.cursorRidesTheCompleteEvent`.

---

## 14. Not in this plan

Prior-review items not needed for the goal, left for when the code is touched: L1–L6, L8 (doc and
latent issues), and the remaining prior M-items already covered above (M3 → Stage 4, M4/M6 → Stage 9).
Security stays out of scope.
