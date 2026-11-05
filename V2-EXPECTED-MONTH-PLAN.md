# V2-EXPECTED-MONTH-PLAN.md

A **month stepper** on the Expected tab's "This month" window, so a past (or coming) month's
occurrences can be read after the calendar has moved on.

**Authority:** `V2-SPEC.md` §6.5 (occurrences), §9.1 (the hub's `today`, `toPay`), §9.3 (Expected),
the API table (§9); `AGENTS.md`. **Status:** draft, E-1…E-7 open (2026-11-05). Where this plan and
`V2-SPEC.md` differ, the spec wins.

## 0. Why

- Expected's windows are Today, This week and This month, and each is measured from the hub's
  today. On Nov 5 "This month" is November; October is gone from the screen.
- Missing the tab for a few days at a month end loses the tail of that month. **Catch up** still
  lists October's `missed` occurrences (arrears), but not the ones that resolved — `occurred`,
  `settled` — which are what "did the end of the month go to plan?" needs.
- Cash flow already steps months (`#cashflow?month=YYYY-MM`, `‹ month ›`); Expected should read the
  same way.

## 1. What already exists (no recalculation)

- Derive materialises every declared regular commitment's occurrences over
  **`asOf − 12 months … asOf + 92 days`** (`CommitmentMatcher.PAST_MONTHS` / `HORIZON_DAYS`,
  §6.5) into `commitment_occurrence`. The past year is already in the index.
- "This month" is a due-date filter over those rows (`HubService.expected` → `Period.bounds(month)`
  → `HubSql.EXPECTED_OCCURRENCES`). A stepper is the same filter over another month's bounds.
- `asOf` advances by a full derive at least every `HubConfig.refreshAsOfByMs` (60 s) (§6,
  "When `asOf` advances"), so a long-running hub rolls over midnight and month ends by itself.
- `/api/expected` already takes `asOf`, but that is the wrong lever: it also moves `toPay` (and the
  headroom) to that day, so "October" would show what was to pay on Oct 31 rather than now.

## 2. What a past month means (state it in the spec)

A past month shows **today's understanding of that month**, not a snapshot of the screen on its
last day:

- **Statuses carry hindsight.** A bill due Oct 30 whose statement row lands Nov 3 (inside its
  window) reads `occurred`; a later Mark paid reads `settled`. On Oct 31 it would have been `due`.
- **Due dates follow the current declaration.** A re-anchored commitment, or a rule change, re-plays
  its past dates and claims under what is declared now (§6.5: claims use the latest declaration
  that admits the fact).
- **Retired** commitments show up to `endedAt`; a newly declared one with an old anchor can show old
  holes — nothing is auto-forgiven.

An "as it looked then" view would be a re-derive of the log prefix at a past `asOf` (derive is
pure, so it is possible). **Out of scope:** it costs a full fold per look and answers a different
question.

## 3. Decisions (operator)

| | Question | Recommendation |
|---|---|---|
| E-1 | How does the API name the month? | A new `month=YYYY-MM`, valid only with `window=month` (else 422). It moves the occurrence range and nothing else; `asOf` keeps its meaning. |
| E-2 | How far back? | Whole generated months only: `minMonth` = the first month wholly inside `asOf − 12 months` (Nov 5 2026 → Dec 2025). The partly generated month is not offered — it would look like missing payments. |
| E-3 | How far forward? | To the month holding `asOf + 92 days` (Nov 5 2026 → Feb 2027), so "what is coming in December" works. The last month may be cut short by the horizon; the header says so ("generated to 2027-02-05"). |
| E-4 | What stays at today? | Arrears (Catch up), `toPay` (Manual payments), the headroom and the since-line. Only Occurrences and its totals follow the month. |
| E-5 | Is the month remembered? | No. It lives in the URL (`#expected?window=month&month=2026-10`) for links and reloads; opening the tab fresh shows the current month. The window choice stays a device preference as today. |
| E-6 | Today / This week? | No stepper (out of scope). The stepper appears only on This month. |
| E-7 | MCP? | `trex_expected` gains the optional `month` (wraps `HubApi.expected`; read-only, no write). |

## 4. Changes

### 4.1 Spec (same PR as the code, `AGENTS.md`)

- API table: `/api/expected?window=today|week|month[&month=YYYY-MM]` — the month's occurrences;
  arrears, `toPay` and headroom stay measured at today; out of range or with another window → 422.
  The response carries `month`, `minMonth`, `maxMonth`.
- §9.3 Expected row: the stepper on This month, the URL form, and the hindsight sentence from §2.
- Downstream (ezbookkeeping): **none** — a read-only filter over existing index rows; no derive,
  identity or unit change.

### 4.2 Hub

- `HubApi.expected(String window, String month, LocalDate asOf)`; `HubService.expected` builds the
  range from `month` when given, else from `at` as today. Bounds come from the **index's `as_of`**
  (`indexer.meta("as_of")`), the same day the rows were generated at, not the wall clock — the two
  agree except for up to 60 s around midnight, and then the rows win.
- `ExpectedResponse` gains `month`, `minMonth`, `maxMonth` (nullable for today/week).
- `HubHttpApi` `/api/expected`: pass `month`; a malformed or out-of-range month → 422.
- `McpTools`: `month` in `expectedProps()` and the allowed-parameter set.

### 4.3 UI (`expected.js`, `api.js`)

- `api.expected(window, month)`.
- On This month: `‹ Oct 2026 ›` beside the tabs, the Cash flow stepper's look and keys; `‹`/`›`
  disabled at `minMonth`/`maxMonth`; a "this month" link back when away from it.
- The URL carries `month`; switching to Today/This week drops it.
- Occurrences header: `Occurrences · Oct 2026 · 14`; the empty text names the month
  ("Nothing due in Oct 2026."). A past month carries a muted note: "statuses as of today".

## 5. Acceptance tests

1. `month=2026-10` with `window=month` returns only occurrences due in October, with their current
   statuses (a fact landing after Oct 31 inside the window reads `occurred`).
2. `toPay`, `arrears` and `headroom` are identical with and without `month`.
3. `minMonth`/`maxMonth` follow E-2/E-3 for a fixed `asOf`; one month outside either bound → 422.
4. `month` with `window=today` or `week` → 422; a malformed month → 422.
5. No `month` → the response is unchanged apart from the three new fields (existing tests stay green).
6. MCP `trex_expected` with `month` returns the same body as the HTTP read.
7. UI (manual, Dev1): step back to October and forward to February; the buttons stop at the
   bounds; a reload keeps the month; reopening the tab shows the current month; Mark paid in the
   Manual payments group still works while a past month is showing.

## 6. Build order

Task 1 and Task 2 are code (one branch, one PR). The release and the Prod1 deploy follow the merge
(patch bump per `docs/RELEASE.md`; `docs/DEPLOYMENTS.md`, *Deploy or update*); they are not tasks.

### Task 1: Spec, hub and tests

Add an optional `month=YYYY-MM` to the Expected read, in the spec and the hub, with tests.

**Spec (`V2-SPEC.md`, in this task's commits):**
- API table (§9, the `GET /api/expected` row): `?window=today|week|month[&month=YYYY-MM]`. `month`
  is valid only with `window=month` (else 422) and moves only the occurrence range and its totals;
  `arrears`, `toPay` and `headroom` stay measured at today (or the explicit `asOf`). The response
  carries `month`, `minMonth`, `maxMonth` (null for today/week). A month outside
  `[minMonth, maxMonth]` or a malformed one → 422.
- §9.3 Expected row: the This-month window steps months (`‹ month ›`, `#expected?window=month&month=YYYY-MM`)
  between `minMonth` and `maxMonth`; the month is not a stored preference. Add the hindsight
  sentence: a past month shows today's understanding of it — statuses as the index has them now
  (a late statement row reads `occurred`, a later Mark paid `settled`), dates and claims under the
  current declarations — not a snapshot of the screen at that month's end.
- Note in the spec PR text and the spec's change notes, if it keeps any: downstream
  (ezbookkeeping) effect **none** — a read-only filter over existing index rows.

**Bounds** (from the day the occurrence rows were generated at, see below):
- `minMonth`: occurrences are generated from `at.minusMonths(12)` inclusive
  (`CommitmentMatcher.PAST_MONTHS = 12`). `minMonth` is the first month **wholly** generated:
  `YearMonth.from(at.minusMonths(12))` when that date is the 1st, else the month after.
  Example: at = 2026-11-05 → past = 2025-11-05 → minMonth = 2025-12.
- `maxMonth`: `YearMonth.from(at.plusDays(92))` (`CommitmentMatcher.HORIZON_DAYS = 92`).
  Example: at = 2026-11-05 → 2027-02-05 → maxMonth = 2027-02.
- `at` for the bounds is the explicit `asOf` when given; otherwise the **index's `as_of`** (the meta
  value the indexer records on each full derive — see `IndexRefresher`, `indexer.meta("as_of")`),
  falling back to the hub's today when the index has none. Rationale: the rows were generated at
  the index's `as_of`; it agrees with the wall clock except for up to 60 s around midnight.

**Hub (`trex-v2-hub`):**
- `HubApi.expected(String window, String month, LocalDate asOf)`; update every caller
  (`HubHttpApi`, `McpTools`, `HubService`'s own internal `expected("month", null)` call, tests).
- `HubService.expected`: when `month` is given (and window is `month`), the occurrence range is
  `Period.bounds(month)`; otherwise unchanged. `toPay`, arrears and headroom are still measured at
  `at` (asOf or today), never at the stepped month.
- `ExpectedResponse` gains `month` (the YYYY-MM read, for window=month), `minMonth`, `maxMonth`.
- `HubHttpApi` `/api/expected`: read `month`; bad input → 422 like the existing `asOf` parse error.
- `McpTools`: `month` in `expectedProps()` and in the tool's allowed-parameter set (both places
  `Set.of("window", "asOf")` appears for `/api/expected`). The MCP tool wraps `HubApi.expected` and
  is read-only.

**Tests** (extend the existing Expected API tests, e.g. `CommitmentsApiTest` / `HeadroomApiTest`):
1. `window=month&month=<past month>` returns only occurrences due in that month, with their current
   statuses (a fact dated after that month's end but inside the occurrence window reads `occurred`).
2. `toPay`, `arrears` and `headroom` are identical with and without `month`.
3. `minMonth`/`maxMonth` match the rules above for a fixed `asOf`; one month outside either bound → 422.
4. `month` with `window=today` or `window=week` → 422; a malformed month (`2026-13`, `2026-1`) → 422.
5. No `month` → existing tests stay green; the new fields are present (`month` = the current month
   for window=month, null bounds only for today/week as specified).
6. MCP `trex_expected` with `month` returns the same body as the HTTP read.

Run the hub module's tests (`mvn -q -pl trex-v2-hub -am test` with Java 25 at
`~/Tools/JDK/jdk-25.0.4.1+1/`) and keep the full build green.

### Task 2: Expected UI month stepper

Give the Expected tab's **This month** window a month stepper, reading Task 1's API.

- `api.js`: `expected(window, month)` — adds `month` to the query when given.
- `expected.js`:
  - Only on the This-month window, a stepper beside the tabs: `‹`, the month label
    (`Oct 2026`), `›` — reuse the look, markup classes and month-key helpers of the Cash flow
    stepper in `cashflow.js` (§ "the month stepper", `monthKey`, `monthLabel`); copy small helpers
    locally rather than importing across modes, as `cashflow.js` itself does.
  - `‹` disabled at the response's `minMonth`, `›` at `maxMonth`. When the shown month is not the
    current one, a small "this month" link returns to it.
  - The month lives in the URL: `#expected?window=month&month=YYYY-MM` (read it from
    `context.modeQuery` in `mount`, like `window`; rewrite the hash in place on a step, the way
    `cashflow.js` does). It is **not** stored in `localStorage`. Switching to Today / This week
    drops it. A fresh open of the tab shows the current month.
  - Occurrences header: `Occurrences · Oct 2026 · 14` when a month is shown (the window label
    otherwise); the empty text names the month (`Nothing due in Oct 2026.`). A past month adds a
    muted note in the header: `statuses as of today`.
  - Manual payments (`toPay`), Catch up and the since-line are unchanged and keep working while a
    past month is shown.
- Keep the existing `#expected?window=week` and `?section=to-pay` deep links working; they compose
  with `month`.

Verify in a browser against a local hub if one is available; otherwise say so in the report. No
Java change in this task.
