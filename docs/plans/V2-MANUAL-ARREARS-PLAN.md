# V2-MANUAL-ARREARS-PLAN.md

> **Archived 2026-10-09 — built (PRs #29–#30).** The outcome lives in `V2-SPEC.md` (the specification); this
> file is the record of why. Its references to `V2-PROPOSAL.md` as authoritative are history.

> **Personal project, single operator, private.** `V2-PROPOSAL.md` is the specification; this file
> is the build order for one change to it. Where this file and the proposal disagree, the proposal
> wins — until the §4 amendment lands. `V2-COMMITMENTS-PLAN.md` §2.5/§2.9 is the earlier build
> order this file **supersedes**.
>
> **Status:** implemented and proven on the dev stack (Stages 0–3, `derive/10`): the same journal
now derives `safe-custody-monthly-fee` with zero arrears and no dormant prompt, no new decisions.
> **Authority:** `V2-PROPOSAL.md` §6.11, §10.6; `V2-COMMITMENTS-PLAN.md` §2.5, §2.9;
> `V2-EXPECTED-UX-PLAN.md`; `AGENTS.md`.
> **Decision (operator, 2026-10-08):** the arrears/backlog workflow is **manual** — a fact attaches
> to the occurrence whose window contains its date; arrears are the **holes** (windows that closed
> with no fact); clearing a hole is a person's act. The automatic oldest-first allocation and
> `partial`-as-arrears are retired.

---

## 1. The change, in one paragraph

Matching stops interpreting amounts. A rule-matched (or pinned) fact attaches to the occurrence
whose date window contains it; several facts in one window sum; the occurrence is `occurred` at the
observed amount — a price, never a shortfall. A window that closes with no fact is `missed` — a
hole — and the holes are the arrears. Nothing is allocated across occurrences, nothing pre-pays,
nothing goes `partial` automatically. A hole is cleared by a person: `SETTLE_OCCURRENCE` (the
existing Settle dialog already sends a commitment's whole backlog) or by pinning the fact that
paid it. Statements and expectations then cannot invent debt: an expectation error is a price
observation, not arrears.

## 2. Why: the failure that prompted it

`safe-custody-monthly-fee` was confirmed from a candidate whose history was **$32.00 for nine
months, then $37.00 for three**. The declaration carries a single amount ($37) and, as built,
every occurrence expected the declared amount; full coverage tolerates only `max(2%, 50¢)`.
Oldest-first allocation then turned each $32 month into a $5 shortfall and walked the accumulated
**$45** to the tail: 24 Aug `partial` ($8), 24 Sep `missed` ($37) — while **every month had a
matching charge**. The holes were invented by the matcher, not by the money.

The operator's rule, adopted: *arrears exist only where there is no transaction — a hole.* How a
lump maps onto holes is a conclusion, not a derivation.

## 3. Target model

### 3.1 Attachment (replaces allocation)

- **Assignment is unchanged**: a fact belongs to at most one commitment — the pin first, else the
  latest declaration whose rules, sign, materialised span and life admit it.
- **Attachment**: the fact attaches to the occurrence whose window contains its date; where windows
  touch or overlap (fortnightly ± 7 days meets on the boundary day), the **earliest due date**
  whose window contains the fact wins, so the answer is deterministic.
- Several facts in one window **sum**; the occurrence is `occurred`, carrying the last fact's id
  and date and the summed amount. The declared/stepped expectation is not consulted for the status
  and is not a shortfall.
- A fact with **no containing window** (between windows) becomes an `off_schedule` occurrence at
  its own date; same-date facts merge into one row (the occurrence table keys by due date).
- A fact before the first materialised window is history the occurrence set does not hold
  (unchanged `beforeSpan`); a fact after `asOf` is not yet observed (unchanged).

### 3.2 Statuses

- `occurred` (≥ 1 attached fact, any amount), `settled` (a person concluded it), `due` (window
  open), `missed` (window closed, no fact — once the statements have passed it; before that it is
  `awaiting`, amended 2026-10-09 by `V2-REVIEW-FIXES-PLAN.md` §6). `partial` is **retired from automatic output** — the
  value stays in the vocabulary and the UI renders it, but nothing derives it.
- `lapsed` overlays the most recent closed-window occurrence when it is `missed` (older misses are
  the backlog), unchanged in spirit.

### 3.3 Evidence and settles

- `SETTLE_OCCURRENCE` is applied to occurrences with no attached fact: a concluded payment marks
  the occurrence `settled` with the expected amount.
- A fact that later attaches to a settled occurrence **wins on the row** — it becomes `occurred`
  with the fact id and amount; the settle remains in the log (visible, `REVOKE`-able) but the
  derived row carries the evidence. Evidence and conclusion are never conflated; the fact is
  stronger.
- A settle naming an occurrence that already carries a fact is a no-op on the row (the conclusion
  is recorded, the evidence stands).

### 3.4 Arrears and clearing

- **Arrears = `missed` occurrences**: `arrears_count` and `arrears_amount` (the expected amount at
  the missed due dates, from the price point or the declared amount). No partial component.
- **Clearing is manual**: `SETTLE_OCCURRENCE` marks the named due dates settled (one dialog for a
  commitment's whole backlog); pinning a fact that carries the right date window flips that
  occurrence to `occurred`. Nothing clears by itself, nothing is auto-forgiven, nothing is
  auto-retired.
- The **Catch up** panel lists the holes, oldest first, with a running total and the existing
  Settle / Assign gestures. `COMMITMENT_ARREARS` review items keep their shape, over holes only.

### 3.5 Unchanged

Detection and candidates; the price-step cost model from observed amounts; dormancy (last
occurred/settled occurrence vs the accounts' posted frontier); the irregular path (every matching
fact is an occurrence at its own date); `nextDue`; the derived tables' shape; the hub API (statuses
are strings).

## 4. Supersedes / amendments

- **`V2-COMMITMENTS-PLAN.md` §2.9** (allocation, partial, pre-payment) and the §2.5 sentence
  "Matching: a due occurrence takes the nearest unassigned current fact …" — superseded. The
  Stage 2 acceptance items "a lump payment clears three occurrences oldest-first", "a short lump
  leaves the next one partial" and "a surplus pre-pays future occurrences" are deliberately
  reversed.
- **`V2-PROPOSAL.md` §6.11** (Stage 2): the arrears/catch-up paragraph and the occurrence-status
  sentence are amended to this model.
- **The price-step gap** (a `fromCandidate` declaration drops the detected timeline) is no longer a
  correctness issue — attachment ignores the expectation for status — and stays parked as a
  forecast/display improvement in `V2-EXPECTED-UX-PLAN.md` 2b.

## 5. Invariant check

- `derive()` stays pure: same facts, decisions, config and `asOf`, same output; attachment is a
  deterministic scan.
- **No new decision types**: `SETTLE_OCCURRENCE` and `PIN_COMMITMENT` already exist; the log
  grammar, the writer, the sequencer and the API surface are untouched.
- The log is unchanged; every derived table stays disposable; nothing is auto-concluded; no amount
  is interpreted; `statehash/4` unchanged; `deriveVersion` bumps to **`derive/10`**.

## 6. Blast radius

| Area | Files |
|---|---|
| core | `CommitmentMatcher.java` (attach replaces allocate; dead allocation code and `Slot.allocated` removed; arrears over missed), `OccurrenceStatus.java` + `CommitmentArrears.java` javadoc, `DeriveConfig.java` (`derive/10`), `CommitmentMatcher` tests |
| hub | none (the status column is text; no DTO or schema change) — `CommitmentsApiTest` reviewed |
| web | none structural; the Catch up hint copy is adjusted if it still promises automatic clearing |
| docs | this plan; `V2-PROPOSAL.md` §6.11; `V2-SPEC.md`; `CHANGELOG.md`; a supersession note in `V2-COMMITMENTS-PLAN.md` §2.9 |

## 7. Stages

- **Stage 0 — this plan.** Its own docs PR.
- **Stage 1 — core.** Attachment semantics, dead-code removal, `derive/10`, rewritten
  `CommitmentMatchTest` (a lump lands on its own window; older holes stay missed; settle clears;
  a below-expectation charge is `occurred`; an off-window fact is `off_schedule`; no pre-pay);
  `mvn test` green. One PR.
- **Stage 2 — docs as built.** The proposal §6.11 amendment, SPEC, CHANGELOG, the commitments-plan
  supersession note. One PR (or folded into Stage 1's docs commit).
- **Stage 3 — prove it.** Rebuild the dev index (`derive/10`), `trex verify` green, and the real
  journal shows `safe-custody-monthly-fee` with **12 `occurred`, 0 arrears, active, next due
  2026-10-24, current −$37.00** — with no new decisions.

## 8. Acceptance

- On the live journal, the existing declaration derives clean: no holes, no dormant prompt for
  SAFE CUSTODY; the arrears review item disappears.
- A payment after a gap attaches to its own period; the older holes stay visible until settled.
- Settling a backlog clears it in one action; `REVOKE` returns it.
- A lump on one date attaches at its full amount there; nothing is allocated across holes and
  nothing is swallowed (`off_schedule`).
- No occurrence is derived `partial`; the Expected view renders the remaining statuses.
- `trex verify`: rebuild ≡ incremental; reconciliation green.

## 9. Decisions taken (operator, 2026-10-08)

1. **Manual arrears**: automatic allocation and `partial`-as-arrears retired; holes are the backlog.
2. Plan first, then build.
3. Windows touching on a boundary day resolve to the earliest due date.
4. Evidence wins on a row: a fact attaching to a settled occurrence makes it `occurred`; the
   settle remains in the log.
5. `partial` stays in the vocabulary, unemitted.
6. Price-step inheritance for declarations is parked (forecast/display only).

## 10. Rollback

Revert the Stage 1 PR (core + tests). The plan stays on record; nothing in the log changes, and
the index rebuilds either way. The earlier §2.9 semantics return with the revert.
