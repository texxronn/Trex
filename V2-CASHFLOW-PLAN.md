# V2-CASHFLOW-PLAN.md

The **Budget** tab becomes **Cash flow**: the month's money in and out of the household's **liquid**
accounts, with two views of the mortgage (**Cost** and **Cash**), category exclusion, and a
Primary/Leaf pivot. It supersedes `V2-BUDGET-VIEW-PLAN.md` D-2, D-3, D-6's boundary and D-7.

**Authority:** `V2-SPEC.md` §9.2 (headroom), §9.3 (the UI modes), §12 (config); `AGENTS.md`.
Downstream: the hub reads only — the ezbookkeeping view (`trex egress ezb`) is unaffected. Firefly
is on the backburner and is not considered (operator, 2026-10-16).

## 0. Why — measured on the live journal (2026-10-16, Jun–Sep 2026)

1. **D-7 counts the interest twice.** `principal` is the whole offset → loan repayment, but the loan
   interest is already a Money Out row. Jun–Sep repayments 2,649 / 2,708 / 2,708 / 4,062 against
   interest 1,693 / 1,747 / 1,745 / 1,686: the true principal is ~960/month, the toggle overstates
   Money Out by ~1.7k/month.
2. **The `budget` boundary distorts Left.** D-6 counts income and spend over **every** account and
   then also adds `movedIn`/`movedOut` across the budget boundary. Over the whole set of own accounts
   every move nets to zero, so any boundary term is a double count. The salary → offset sweep is a
   "move out" and the offset → cards a "move in"; a netsaver salary is income and then "moved in"
   again. Aug sheet Left −6,592 against a real +6,938.
3. **A paid commitment counts in its due month, not its payment month.** Sydney Water due 06-28,
   paid 07-01 lands in June, so a month cannot reconcile with the account rows.

With all three fixed, the Cash view's Left for August equals the sum of every row on the liquid
accounts to within $0.01 (one occurrence-vs-fact cent).

## 1. Decisions (operator, 2026-10-16)

- **C-1 Rename.** Budget → **Cash flow** ("old school, conveys exactly what it does"). Mode
  `#cashflow`, `#budget` kept as an alias; API `GET /api/cashflow`; `/api/budget` removed.
- **C-2 Liquid.** Each account carries `liquid` (default **true**); it **replaces** `budget`. A
  declared loan account (`mortgages.yaml` `loans[].account`) and a clearing account are **never**
  liquid, whatever the flag. Kids' accounts (sg-auriel, sg-blythe — clearing today, onboarded later)
  and any investment account are `liquid: false`. A `budget:` key in `accounts.yaml` is a config
  error naming `liquid`.
- **C-3 Flows.**
  - an **external** row on a liquid account is Money In / Money Out (as today, commitments through
    their occurrences);
  - an **external** row on a declared **loan** account (interest, fees) is Money Out, always
    (a credit there is Money In);
  - an external row on any **other non-liquid** account is off the sheet (the kids' own activity);
  - a move **liquid ↔ liquid** is noise: ignored entirely;
  - a move **liquid → loan** is a **repayment**; **loan → liquid** a **redraw** (a negative
    repayment). Per loan: `charges` = the loan's Money Out external rows net of its credits,
    `principal = repayments − charges` (may be negative in an odd month; reported as is);
  - a move **liquid → other non-liquid** (kids, clearing, investments) is **set aside**; back the
    other way it is a negative set-aside;
  - a move between two non-liquid accounts changes nothing and is not reported.
- **C-4 Two views** (the toggle; `view=cost` default, `view=cash`).
  - **Cost:** Money Out = spending incl. loan charges + due + unrecorded. `left = In − Out`. The
    principal ("→ home equity") and set-aside are shown in a bridge, never in Out.
  - **Cash:** the loan charges leave their category groups and, with the principal, form one
    **`Mortgage`** slice equal to the repayments (parts `Interest & fees`, `Principal`); **`Set
    aside`** is a slice (parts: the accounts). The current month projects the declared
    **repayment** commitments' due occurrences (`mortgages.yaml` `repaymentCommitment`) into `Due`
    and skips the declared **interest** commitments' due occurrences (they sit inside the
    repayment). `left = In − Out` = the change in the liquid accounts for a past month.
  - The status strip's `headroom.left` is the **Cost** view's `left`.
