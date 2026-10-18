# V2-CASH-PLAN.md

> **Personal project, single operator, private.** `V2-SPEC.md` is the specification; this file is the
> build order for one change to it. Where this file and the spec disagree, the spec wins; the spec is
> amended first (Stage 1), per `AGENTS.md`.

**Status:** built (2026-10-14; Stages 1–5). The wallet pairing, `POST /api/cash`, the derived
`cash_month` table and `/api/cash/months`, the Cash tab and the `Unrecorded cash` line are on
`master`. **Stage 6** (the `CASH_ADJUST` Firefly projection, §7) stays deferred (operator, later).
**Decisions (operator, 2026-10-14):** cash is a wallet account (idea C); the unrecorded figure is
computed in derive, per calendar month, signed; the Firefly projection of that figure is deferred
(Stage 6). D-1…D-7 (§3) accepted as recommended (operator, 2026-10-14).
**Authority:** `V2-SPEC.md` §6.2, §6.3, §6.7, §9 (Expected), §11.1, §12; `V2-PROPOSAL.md` §12.4
(manual cash), §6.10 (clearing); `docs/plans/V2-ATTACH-ACCOUNT-PLAN.md`; `AGENTS.md`.

---

## 1. The problem

ATM withdrawals and cash deposits sit on the bank accounts as EXTERNAL rows. Withdrawals take
`CASH_WITHDRAW` ("at master level the statement cannot say what it bought"); deposits take whatever
the rules give them. Neither says what the cash was for, and there is nowhere to say it.

The operator wants to record **some** cash spending — the ones worth recording — and never be forced
to record all of it. So the cash balance will never be complete, and the design must stay honest
anyway: the monthly budget must not lose the cash that was never recorded, and must not count twice
the cash that was.

## 2. The model

**Cash is a wallet account.** `cash-ron` already exists (`balanceSource: declared`) and is already
mapped in `firefly.yaml` as an asset. A declared account has no statement: its facts are what a
person authors, its balance is anchored only by attestations, and its chain has deliberate gaps.

| Movement | Becomes | How |
|---|---|---|
| ATM withdrawal | `TRANSFER` bank → `cash-ron` | a `transferPatterns` entry with `clearing: cash-ron` (§4.1) |
| Cash deposit | `TRANSFER` `cash-ron` → bank | the same, on the deposit descriptor |
| Deposit of outside money (a gift, a sale) | EXTERNAL income on the bank | an existing `MARK_EXTERNAL` on the leg |
| Recorded cash spend | a manual fact on `cash-ron`, negative | `/api/cash` (§4.3) |
| Recorded cash income (a buyer paid cash) | a manual fact on `cash-ron`, positive | `/api/cash` |
| Wallet count ("I have $120") | an ATTESTATION on `cash-ron` | `/api/cash`; optional, any time |
| Everything never recorded | the month's **unrecorded cash**, signed | derived (§4.4) |

**Unrecorded cash** for one wallet and one calendar month:

```
unrecorded = Σ cash in from the bank      (ATM legs landing on the wallet)
           − Σ cash back to the bank      (deposit legs leaving it)
           − Σ recorded spends            (negative manual facts, as magnitudes)
           + Σ recorded cash income       (positive manual facts)
           − Δ held                       (closing count − opening count; §4.4)
```

| Sign | Meaning | Example |
|---|---|---|
| `> 0` | cash left the wallet and nobody recorded it | the lunches not logged |
| `< 0` | cash appeared that nobody recorded | $50 found in a coat; a buyer paid cash, not recorded |
| `= 0` | everything is accounted for | a fully recorded month |

**Budget.** `cash-ron` becomes a budget account. An ATM withdrawal is then a transfer between two
budget accounts, which "left this month" already skips. Recorded spends and income count by
category. The unrecorded figure counts as its own line, either sign. So the month's total cash cost
is right whether or not anything was recorded — recording only moves money out of the unrecorded
line into real categories.

**Nothing new is stored.** The wallet transfers and the unrecorded figure are derived; the manual
facts and counts are ordinary facts (`V2-PROPOSAL.md` §12.4 — "I spent $40 on vegetables" is an
observation, authored by a person). `trex index --rebuild` reproduces all of it.

---

## 3. Operator decisions

| Id | Question | Recommendation | Why it is a decision |
|---|---|---|---|
| **D-1** | May a `clearing:` pattern target a `declared` account? | **Yes.** The real leg pairs with the wallet exactly as with a clearing account: one real leg, a synthetic `clr\|…` leg on the wallet, no window, no ambiguity. | §6.3 and the derive guard pair only with `clearing` accounts today. |
| **D-2** | May `ATTACH_ACCOUNT` target a `declared` account? | **Yes**, for ATM rows whose descriptor the pattern misses. Same semantics; `REVOKE` releases. | Spec §5 says "the account must be `clearing`" (`Derive.java` refuses otherwise). |
| **D-3** | Default for a cash deposit? | **Transfer from the wallet.** Outside money is a `MARK_EXTERNAL` on the leg (or record the cash income first, so the flow nets). | Sets which side the common case lands on. |
| **D-4** | Δ held before the first count? | **Zero.** The first count only sets the baseline; it never dumps the whole balance into one month as unrecorded. Later months use count-to-count. | Otherwise the first count is a huge false spike. |
| **D-5** | The current (open) month? | **Derived like any other, marked `open: true`**; Expected shows it, live. Nothing downstream treats it as final. | Stage 6 relies on the closed/open line. |
| **D-6** | `CASH_WITHDRAW` once ATMs pair? | **Keep the rule.** A paired leg is not categorised; the rule still names an ATM row the pattern misses, which Review then shows. Revisit after Stage 0 counts. | A category rule change is a reflow. |
| **D-7** | Wallets | **`cash-ron` only now**; every rule is per wallet, so `cash-mel` is a config line later. | Scope. |

