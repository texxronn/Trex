# V2-BUDGET-VIEW-PLAN.md

> **Personal project, single operator, private.** `V2-SPEC.md` is the specification; this file is the
> build order for one change to it. Where this file and the spec disagree, the spec wins; the spec is
> amended first (Stage 1), per `AGENTS.md`.

**Status:** Stages 1–3 landed (spec + plan, the `/api/budget` read, the view); Stage 4 (verify on
Dev1) remaining. The Budget mode existed (§9.3, `V2-REVIEW-FIXES-PLAN.md` §10) and
showed one figure; this file expands it into the month's sheet — Money In, Money Out, Money Left Over
and a Money Out donut. **D-6 (2026-10-16)** then rewrote the arithmetic it presents: Money In and
Money Out are the month's **real flows over every account** (whatever the `budget` flag), with the
moves between your own accounts carried on a **Moved** bridge that is neither income nor spend.

**Decisions (operator, 2026-10-16).**
- **D-1** The Money Out donut's slices are the **primary groups** the ezbookkeeping view already uses
  (Living, Bills & Subscriptions, Travel, …), not the raw trex categories, so the Budget donut and the
  ezbookkeeping charts agree.
- **D-2** Money moved to one of your own accounts outside the budget (savings, investments, the loan
  offset) shows as a **distinct, differently-styled "Moved out" slice** — named for what it is, a
  move and not spending — carrying the **destination accounts** it went to, so the donut sums to
  Money Out. Internal transfers are still skipped entirely. *(superseded by D-6: moves leave the
  donut for a Moved bridge.)*