- **C-5 Payment month.** A paid occurrence (`occurred`/`settled`/`partial`) counts in the month of
  `COALESCE(matched_date, due_date)`; a `due`/`awaiting`/`missed` one in its `due_date` month. The
  Expected tab's query is not changed.
- **C-6 Reconcile.** The read reports `reconcile.liquidNet` (the signed sum of every non-noop,
  non-synthetic row on the liquid accounts dated in the month), `reconcile.postedLeft` (the Cash
  view's left without any due projection and without unrecorded cash, computed whatever the view)
  and `reconcile.gap = postedLeft − liquidNet`. The UI shows it in the Cash view.
- **C-7 Exclude.** A view filter, never a decision or a log line. Keys name a **leaf** category
  (`FLIGHTS`) or a **primary** group (`Travel`). Defaults from an optional view-config
  `cashflow.yaml` (`exclude: [BIG_TICKET_PURCHASES]`), not hashed into `configRevision`; the URL
  overrides (`exclude` present — even empty — replaces the default). Excluded spending leaves the
  slices and rides `excluded`; `left` is unchanged; `leftExcl = left + excluded.total`. Only posted
  category spending can be excluded (not `Mortgage`, `Set aside`, `Due`, `Unrecorded`).
- **C-8 Pivot.** `Primary | Leaf` on Money Out and the donut, in the URL (`pivot=leaf`), view-only:
  the read already carries each group's leaf parts. In Primary the ⊘ toggle excludes a group, in Leaf
  a leaf.

## Global constraints (every task)

- Java 25 at `~/Tools/JDK/jdk-25.0.4.1+1/` (`export JAVA_HOME=$HOME/Tools/JDK/jdk-25.0.4.1+1`).
  Build/test: `mvn -q -pl trex-v2-hub -am test` (or the module you touched, `-am`).
- Work in the worktree `/home/ron/work/Projects/Java/Trex-cashflow` on branch `feat/cashflow`.
  Small commits; the tree builds and its tests pass after each. Never push, never touch `master`.
- Never touch `/home/ron/work/Projects/Java/Trex/run`, the Docker volumes, or the running stack.
- `AGENTS.md` invariants hold: `derive()` untouched; nothing written to the log; no category is a
  property of a transaction; prefer simple code; comments carry the *why*.
- Match the surrounding code's idiom (records, `TreeMap` for deterministic order, the existing
  comment density). JDK only; no new dependencies.

---

## Task 1: Spec and docs

Docs only, no code. Bring the authority in line with §1 before any code lands.

- `V2-SPEC.md` §9.2: rewrite "Headroom" and "The budget sheet"/"The principal option" paragraphs as
  **the cash-flow sheet** per C-2…C-7 (liquid boundary, flows, Cost/Cash views, payment month,
  reconcile, exclude). Keep it at the spec's density; point to `V2-CASHFLOW-PLAN.md` for the why.
  The API table row for `/api/budget` (near line 457) becomes `GET /api/cashflow?month=YYYY-MM&view=cost|cash&exclude=…`.
- `V2-SPEC.md` §9.3: the **Budget** mode row becomes **Cash flow** (C-1, C-4, C-7, C-8).
- `V2-SPEC.md` §12: the `budget` account key becomes `liquid` (C-2); add `cashflow.yaml` as optional
  view-config (C-7); note `mortgages.yaml` is now also read by the cash-flow sheet (loan accounts,
  repayment and interest commitment ids).
