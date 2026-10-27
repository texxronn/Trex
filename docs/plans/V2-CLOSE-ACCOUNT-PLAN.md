# V2-CLOSE-ACCOUNT-PLAN.md

> **Status:** built (stages 1–5); `V2-SPEC.md` is the specification; this file is the build order
> for one change to it. Where this file and the spec disagree, the spec wins, and the spec is
> amended in the same change (§7). Build note: `settles` was approved before stage 1, and stage 2
> found that a closed chain must **not** be fed as an extra edge — a loan that opens at 0 and closes
> at 0 cancels the multiset entirely — so a closed account reports its own `CLOSED` status instead.

**Authority:** `V2-SPEC.md` §6.2/§6.3/§6.7, §7, §9.3, §9.5, §12; `AGENTS.md`.
**Adds to the decision set:** one action, `CLOSE_ACCOUNT`.

---

## 1. The problem, in one paragraph

A **statement** account can close while its final statement is unobtainable. ING closed the
fixed-rate loan (`ing-mortgage-simplifier`) on 2026-10-23 and rolled its balance
($327,921.77) onto `ing-variable-rate`; the variable account's statement proves the movement, but
the closed account's own closing row does not exist and never will. Today the account keeps its last
statement balance forever, so the same money is counted twice — once as the closed loan, once inside
the surviving one — and the derived balance is a stale fact, not the truth. The model must let a
person **conclude** that the account closed at a stated balance, without inventing a bank fact.

## 2. Why not a synthetic fact

`AGENTS.md` and `V2-SPEC.md` §6.1: a fact is *what a source said*. A hand-written row would have to
claim a source that never said it. `POST /api/cash` (§9.4) refuses `statement` accounts for the same
reason, and there is no `CLOSE`-shaped action in the set (`Action` is the complete list). The closure
is a **conclusion**, so it belongs in the decision half of the log: attributed, reviewable,
revocable, and never part of the balance chain's evidence.

## 3. The change

### 3.1 Decision: `CLOSE_ACCOUNT` (new)

| field | type | meaning |
|---|---|---|
| `account` | string | the account ref being closed; must be `balanceSource: statement` |
| `date` | `LocalDate` | the day the account closed (the movement date) |
| `balance` | long (cents) | the balance it closed at — `0` for a paid-out loan |
| `settles` | list of ids, optional | the real legs that closed it (here: the rollover on `ing-variable-rate`) |
| `comment` | string, optional | why |

- Attributed (`actor`, `user`) exactly like every decision; the hub stamps the session.
- **Latest effective per account wins**; `REVOKE` is the way back (no `OPEN_ACCOUNT` — the family
  inverse would be a lie about the past).
- `settles` ids resolve through the supersession map, like `ATTACH_ACCOUNT`.
- The sequencer accepts a well-formed decision on an unknown account and never sees the log's
  semantics (unchanged rule, §6.6); derivation surfaces `INEFFECTIVE_DECISION` for a bad one.

### 3.2 Derivation (§6.1, §6.7)

For each closed account, with `last` the newest current fact at or before `date`:

1. **Validate** — the account exists, is `balanceSource: statement`, `date` is on or after `last.date()`,
   and every `settles` id is a current fact on a *different* account. Otherwise the decision is
   ineffective (`CLOSE_ACCOUNT …` reason) and nothing else changes.
2. **Account side** — each `settles` leg pairs **directly** with the closed account, exactly the
   clearing shape (§6.3: "one real leg and an account side, no window, no ambiguity"), with the
   account side's amount the negation of the real leg's. This reuses `pairClearing`; the closed
   account is admitted where a `clearing:`/`declared` target is admitted today, but only when an
   effective `CLOSE_ACCOUNT` names it.
3. **Balance** — the derived closing is the declared `balance`, not `last.balance()`. The closure is
   the account's **terminal movement**:
   `adjustment = balance − last.balance()`, and the attached account sides must sum to it; a mismatch
   is an ineffective decision naming both numbers, never a silent write-off. With no `settles`, the
   adjustment stands alone and is reported (not hidden).
