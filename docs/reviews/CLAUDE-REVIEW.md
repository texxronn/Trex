# V2-SPEC.md review

> **Archived 2026-10-09 — every finding is actioned in `V2-REVIEW-FIXES-PLAN.md` (PRs #47–#56); kept as the record.**

**Reviewed:** `V2-SPEC.md` (465 lines), checked against `V2-PROPOSAL.md` and the `trex-v2-*` code
at `05ac842`, as of 2026-10-09.
**Prior review:** `claude-v2-proposal-spec-review.md` (2026-09-30). This review first re-checks its
findings, then adds new ones (N1–N8).
**Out of scope:** authentication and security.

Severity: **High** = a derived result that is silently wrong; **Medium** = a contract gap or a
latent bug; **Low** = editorial.

---

## Verdict

The core design is sound. Facts and decisions are kept apart, `derive()` is pure, undo is always
appended, and the writer is small. The main problem is the documents, not the code. The spec says
the proposal wins, but large features (commitments, the runner, stream) exist only in the spec and
about a dozen plan files, so one rule now spreads across three or four documents.

---

## Findings from the prior review

| ID | Status | Note |
|---|---|---|
| H2 matcher arbitrary pick | **fixed** | the mutually-unique rule is in the spec and the code |
| H3 T1 amount/date check, `TRF-` collision | **fixed** | `Derive.java:507`, `uniqueTransferId` |
| M7 O(n²) duplicate scan | **fixed** | grouped by `(account, date)`, `Derive.java:1612` |
| **H1** re-observing the same id raises no item | **open** | Proposal lines 563–564 promise `POTENTIAL_DUP`/`RESTATEMENT` for a `Flagged` row. The code compares only *different* current ids, so the older observation disappears without any item. Still the biggest correctness gap. |
| M1 sequencer config is static | **open** | `SequencerService.java:60` loads it once. `PUT /api/config/categories` passes the hub's precheck, then the sequencer answers 400 until it is restarted. |
| M2 dedup key has no `observation` | **open** | `SequencerState.java:35`. A pending row and a posted row that look identical are treated as `Duplicate`. |
| M5 cursors live only in the index | **open** | `rm index` loses them, which breaks invariant 1 (everything but the log is disposable). |
| D1 which document is in charge | **open, and worse** | see below |

---

## New findings

### H0. Rows that share a receipt on one day collapse into one id (High)

Found while planning the fixes (`V2-REVIEW-FIXES-PLAN.md` §4). ING puts one receipt number on
several different transactions of the same day (a purchase, its international fee and the fee
rebate; an annual fee and its transfer). The natural key `nk|account|date|receipt` gives them one
id, the sequencer appends the later rows as `Flagged` re-observations, and derive keeps only the
latest. On the fixture journal: 58 ids, **112 transactions hidden** from every derived view. The log
itself is intact. The fix is an identity-rule change, so it needs an operator decision (D-A).

### N1. Commitments use two different clocks (High)

- `active`/`dormant`/`ended` are measured against the accounts' posted frontier
  (`Commitments.java:138`). That is correct.
- `missed` is measured against `asOf` (`CommitmentMatcher.java:297`:
  `windowEnd.isBefore(asOfDate)`).
- **Failure.** Monthly statements arrive weeks late. Until the next ingest, every window that
  closed in the gap shows `missed`, enters arrears, and raises `COMMITMENT_ARREARS`. The bill was
  paid; the data just hasn't arrived yet.
- **Fix.** A window counts as closed only when the account's frontier is past `windowEnd`. If
  `asOf` is past it but the frontier is not, the status is `due` (or a new `awaiting` status).

### N2. The `asOf` guarantee no longer holds (Medium)

- Proposal §9.1/§15.17 says a later `asOf` changes only stale/age statuses.
- Commitments break this: occurrences are generated from `asOf − 12 months` to `asOf + 92 days`,
  and `missed`, arrears and the commitment review items all shift with `asOf`.
- **Fix.** State in the spec which outputs may depend on `asOf`, and add an invariant test that
  checks exactly that set.

### N3. Unstated tuning numbers (Medium)

- The commitments paragraph hard-codes 3 occurrences, 20%, 0.7 regularity, 5%/50¢, a 2× outlier,
  `± min(cadence/2, 7)`, 12 months and 92 days.
- The spec doesn't say whether these are config (and so feed `configRevision`) or code constants
  (and so need a `deriveVersion` bump). They appear to be code constants, which means retuning one
  requires a version bump. Say so.
- The fortnightly window-boundary tie rule ("earliest due date wins") appears only in a code
  comment (`CommitmentMatcher.java:61`). Add it to the spec.

### N4. The `RESTATEMENT` wording contradicts its own rule (Medium)

- Spec §6 and proposal line 1652 call a restatement "a different reading of the **amount**".
- The rule requires the *same* amount with *different* stems, i.e. a different reading of the text.
- **Fix.** One word: "amount" → "description". The current wording misleads anyone writing a new
  predicate.

### N5. Spec §6 is one block of text (Low)

- The commitments paragraph runs about 35 lines in a single block: detection, coverage, matching,
  status and arrears together.
- Split it into sub-sections with a status table: status | trigger | clock (`asOf` or frontier) |
  review kind. N1 would have been obvious in that table.

### N6. Three different dismissal-aging rules (Low)

- Clusters age by subject (the smallest id), `SUSPECTED_RECURRING` by its stem's newest fact, and
  `DORMANT_COMMITMENT`/`COMMITMENT_ARREARS` by the commitment's matched facts.
- Each is reasonable, but the spec doesn't define "subject" in one place. The prior L7 is still
  open: if a cluster's membership changes, its subject changes and the dismissed item reopens.
- **Fix.** One table of subject and aging per review kind.

### N7. The word "settle" means two things (Low)

- Pending `SETTLE` and the commitment `settled` status (`SETTLE_OCCURRENCE`) are different
  concepts with the same word.
- Consider renaming the commitment one, e.g. `CLEAR_OCCURRENCE` / `cleared`. That is cheap now and
  expensive later. The log's history is permanent, so lines already written keep the old name and
  the codec would have to read both spellings.

### N8. Hub endpoints and UI modes are written as prose (Low)

- Spec §9 lists about 35 endpoints and the eight UI modes in running text. A table
  (path | method | purpose | plan doc) would make drift reviewable.
- The UI-mode detail is UX and belongs in the plan docs, not the spec.

---

## D1: which document is in charge

The "proposal wins" clause in `AGENTS.md` and `V2-SPEC.md` line 6 is no longer true: commitments,
the runner, stream, clearing accounts and `noop` roles exist only in the spec and the plan files.

Recommendation:

1. Make `V2-SPEC.md` authoritative and keep `V2-PROPOSAL.md` as frozen history of intent.
2. Once a plan is built, fold its substance into the spec and archive the plan doc.
3. Add the contracts the spec still lacks (prior D2): the dedup key, the per-row outcome table, the
   review-item predicates, and the `stateHash` inputs.

This changes the authority section of `AGENTS.md`, so it is the operator's decision.

---

## The goal: daily use

The operator's aim (2026-10-09): run trex self-hosted, with bank statements dropped in and the
derivation doing the work, and use it daily for **predictability and balancing monthly budgets**.
The bar is trust and calm: a screen that never raises a false alarm, and a review routine that has a
finish line. The findings above are ranked against that goal.

### Already in place

- **Predictability.** The Expected tab and commitments: what is due today/this week/this month,
  committed totals in and out, arrears.
- **Trust.** Every figure traces to a statement line; nothing is deleted; every override is undone
  by an appended decision.
- **A finishing point.** The review queue has a cleared state, which suits a routine better than an
  open ledger.

### Gaps for daily use

1. **N1 first.** A statement that has not arrived yet shows as `missed` and lands in arrears. A false
   alarm on the main screen costs more trust than any missing feature.
2. **No drop-folder ingest.** The runner has an upload inbox and a schedule, but nothing watches a
   folder (no `WatchService` in the code). "Statements fall in and it churns" needs a watched inbox
   that turns each new file into an `ingest` job via the `statements.yaml` filename → account map,
   or a scheduled job that sweeps the inbox. Every manual step is a place for the habit to break.
3. **Budgets are not built.** "Budget" appears in the proposal and the commitments plan, not in the
   as-built spec. Either use Firefly's budgets over trex's clean data, or add one trex figure:
   **left this month** = expected income − commitments due − spent so far. Recommended: the single
   figure — it answers "am I okay?", and Expected already holds most of its inputs.
4. **Statements are a month behind.** Predictability needs fresh data. Download the CSVs weekly (ING
   and CBA allow date ranges); the Jobs frontier strip already shows what is missing.

### Guardrails

- **A visible "done for today" state.** An empty queue says "all clear"; a non-empty one shows a
  count, not an endless list.
- **Dismiss is cheap.** "Good enough" is one click. An exhaustive queue must not become a compulsion.
- **Rules tuning stays out of the daily path.** The Rules editor and its preview are weekend work.
- **The daily ritual:** open Expected → check "left this month" → clear review → done. About five
  minutes.

### The 90% line

0. H0 fixed — no transactions hidden by a shared receipt.
1. N1 fixed — no false `missed`.
2. Drop-folder auto-ingest.
3. "Left this month" on Expected.
4. H1 fixed — no silent duplicates.
5. A daily "all clear" state.

Then freeze features for a month and only use it. What comes up goes on a list, not into the code.

---

## Suggested order

1. H0, then N1 and H1: each with a regression test named after the failure
   (`V2-REVIEW-FIXES-PLAN.md` has the detail).
2. The rest of the 90% line: drop-folder ingest, "left this month", the "all clear" state.
3. M1 and M2 before the next config edit and the first feed respectively; M5 before the first feed.
4. The authority decision (D1), then one spec pass covering N2–N6, N8 and the missing contracts.
5. N7 and the rest as they are touched.