---

## 4. The change

### 4.1 Derive: the wallet as a pairing target (D-1, D-2)

- `transfers.yaml` gains a `default` pattern for ATM withdrawals and one for cash deposits, each
  `clearing: cash-ron`. The descriptors come from Stage 0's measurement, not from guesswork; the
  existing `CASH_WITHDRAW` regex (`\batm\b|wdl atm|cash out|cash withdrawal`) is the starting point.
- `pairClearing` already does the work (`Derive.java`, the clearing branch of the pool loop and the
  `ATTACH_ACCOUNT` branch). The change is the account check: `clearing` **or** `declared`.
- The wallet's synthetic leg (`ClearingLeg`) is the counterpart. For a declared account it is
  **not** given a derived balance from a computed opening (that is clearing semantics); its balance
  comes from the declared chain (next point).
- **Reconciliation** of a declared account (§6.7, "reports its gap") must include the synthetic legs
  as movements between counts. Today a declared account's movements are its facts only. This is the
  one real semantic edge in the stage — write the test first.
- Config load refuses a `clearing:` target that is a `statement` account (unchanged meaning, wider
  message).

**Egress effect (stated per `AGENTS.md`).** Every ATM and cash-deposit row changes kind: from an
EXTERNAL withdrawal/deposit (bank → merchant expense, `CASH_WITHDRAW`) to a TRANSFER asset → asset
(bank ↔ "Cash (Ron)"). Per §11.1 a unit that changed kind is an **orphan beside its successor**: the
plan creates the transfers, and the old groups are cleaned with `--remove-orphans`. The count is
Stage 0's ATM + deposit row count. The clearing-unit path is already projected (the `clearing` flag in
`HubClient`); confirm it carries a declared target unchanged.

### 4.2 Budget: the wallet in "left this month"

- `accounts.yaml`: `cash-ron` keeps `budget` at its default (`true`). Withdrawals and deposits
  become internal (both sides budget) and are skipped by `HubQueries.headroom` with no code change.
- Recorded cash facts count like any budget-account posting, by category.
- The month's unrecorded figure (§4.4) is added to the headroom as one line, `Unrecorded cash`,
  signed. A negative month reduces spend.
- `fetchEveryDays` is already "none" for a declared account, so no stale nudge.

### 4.3 Manual entry: `/api/cash` and the Cash tab

The proposal's shape (§12.4), kept exactly:

- `POST /api/cash` (hub) → `POST /facts` (sequencer), source `HUB_0001` (already declared in
  `sources.yaml` for "manual cash").
- A fact: `accountRef` a declared account (refused on `statement` and `clearing`), `date`, signed
  `amount` (spend negative, income positive), `rawDescription`, `receipt` = a client-minted `MAN-…`
  ref (identity by natural key), `sourceType: manual`, `provenance: AUTHORED`, `evidenceId: null`,
  `parser: manual/1`.
- A count: the same, `amount: 0`, `balance` = the counted value → derived as ATTESTATION.
- Corrections are a new fact plus `SUPERSEDE`, or `RETIRE` — never an edit.
- Category: optional on entry, applied as a `PIN` decision on the new fact in the same request.
  A rule may also catch it; the category is never a field of the fact.
- Who: the fact stays person-less; the acting user signs the `PIN` (`actingUser`, as every hub
  write).

**Cash tab** (hub): the wallet's rows — transfers in and out, recorded spends and income, counts —
newest first; a quick-add form (spend / income / count; date defaults to today; amount; description;
optional category); the month's unrecorded figure at the top, per month, with its parts.

**MCP.** A `trex_cash` tool is optional and not in scope; if added later it wraps `/api/cash` and
writes (`AGENTS.md`).

### 4.4 Derive: `cash_month`

A new derived table, one row per (declared account, calendar month) from the wallet's first movement
to the month of `asOf`:

| Column | Meaning |
|---|---|
| `account_ref`, `month` | key (`YYYY-MM`) |
| `in_from_bank`, `out_to_bank` | Σ synthetic legs in / out (magnitudes, cents) |
| `recorded_out`, `recorded_in` | Σ current manual facts (ATTESTATIONs and `noop` excluded) |
| `held_open`, `held_close` | the latest count on or before the day before the month / its last day; null before the first count |
| `held_delta` | `held_close − held_open`; **0** when either is null (D-4) |
| `unrecorded` | the formula of §2 |
| `open` | `asOf` is inside the month (D-5) |