- **D-3** Loan/mortgage repayments (offset → loan; **both** accounts are outside the budget, so
  headroom never sees them) do **not** enter Money In/Out/Left; the view carries a single muted
  **"outside the budget"** section: the repayments aggregated per pair, and those accounts' own rows
  (the loan interest, income into a savings account). The Mortgage tab owns the detail. *(amended by
  D-6: those accounts' own rows are ordinary Money In/Out now.)*
- **D-4** The **current** month is posted **plus still-due** (headroom's projection, unchanged); a
  **past** month is pure actuals — its `due`/`awaiting` occurrences have become `missed` by then.
- **D-5** The roll-up map stays in `ezb.yaml`, and the hub reads the same map for the donut, so the
  sheet and the charts slice a category the same way; §12's "read by `trex egress ezb`" is amended to
  name the Budget roll-up. *(operator, 2026-10-16: accepted over a shared `groups.yaml`.)*
- **D-6** Money In and Money Out are the month's **real flows**: every account's **external** rows
  (a row with no contra leg among your own accounts) plus every commitment's occurrence, **whatever
  the account's `budget` flag** — so the salary into `cba-netsaver` (Mel's, and the operator's on
  `ing-salary`), the mortgage interest on `ing-mortgage-simplifier`, and the `holy-family-oosh`
  debits all count. A **move** between two of your own accounts is **never** income or spend: it
  rides a **Moved** bridge (`moved.out`/`moved.in` crossed the budget boundary; `moved.outside` is a
  move between two non-budget accounts, reported per pair with a count) and moves `left` only across
  the boundary. `budget: false` now means only *this account's moves sit outside*. A fact a
  commitment claims counts through its occurrence only when attached to one — a claimed but
  unattached fact (the OOSH case) is ordinary spend — and a commitment whose facts are all transfers
  (a loan repayment) is a move. **The donut is real spending only**: the old `Moved out` slice left
  `spend` and `outsideExternal` is gone, the loan interest now an ordinary Money Out row.
  *(operator, 2026-10-16, verified against the live journal: "Transfers should not be added";
  "Salary needs to be both mine & Mels (in cba-netsaver)"; "Mortgage interest needs to [be] in Money
  Out"; "include all commitments in the money out".)*
- **D-7** The sheet takes one operator option, **off by default**, to count the month's **loan
  principal repayments** as Money Out. A principal repayment is a transfer whose **paying** leg's
  contra account is one of the **declared loan accounts** (every facility's `loans[].account` in
  `mortgages.yaml`); `?principal=include` turns them into a **`Loan principal`** slice and lowers
  `left`, so the sheet's `left` is then below the status strip's `headroom.left` by exactly the
  principal — the strip never counts it. Off, they stay what they are today: a move between the
  operator's own accounts (the offset → loan transfers, in `moved.outside`). The month's principal is
  reported either way. *(operator, 2026-10-16: "Add a checkbox for Money Out (Includes Principal
  Repayments?) — in which case adds Principal repayments as money out".)*

**Authority:** `V2-SPEC.md` §9.2 (headroom), §9.3 (the UI modes), §12 (config), §11.2 (the ezb
roll-up map); `V2-REVIEW-FIXES-PLAN.md` §10; `AGENTS.md`.

---

## 1. The problem

The Budget mode renders **one line**: "Left in <month>", with the arithmetic in muted text beside it
(`budget.js:54`). The operator's reference (§8) is a **monthly budget sheet**: a Money In table, a
Money Out table, a Money Left Over bar, and a donut of Money Out by category, sliding month by month.

Almost all of the arithmetic already exists. `HubQueries.headroom()` (§9.2) computes, over the
`budget` accounts, of one calendar month: income in and due, commitments paid and due, other spend,
moved in and out, unrecorded cash, `missed`, `unpaired`, and `left`. It is a read of the derived
tables — nothing planned, nothing stored. What the sheet needs beyond it is **one new arithmetic**
(roll the expense rows up into the groups) and a **view**.

## 2. The sheet's arithmetic (revised by D-6; every line is a field headroom already has)

Income and spend are the month's **real flows** over **every** account — the external rows plus the
commitments' occurrences, whatever the account's `budget` flag:

| sheet | = headroom |
|---|---|
| **Money In** | `incomeIn` + `incomeDue` (holds no move) |
| **Money Out** | `committedPaid` + `committedDue` + `uncommittedSpend` + `unrecordedCash` (holds no move) |
| **Moved** | `movedIn` (in), `movedOut` (out), `net` = `movedIn` − `movedOut`; never income or spend |
| **Money Left Over** | `left` = Money In − Money Out + Moved net |

A **move** between two of your own accounts changes `left` only where it crosses into or out of a
`budget` account; between two non-budget accounts it changes nothing. The existing rules carry over
with D-6: a commitment counts through its occurrence, never twice as spend; a commitment whose facts
are all transfers (a loan repayment) is a move; a fact claimed by a commitment but attached to no
occurrence counts as ordinary spend; `missed` and `unpaired` are shown **apart**, never folded in.
The sheet must reconcile with the figure the status line already prints, so the two share one read
(§3) — the same discipline that keeps Expected and the since-cleared line from drifting
(`HubService.java:381`).

## 3. The one new arithmetic: the roll-up

`txn_current` carries `category`, and the headroom walk already reads every row the sheet needs. The
new part is to **also group the expense rows by their primary group** and to **count the moves that
never touch the budget**.

- **Expense rows → primary groups (D-1).** A row's trex category is mapped to a primary by the
  `categories:` map the ezbookkeeping view already uses (`ezb.yaml`, §11.2/D-5); an expense category
  with no entry falls back to one `Expense` bucket. Income rows are **not** grouped — there are only
  four income categories, so the Money In table lists them directly. A commitment's row is grouped by
  its category too, so a committed bill and a direct purchase in the same group land in one slice.
- **Moves → a Moved bridge, not a slice (D-2, amended by D-6).** `movedOut`/`movedIn` are money that
  crossed the budget boundary between your own accounts; nothing about them is income or spend, so
  they ride a bridge that shows the boundary-crossing moves (with the account on the other side of
  each, as parts) and their net.
- **Outside the budget (D-3, amended by D-6).** The walk now reads **every** account's rows, so the
  moves between two non-budget accounts (the offset → loan repayment) are reported **once per
  transfer** (the paying leg), which the sheet **aggregates per pair with a count**, and the outside
  accounts' own rows (the loan interest, a salary into a savings account) are ordinary Money In/Out
  now rather than a separate list. The moves sit in a muted section, never in In/Out; only the
  boundary-crossing net moves `left`.

## 4. The read

One new endpoint, `GET /api/budget?month=YYYY-MM` (default: the hub's current month):

```
{ month, through, accounts[],
  income: { posted, due, total, lines: [ { category, amount } ] },
  spend:  { posted, due, unrecorded, total,
            slices: [ { group, amount, parts: [ { label, amount } ] } ] },
  moved:  { out, in, net,
            outParts: [ { label, amount } ], inParts: [ { label, amount } ],
            outside: [ { from, to, amount, count } ] },
  principal: { total, parts: [ { label, amount } ] },
  missed, unpaired, left }
```

- `income.total = posted + due`; `spend.total = posted + due + unrecorded`. **Neither holds a move**
  (D-6): income and spend are every account's external rows plus the commitments' occurrences.
- `income.lines` is the posted income per category; `income.due` is shown by the view as its own row.
- `spend.slices` is the posted spend rolled into its primary group, then the synthetic `Due`
  (committed-due) and `Unrecorded` slices, each only when non-zero. A group slice's `parts` are the
  trex categories inside it. The group slices alone sum to `spend.posted`; every slice sums to
  `spend.total`. The donut is real spending only — the old `Moved out` slice is gone.
- `moved` is the move bridge (D-6): `out`/`in` crossed the budget boundary (each with the account on
  the other side as `outParts`/`inParts`), `net = in − out`, and `outside` is the walk's moves between
  two **non-budget** accounts, one entry per pair — `amount` is the summed magnitude and `count` the
  payments behind it. **No part of `moved` enters Money In or Money Out**; only `net` moves `left`.
- `budget: false` now means only *this account's moves sit outside*; the account's own external rows
  count like any other. `outsideExternal` is gone: the loan interest is ordinary Money Out, the
  salary and interest ordinary Money In.
- `principal` (D-7) is the month's loan principal repayments — transfers paying a declared loan
  account (`mortgages.yaml` `loans[].account`) — **always present**: `total` and `parts` (the loan
  accounts, amount descending). The endpoint takes `?principal=include` (anything else, or absent, is
  off); with it **on** the walk skips those rows as moves, a **`Loan principal`** slice joins
  `spend.slices` **after the groups and before `Due`**, `spend.total` includes it
  (`total = posted + due + unrecorded + principal.total`; `posted` is unchanged), and `left` is lower
  than headroom's by exactly `principal.total` — the status strip never counts it. With it **off** a
  repayment stays in `moved.outside` and the sheet's `left` still equals headroom's.

- **Reuse, not a second implementation.** The row-walk is factored out of `HubQueries.headroom()`
  into one helper that both call; `headroom`'s fields keep their names (only the values change per
  D-6, plus D-7's principal which headroom never sets) and the sheet's `left` equals headroom's, which
  a test asserts on the same month.
- `month` may be any `YYYY-MM`; the read is `asOf`-free (a month is absolute), unlike headroom, which
  measures "the month of `asOf`". `through` is the oldest statement frontier among the budget
  accounts, exactly as headroom reports it.
- The group map is read from `ezb.yaml` (D-5). The hub already reads seeded config by name
  (`HubService.configFile`, `MCP_CONFIG_FILES`), so this adds a mapper, not a new surface.

## 5. The view

`budget.js` grows from one line into the sheet; `app.css` gains the sheet and donut. No library —
the donut is hand-drawn SVG, as `mortgage.js` and `commitment.js` already do (`svgEl`, `dom.js:34`).

- **Toolbar.** A month stepper: `‹  October 2026  ›` and a jump date, exactly Eyeball's month case
  (`eyeball.js`, `boundsOf`/`step`) with the day/week grains removed. The choice lives in the URL
  (`#budget?month=YYYY-MM`), so a month is linkable and survives a reload.
- **Money In.** A small table: one row per income category (posted), then a `Due` line when
  `income.due` is non-zero, then the total — all real flows over every account (D-6), holding no move.
- **Money Out.** A table with one row per slice — the primary groups, then the synthetic `Due` and
  `Unrecorded` rows — then the total; real spending only (D-6), holding no move. A **group row opens
  into its parts** (the trex categories inside it), so the compact total can be read first and the
  distribution only when wanted; the `Due`/`Unrecorded` rows are muted and do not open. `missed` and
  `unpaired` ride as muted notes, as they do today. The header carries the operator's **"Include
  principal repayments"** checkbox (D-7) with the month's principal beside it, **hidden entirely when
  `principal.total` is 0**: on, the `Loan principal` row joins the table (opening into its loan
  accounts, rendered as account chips like the moved parts) and lowers Left; off, it is a move in the
  section below. The choice lives in the URL (`#budget?month=YYYY-MM&principal=include`), read on
  mount, preserved by the stepper and rewritten with `history.replaceState`.
- **The Moved bridge (D-6).** Under the two tables: the boundary-crossing moves `out` and `in`, each
  opening into the accounts on the other side (render as account chips), and the net. A sentence says
  the bridge is neither income nor spend but moves Left.
- **Money Left Over.** The bar the image ends on: `left` = In − Out + Moved net, red when negative.
- **The donut.** One arc per slice (the groups plus the synthetic `Due`/`Unrecorded`, styled apart),
  the Money Out total whole-dollar in the centre, and a **compact boxed legend table** (name left,
  amount and share right, a thin dark rule around it and between the rows). The palette is the
  reference sheet's (magenta, blue, green, amber, red, grey). Slices below a threshold fold into
  "Other" so a long tail cannot shred the ring.
- **Outside the budget.** The muted D-3 section under the tables: the aggregated moves between two
  non-budget accounts, one per pair — "ING Loan Offset → ING Mortgage Simplifier", the amount, and
  "N payments" when there is more than one. A sentence says why none of it is in the figures (D-6:
  the outside accounts' own rows are ordinary Money In/Out now, so they are no longer listed here).
- **Empty.** A month with nothing reads as an empty sheet, not an error.

## 6. Stages

### Stage 1 — spec and plan (no code)

This file, plus the `V2-SPEC.md` edits in the same PR: a `GET /api/budget` row in the §9 table; §9.2
gains the sheet (the same fields, now presented as Money In / Money Out / Left, and the rule that the
two reads must agree); §9.3's **Budget** row is rewritten and its Detail points here; §12's `ezb.yaml`
sentence is amended for D-5; §11.2's category bullet notes that the same map drives the Budget donut.
If D-5 is refused, the swap to a shared `groups.yaml` is folded in here, before any code. **D-6
(2026-10-16)** amends the model again: Money In/Out are the month's real flows over every account,
with the moves between your own accounts on a bridge (see the Decisions block and §9.2).

### Stage 2 — the read (`trex-v2-hub`)

Factor the headroom walk into `HubQueries.budgetSheet(month)` (or a shared private helper both call);
add `BudgetResponse`; add `GET /api/budget` to `HubApi`/`HubService`; a mapper from the `ezb.yaml`
`categories:` map. Tests: the sheet reconciles with `headroom` for the same month; a group roll-up
that lands rows in the right slice; an internal transfer is absent; a savings move is the `moved.out`
bridge line with its destination parts; an outside-outside transfer appears once in `moved.outside`
per transfer and the sheet aggregates it by pair with a count; a `budget: false` account's external
row counts in In/Out and moves `left` (D-6); a claimed-but-unattached fact (the OOSH case) is Money
Out; a transfer-only commitment adds no spend; `missed`/`unpaired` are apart from `left`.

### Stage 3 — the view (`budget.js`, `app.css`)

The month stepper and the URL query; the two tables, the Moved bridge, the Left bar, the donut and
the legend; the outside-the-budget section; a `dom.js`-built SVG donut with the fold-to-Other rule.
The existing single line stays as the header of the sheet (it is the same figure).

### Stage 4 — verify on Dev1

Plan/verify against the real dev journal: a month with heavy transfers (the offset) to confirm the
internal moves are skipped, the boundary-crossing moves sit on the bridge and the outside-outside
repayments appear only in the muted section; a month with a missing statement to confirm
`missed`/`through` read as a late statement, not as money; a past month to confirm it is actuals; and
a month with the mortgage interest, both salaries and the OOSH debits to confirm they are real flows
now (D-6). Rendered against the running hub.

## 7. Verification

- `mvn -q verify` green at each stage (the hub tests are the gate; the view has no test harness, so
  Stage 3 is checked by eye on Dev1).
- The sheet's `left` **equals** `/api/expected`'s `headroom.left` for the same month — the assertion
  that keeps the new read honest.
- Dev1: the donut's slices sum to Money Out (real spending only, D-6); the Moved bridge's `net` is
  the only move that touches `left`; the outside-the-budget moves name the real offset → loan pairs
  and change nothing in In/Out/Left.

## 8. Out of scope

Budgeting as a **plan** (limits, targets, envelopes — still out, §11.2); weekly/daily windows (the
operator wants calendar months only, D-1's grain); editing from the sheet; a second currency; any
change to `derive()`, the log or the ezbookkeeping view (the sheet is a read of the index).

## 9. Decision record

- **D-1** donut slices = the primary groups (`ezb.yaml`'s `categories:` map), so the Budget donut and
  the ezbookkeeping charts agree.
- **D-2** moves to your own non-budget accounts = a distinct "Moved out" slice, labelled as a move
  and carrying the destination accounts, so the donut sums to Money Out without calling a move
  spending. *(superseded by D-6: moves leave the donut for a Moved bridge.)*
- **D-3** loan repayments (both legs outside the budget) = a muted "outside the budget" section,
  aggregated per pair; never in In/Out/Left; the Mortgage tab owns the detail. *(amended by D-6: the
  outside accounts' own external rows are ordinary Money In/Out now, not a separate list.)*
- **D-4** current month = posted + due (headroom's projection); a past month = actuals, because its
  `due` has become `missed`.
- **D-5** the roll-up map stays in `ezb.yaml`, read by the hub too; §12's sentence is amended.
  *(operator, 2026-10-16: accepted over a shared `groups.yaml`.)*
- **D-6** Money In/Out = the month's **real flows** over every account (external rows + commitments,
  whatever the `budget` flag); a move between your own accounts is never income or spend and changes
  `left` only across the budget boundary; a claimed but unattached fact counts as spend; a
  transfer-only commitment is a move; the donut is real spending only.
  *(operator, 2026-10-16, verified against the live journal.)*
- **D-7** Money Out gains an **off-by-default operator option** to count the month's loan principal
  repayments (a transfer paying a declared `mortgages.yaml` loan account) as Money Out: on, a `Loan
  principal` slice and a `left` below the status strip's headroom by exactly that principal (the strip
  never counts it); off, they stay a move between the operator's own accounts. The month's principal
  is reported either way. *(operator, 2026-10-16: "Add a checkbox for Money Out".)*