- `V2-BUDGET-VIEW-PLAN.md`: add a short **D-8** bullet after D-7 saying D-2, D-3, D-6's boundary and
  D-7 are superseded by `V2-CASHFLOW-PLAN.md` (one sentence each is enough).
- `AGENTS.md`: the line "A change to derive, identity or units states its effect on the downstream
  views (Firefly and ezbookkeeping)" becomes "…on the downstream view (ezbookkeeping)".
- Commit: `docs(spec): the Budget tab becomes Cash flow (V2-CASHFLOW-PLAN)`.

Acceptance: `grep -n "budget" V2-SPEC.md` shows no remaining description of the old boundary
semantics (the word may remain in history references); the plan file is untouched.

## Task 2: `liquid` replaces `budget` in config (no behaviour change)

Mechanical rename through config and plumbing; the sheet's arithmetic is NOT changed in this task.

- `trex-v2-core/.../config/Account.java`: the `budget` component becomes `liquid`, same default
  rule (absent → true except a clearing account → false). Update its javadoc to C-2.
- `trex-v2-log/.../ConfigLoader.java` (~line 78-87, 309): read `liquid`; a `budget` key present on an
  account is a config error whose message names `liquid` (e.g. "accounts.yaml: 'budget' was renamed
  'liquid' (V2-CASHFLOW-PLAN C-2)"). Add a ConfigLoader test for both (default and the error).
- `trex-v2-hub/.../HubService.java`: `budgetAccounts()` → `liquidAccounts()`, `statementBudget` →
  `statementLiquid`; every caller and javadoc follows. The walk still receives the same set.
- `deploy/config/accounts.yaml`: every `budget: false` becomes `liquid: false` (same accounts, so
  behaviour is identical in this task), and the header comments describing the key follow C-2.
- Fix every test fixture writing `budget:` (`grep -rn "budget:" trex-v2-*/src/test`).
- Commit: `refactor(config): the budget account flag is renamed liquid`.

Acceptance: `mvn -q test` green across modules; `grep -rn "budget()" trex-v2-*/src` empty.

## Task 3: The cash-flow read (`trex-v2-hub`)

The core of the plan. Rewrite the shared walk and the sheet to §1 C-1…C-6 (exclusion is Task 4).
Read `V2-CASHFLOW-PLAN.md` §0-§1 first; they are the requirements.

**Files:** `HubQueries.java` (`walk`, `Walk`, `budgetSheet` → `cashflowSheet`, `headroom`),
`HubSql.java` (new `CASHFLOW_OCCURRENCES`; `HEADROOM_ROWS` may be reused), `HubService.java`,
`HubApi.java`, `HubHttpApi.java` (route), `api/BudgetResponse.java` → `api/CashflowResponse.java`,
`api/ExpectedResponse.java` (Headroom record), tests `BudgetApiTest` → `CashflowApiTest`,
`HeadroomApiTest`, `HubStatusApiTest`, `deploy/config/accounts.yaml`, and `web/js/api.js` +
`web/js/budget.js` only as far as needed to keep the current page loading (the UI proper is Task 5).

**Inputs to the walk:** the liquid set (Task 2), the declared loan accounts and, per loan, its
`repaymentCommitment` and `interestCommitment` ids (all from `mortgages.yaml`; extend the existing
`loanAccounts()` reader in `HubService`), the view (`cost`|`cash`), the group map (unchanged).
A loan account and a clearing account are removed from the liquid set whatever the flag (C-2).

**The walk (C-3):** classify each month row as in C-3. Drop the `internalOnly` map: a commitment
whose claimed facts are all transfers is a move (already `transferOnly`), and its occurrences are not
counted — **except** in the Cash view, where a declared `repaymentCommitment`'s `due`/`awaiting`
occurrences go into `Due` and a declared `interestCommitment`'s `due`/`awaiting` occurrences are
skipped (C-4). Track per loan: `repayments` (net of redraws) and `charges` (net), and the categories
of the charges so the Cash view can take them out of their groups (a charge attached to an
interest commitment occurrence is counted through that occurrence today — keep that path and make
sure the Cash view removes it from the group exactly once). Track set-aside per destination account.
Remove `movedIn`/`movedOut`/`outside` entirely.

**Occurrences (C-5):** a new `HubSql.CASHFLOW_OCCURRENCES` selecting the month's occurrences by
payment month for paid statuses and by due month otherwise; `EXPECTED_OCCURRENCES` is unchanged.

**Reconcile (C-6):** `liquidNet` from the rows; `postedLeft` and `gap` as defined.

**API:** `GET /api/cashflow?month=YYYY-MM&view=cost|cash` (anything but `cash` is `cost`);
`/api/budget` is removed. `CashflowResponse`:
`month, view, through, accounts (the liquid refs), income {posted, due, total, lines},
spend {posted, due, unrecorded, total, slices[{group, amount, parts[{label, amount}]}]},
loans {repayments, charges, principal, parts[{account, repayments, charges, principal}]},
setAside {total, parts[{label, amount}]}, missed, unpaired, left, reconcile {liquidNet, postedLeft, gap}`.
Slices: the groups (amount desc, then name), then in the Cash view `Mortgage` (parts
`Interest & fees`, `Principal`) and `Set aside` (parts: accounts), then `Due`, then `Unrecorded`;
zero slices are omitted. `spend.total` is the sum of every slice; `left = income.total − spend.total`.
`through` is the oldest statement frontier among the **liquid** statement accounts.

**Headroom:** `ExpectedResponse.Headroom` loses `movedOut`/`movedIn`; its `left` is the Cost view's
`left` for the month of `asOf`. Update every consumer (`grep -rn "movedOut\|movedIn" trex-v2-hub/src`,
including `web/js`).

**accounts.yaml:** `ing-savings`, `ing-loan-offset`, `cba-netsaver` lose `liquid: false` (they are
liquid); the two loan accounts keep `liquid: false` (explicit, though implied by C-2).

**Tests (`CashflowApiTest`, end-to-end through the journal like the current `BudgetApiTest`)** —
one fixture month with accounts `everyday`, `card`, `savings` (liquid), `offset` (liquid), `loan`
(declared in a test `mortgages.yaml`, with repayment and interest commitments), `kids` (clearing),
asserting:
1. a salary into `savings` then `savings → everyday` counts the salary **once**; `left` unaffected
   by the move;
2. `everyday → savings` and a card repayment change nothing;
3. offset → loan repayment 2,708.14 with a loan interest charge 1,744.50: Cost Out holds the
   1,744.50 in its category group and `left` excludes the principal; `loans.principal` = 963.64;
   Cash Out has a `Mortgage` slice 2,708.14 (parts 1,744.50 / 963.64) and the interest is **not**
   also in its group; Cost left − Cash left = principal + set-aside exactly;
4. a redraw loan → offset reduces `loans.repayments`;
5. `everyday → kids` 6,000 is `setAside` 6,000; in Cash it is a `Set aside` slice; in Cost only
   reported;
6. a commitment due on the last day of the previous month and paid on the 1st counts in **this**
   month (C-5);
7. Cash view of a past month: `reconcile.gap == 0` and `left == reconcile.liquidNet`;
8. current month, Cash view: a due repayment occurrence is in `Due`, a due interest occurrence is
   not; Cost view: the reverse;
9. headroom's `left` equals the Cost sheet's `left` for the same month.
Keep the existing headroom/status tests passing after updating them to the new semantics.

Commits: as many small ones as useful (e.g. SQL + walk, response + route, tests). Message prefix
`feat(hub):`.

## Task 4: Exclusion (`trex-v2-hub`)

C-7, server side.

- An optional view-config `cashflow.yaml` read the way `HubService` reads `ezb.yaml` (seeded config
  by name; absent file → no defaults; never hashed into `configRevision`):
  ```yaml
  # cashflow.yaml — Cash flow view defaults (V2-CASHFLOW-PLAN C-7). View-config only.
  exclude: [BIG_TICKET_PURCHASES]
  ```
  Add it to `deploy/config/` with that content.
- `GET /api/cashflow` takes `exclude` (comma-separated). Absent → the defaults; present (even empty)
  → exactly that list. A key matches a leaf category exactly, or a primary group name exactly.
- Response gains `excluded {keys, total, parts[{label, amount}]}` (keys = the effective keys in
  request/default order; parts = the excluded leaf categories, amount desc) and `leftExcl`.
  Excluded spending is removed from the slices (a group whose leaves are all excluded disappears;
  a partially excluded group keeps its other leaves); `spend.total` and `left` are **unchanged**;
  `leftExcl = left + excluded.total`. Only posted category spending is excludable.
- If `HubService` has a list of config files it reads or validates (e.g. `MCP_CONFIG_FILES` or a
  drift check), add `cashflow.yaml` where `ezb.yaml` appears.
- Tests: exclude by leaf, by group, default from file, explicit empty overrides default, `left`
  unchanged, `leftExcl` correct, slices still sum to `spend.total − excluded.total`.
- Commit: `feat(hub): the cash-flow sheet excludes categories by leaf or group`.

## Task 5: The Cash flow page (`web/`)

The UI for C-1, C-4, C-6. Rename and rework `budget.js` into `cashflow.js`.

- `index.html` nav: `#cashflow` labelled **Cash flow**; `app.js` registers mode `cashflow` and
  routes `#budget` (with its query) to it. `api.js`: `cashflow(month, {view, exclude})` →
  `/api/cashflow`.
- Toolbar: the month stepper (as today) plus a **Cost | Cash** segmented switch; state in the URL
  (`#cashflow?month=YYYY-MM&view=cash`), written with `history.replaceState` as the page does today.
  Remove the old principal checkbox.
- Money In table as today. Money Out table per the slices; `Mortgage` opens into Interest & fees /
  Principal, `Set aside` into account chips (as the moved parts are rendered today).
- Under the tables, Cost view: a muted bridge "Principal → home equity $X" (opening into loans) and
  "Set aside $Y" (opening into accounts), with one sentence: neither is spending; Cash view shows
  them as slices instead.
- Money Left Over bar as today; in the Cash view a reconcile line: "Matches the accounts' rows"
  when `reconcile.gap == 0`, else "Rows differ by $gap" (muted, informational).
- Remove the Moved bridge and "Outside the budget" sections.
- Must work at phone width (≥360px): tables and donut stack, controls tappable, no horizontal scroll.
- Update `app.css` class names as needed. No library.
- Commit: `feat(hub-ui): the Budget mode becomes Cash flow with Cost and Cash views`.

Verification: build the hub, run it against a scratch run dir (never the user's), load
`#cashflow` and `#budget` (alias) and check both views render without console errors.

## Task 6: Exclude toggles and the pivot (`web/`)

C-7 and C-8 in `cashflow.js`.

- A **Primary | Leaf** switch above Money Out (URL `pivot=leaf`, default primary). Leaf: one row per
  leaf category across groups (amount desc), the donut sliced the same way; `Mortgage`, `Set aside`,
  `Due`, `Unrecorded` stay as they are.
- Each category row (group in Primary, leaf in Leaf) and legend row carries a ⊘ toggle that adds or
  removes its key from the URL `exclude` list (first toggle starts from the response's
  `excluded.keys` so defaults are respected) and reloads.
- An **Excluded** line under Money Out: total and the keys, opening into the excluded leaves; the
  Money Out header shows the total net of exclusions with the full total muted beside it.
- Left: `left` as today, with "Left excl. one-offs $leftExcl" beside it when `excluded.total > 0`.
- Phone width as in Task 5.
- Commit: `feat(hub-ui): exclude categories and pivot Cash flow by primary or leaf`.