Pure: a function of current facts, transfers and `asOf`; months iterate in order; no clock. A change
here bumps `deriveVersion`. Retired and superseded facts drop out as everywhere else, so a correction
reflows the month.

**Egress effect.** None in this plan's Stages 1–5: `cash_month` is not a unit (Firefly option (a)).
Firefly's "Cash (Ron)" balance therefore drifts by the cumulative unrecorded figure; trex's budget is
right regardless. Stage 6 closes the drift.

---

## 5. Invariant check

| Invariant | Holds because |
|---|---|
| `derive()` pure | `cash_month` is a function of current facts, transfers and the explicit `asOf` |
| The log holds facts, decisions, ingest events | manual entries are facts; categories are `PIN` decisions; nothing else is written |
| The writer never interprets | `/api/cash` validates shape and account kind only |
| Decisions win | `MARK_EXTERNAL` takes a deposit out of the pool before any pattern; `ATTACH_ACCOUNT` before the matcher |
| Nothing deleted | corrections are `SUPERSEDE`/`RETIRE`; Firefly orphans need `--remove-orphans`, explicit |
| Derived tables disposable | `cash_month`, the wallet's synthetic legs and the headroom line rebuild from the log |
| No category on a transaction | entry categories are `PIN` decisions; `UNRECORDED_CASH` (Stage 6) is config |
| No rewriting `externalId` | no id changes; ATM rows keep their ids and change only their pairing |

---

## 6. Stages

Each stage is one PR: failing tests first, then the change, then the doc amendment; the tree green
after every commit.

| Stage | What | Kind | Gate |
|---|---|---|---|
| 0 | **Measure.** Count ATM and cash-deposit rows per account and descriptor in the live index (read only; never touch `run/`); list the descriptors the `CASH_WITHDRAW` regex misses; count the Firefly groups that will orphan. | measure | — |
| 1 | **Spec PR.** §6.3 (declared target), §5 `ATTACH_ACCOUNT` (declared allowed), §6.7 (declared reconciliation includes synthetic legs), §12.4-shaped manual entry into the spec's API table, `cash_month`, the headroom line, and §11.1's egress effect. | spec | D-1…D-7 |
| 2 | **Derive: wallet pairing.** The account check, declared reconciliation with synthetic legs, config-load refusal, the two `transferPatterns` entries. Tests: ATM pairs to wallet; deposit pairs out; `MARK_EXTERNAL` deposit stays EXTERNAL; declared gap includes legs. | derive + config | Stage 1 |
| 3 | **Manual entry.** `POST /api/cash` (spend / income / count), account-kind refusal, optional category `PIN`; Cash tab with quick-add and the wallet's rows. | hub + UI | Stage 2 |
| 4 | **`cash_month`.** The derived table, index view, `/api/cash/months`. Tests: each sign; first count sets baseline only (D-4); open month flag; a `RETIRE` reflows. | derive + index | Stage 2 |
| 5 | **Budget.** The `Unrecorded cash` headroom line; the Cash tab's monthly header. Then the Firefly apply with `--remove-orphans` for the re-kinded ATM groups (operator runs it). | hub + egress run | Stages 3–4 |
| 6 | **Deferred — Firefly option (b).** See §7. | derive + egress | operator, later |

---

## 7. Stage 6 (deferred): project the unrecorded figure to Firefly

Settled now, so the later build is mechanical:

- **A new unit kind, `CASH_ADJUST`**, emitted by derive from a **closed** `cash_month` row
  (`open = false`) with `unrecorded ≠ 0`. Not backed by a fact; the "synthetic" flag is the
  precedent. Never for an open month — the figure would rewrite daily.
- **Id:** `CASH-<accountRef>-<YYYY-MM>-OUT` (positive) or `-IN` (negative). The id names the month
  and sign, never the amount: a backdated entry moves the amount, and §11.1's fingerprint convergence
  rewrites it in place.
- **Type by sign:** `OUT` is a `withdrawal`, wallet → expense account "Unrecorded cash"; `IN` is a
  `deposit`, revenue account "Found cash" → wallet. Category `UNRECORDED_CASH` (declared in
  `categories.yaml`).
- **No $0 posting** (Firefly refuses one): a zero month emits no unit. A sign flip or a drop to zero
  is a changed kind — orphan beside its successor, cleaned by `--remove-orphans`. This avoids relying
  on a Firefly PUT changing a group's type, which is unverified.
- **Config:** the two counter-accounts in `firefly.yaml`.
- **Effect:** Firefly's "Cash (Ron)" balance matches the wallet at each count, and Firefly's spend
  reports include unrecorded cash. No log change and no migration: turning it on is derive, egress
  and config, then plan and apply.

---

## 8. Rollback

Before Stage 5's apply: revert the PRs and `trex index --rebuild`; manual cash facts stay in the log
as facts on a declared account (harmless, and still valid). After the apply: revert, rebuild, and run
the egress again — the ATM transfers orphan back to EXTERNAL withdrawals the same way they came.