4. **Chain** — reconciliation gets the closure movement like a declared wallet's synthetic leg, so the
   account stays `RECONCILED` and lands on the declared closing; it must never become `BROKEN`.
   `Opening` (the egress anchor) reports `latestBalance = balance`, `latestAt = date`.
5. **Row** — one derived leg, id `cls|<account>|<date>`, `synthetic = 1` in `txn_current`, no evidence,
   no receipt, never a decision target, `leg = MATCHED` when a `settles` pair carries it and `HELD`
   otherwise. It is visible in the ledger and the Chains view — the account's history ends in a
   labelled derived row, not in silence.

### 3.3 What does **not** change

- The account's facts stay: no deletion, no supersession, no re-dating. Its history, categories,
  commitments and past transfers are untouched.
- `role`, `rail`, category, units, the state hash: the closure moves no fact, so no row's hash moves.
- The Cash flow sheet: the closure pair is non-liquid ↔ non-liquid, so C-3 "changes nothing and is not
  reported". The separately-attached liquid→loan repayment ($100K) keeps its own classification.
- `mortgages.yaml` stays as it is until the balance is zeroed; then the loan may be relabelled, never
  removed, because the file is not temporal and removing it restates every past month (see §5).

## 4. Surfaces

| surface | change |
|---|---|
| `Action` / `Decision` / `LogCodec` | the action, its record, encode/decode |
| `Derive` | validation, direct pairing, the closure movement, the derived leg, ineffective reasons |
| `Reconciliation` / `ChainHealth` | the movement that lands the account on its declared closing |
| `Opening` | `latestBalance`/`latestAt` from the closure (egress anchor) |
| index | the synthetic row through the existing `INSERT_TXN_CLEARING` shape; no new table |
| hub | precheck arm; `POST /api/decisions` already carries a draft — no new endpoint |
| web | Chains (or Accounts): **Close account…** on a statement account — date, balance, the settling
leg(s), confirm; the row then renders the closure |
| MCP | none (no new read) |

## 5. Consequence to state in the spec PR

Once the balance is zeroed, the Mortgage view shows the facility at its true debt, and the closed
loan can stay declared at `0` (`"Simplifier (closed)"`) without restating 2022–2026. Removing it from
`mortgages.yaml` instead would move every past month's interest out of Money Out and turn its
repayments into "Set aside" — the reason the plan does **not** remove it.

## 6. Stages (each compiles, each green before the next)

1. **Model** — `Action.CLOSE_ACCOUNT`, `Decision.CloseAccount`, `LogCodec` encode/decode, the
   sequencer's `buildDecision` arm and the hub precheck arm. Tests: codec round-trip, sequencer
   accepts/rejects, precheck.
2. **Derive, balance only** — validation, the closure movement, reconciliation, `Opening`, the derived
   leg with `settles` empty (unpaired, `HELD`). Tests: balance lands on the declared closing; the chain
   stays `RECONCILED`; an ineffective closure changes nothing; `REVOKE` restores the old balance.
3. **Derive, pairing** — `settles`: direct pairing through `pairClearing`, the account side materialised,
   the sum checked against the adjustment. Tests: the rollover pairs; a mismatched `settles` is
   ineffective; a closure pair is "changes nothing" in the Cash flow.
4. **Web** — the close action and its confirm dialog.
5. **Spec + docs** — `V2-SPEC.md` §6.2 (action set), §6.7 (closure), §7 (the derived leg), §9.3
   (Chains/Accounts affordance), `CHANGELOG.md`.

## 7. Open question — resolved

`settles` was approved (it lets the $327,921.77 rollover stop being a permanent `UNMATCHED_LEG`).
The closure is a declaration, not a chain edge: `Reconciliation` reports `CLOSED`, and the account
can never raise `BALANCE_BREAK`.
