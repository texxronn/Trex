# V2-INGEST-FRONTIER-PLAN.md

> **Archived 2026-10-09 — built (PR #45); its "proposed" status line below is history.** The outcome lives in `V2-SPEC.md` (the specification); this
> file is the record of why. Its references to `V2-PROPOSAL.md` as authoritative are history.

> **Personal project, single operator, private.** `V2-PROPOSAL.md` is the specification; this file
> is the build order for one change to it. Where this file and the proposal disagree, the proposal
> wins — until the §4 amendment lands.
>
> **Status:** proposed — Stage 0 (this plan). Build follows once this PR is merged.
> **Authority:** `V2-PROPOSAL.md` §10.1/§10.5 (the accounts overview), §12.6 (the ingest history),
> §12.2 (feeds and cursors); `V2-JOB-RUNNER-PLAN.md`; `AGENTS.md`.
> **Decision (operator, 2026-10-09):** the Jobs page shows, per account, the **latest transaction
> date already processed** — the frontier — so the next statement file can be requested with a date
> range. It is a read over the existing derived table; nothing new is stored.

---

## 1. The change, in one paragraph

Statements are retrieved from banks by date range, and the lower bound is the account's frontier:
the latest transaction date already in the log. That number already exists — `MAX(date)` over
`txn_current` per `account_ref`, the same value `GET /api/accounts` already serves as
`AccountCoverage.last` — but the Jobs page hides it: `GET /api/ingests` returns the `ingest_batch`
view (batch, file, account, n-range, status, times) and nothing date-shaped. Add `latestTxnDate` to
each ingest row and show it on the Jobs page, both as a column on the ingest table and as a small
per-account **fetch frontier** strip (account, newest ingest, frontier, suggested `from`..`to`).

## 2. Why: the workflow it unblocks

The runner has no retrieve step — `ingest` reads staged files, and files are fetched by hand from
each bank. A bank export is requested as a date range; without the frontier the operator either
re-fetches everything (slow, large) or guesses. The data to answer it is already derived:

| account | first | frontier (`MAX(date)`) |
|---|---|---|
| cba-smartaccess | 2024-10-02 | 2026-10-01 |
| ing-credit-card | 2023-03-29 | 2026-10-02 |
| ing-salary | 2019-10-14 | 2026-09-14 |
| bw-card-legacy | 2019-10-28 | 2023-12-06 (closed — no range to fetch) |

The Accounts mode already pairs account → totals and → newest ingest (`HubQueries.accountTotals`,
`HubSql.ACCOUNT_LAST_IMPORTS`); the Jobs page simply never joined them.

## 3. Target model

### 3.1 The frontier

- **Definition**: `MAX(txn_current.date)` for the account, clamped to `asOf` (today), so a
  future-dated or scheduled row cannot push the suggested range into the future. No facts for the
  account → null.
- It is a **read**, not a new derived table: `txn_current` is already derived and disposable; the
  frontier is a query. No column, no table, no `deriveVersion`/`statehash` change.
- It is **per account, not per batch**: every ingest row for one account carries the same frontier.
  That is why the strip (one row per account) is the honest surface and the column is a convenience.

### 3.2 The read

- `HubSql`/`HubQueries.ingests()` gain one expression: a correlated
  `(SELECT MAX(t.date) FROM txn_current t WHERE t.account_ref = b.account_ref AND t.date <= ?)`,
  bound to `asOf`.
- `IngestsResponse.IngestRow` gains `LocalDate latestTxnDate` (nullable). The DTO is the only hub
  change; no new endpoint, no change to `ingest_batch` or any table.

### 3.3 The surfaces

- **Ingest table** (`jobs.js` `renderIngests`): a "Frontier" column showing `latestTxnDate` (or
  `—`), next to `Account`.
- **Fetch frontier strip** (new, above the ingest table): one row per **registry account**
  (`ctx.refdata.accounts`, already loaded in `jobs.js`), joined to the newest ingest row for that
  account; columns: account, newest file + status, frontier, and suggested `from`..`to`. An account
  with no facts shows `—` and no range; an account whose frontier is well behind `today` is a
  prompt to check whether it is closed or merely unfetched (the plan does not label it closed —
  that is a judgment).

### 3.4 Range semantics

- Suggested `to` is `asOf` (today). Suggested `from` is the **frontier itself**, not `+1 day`: a
  one-day overlap is harmless because re-observing an identical fact is suppressed by `occ`
  (§6.1), and it avoids missing a late posting dated on the frontier day. Where the bank export is
  period-based, the operator rounds to the statement period.
- **Statement periods are not recorded** (`AccountsResponse` says so): this is a heuristic lower
  bound, not a coverage verdict. A hole still means "check", not "missing".

### 3.5 Unchanged

The log, the decision set, the sequencer, the runner and its jobs; the `ingest_event` markers and
the `ingest_batch` view; `txn_current` and every derived table; `GET /api/accounts` and the Accounts
mode; the `source_cursor` feed mechanism (a cursor is not a date — feeds resume by cursor, and this
plan does not touch them).

## 4. Supersedes / amendments

- **`V2-PROPOSAL.md` §12.6** (the ingest history): note that a row carries the account's frontier
  as a read, alongside its n-range. `§10.1`/`§10.5` already define the frontier (`last`); this just
  surfaces it on the Jobs page. No architecture change.
- **`V2-JOB-RUNNER-PLAN.md`**: the Jobs page gains the fetch-frontier strip; the runner itself is
  untouched.

## 5. Invariant check

- Read-only: no decision, no log line, no new table, no schema migration; `derive()` untouched;
  `deriveVersion` and `statehash` unchanged; every table stays disposable.
- The frontier is derived from `txn_current` on request — no stored cursor, so nothing to drift.
- If retrieval ever becomes an automated job, the range it consumes is an input to that job, not a
  stored state; the `source_cursor` seam remains the durable resume point for feeds.

## 6. Blast radius

| Area | Files |
|---|---|
| index | none |
| hub | `HubQueries.ingests()` (frontier expression), `IngestsResponse.IngestRow` (+ `latestTxnDate`) |
| web | `jobs.js` (`renderIngests` column; a new `renderFrontier` strip), `app.css` (strip styling if needed) |
| docs | this plan; `V2-PROPOSAL.md` §12.6; `V2-SPEC.md` (the `/api/ingests` row); `CHANGELOG.md` |

## 7. Stages

- **Stage 0 — this plan.** One docs PR.
- **Stage 1 — the read.** `latestTxnDate` on the ingest row; a hub test (an account's newest fact
  sets the frontier; a future-dated fact is clamped; an account with no facts is null). One PR.
- **Stage 2 — the UI.** The Frontier column and the fetch-frontier strip on the Jobs page. One PR
  (or folded into Stage 1).
- **Stage 3 — prove it.** On the dev stack, the strip reads each active account's frontier and the
  suggested range; `trex verify` green (no derivation changed).

## 8. Acceptance

- `GET /api/ingests` rows carry `latestTxnDate`; it equals `MAX(date)` for the account, clamped to
  `asOf`, and null when the account has no facts.
- The Jobs page shows the frontier per ingest row and a per-account strip with the newest ingest
  file and a suggested `from`..`to`.
- A future-dated fact does not push the frontier or the suggested range beyond today.
- No derivation, table, log or decision changes; `trex verify`: rebuild ≡ incremental.

## 9. Decisions taken (operator, 2026-10-09)

1. The frontier is a **read** over `txn_current`, not a stored field.
2. It is **per account**, clamped to `asOf`.
3. Suggested `from` is the frontier inclusive (overlap-safe by `occ` suppression); `to` is today.
4. Show **both** the per-ingest column and the per-account strip.
5. Plan first, then build.

## 10. Rollback

Revert the stage PRs. Nothing is stored, so the revert removes the column and the strip and leaves
the index, the log and the runner exactly as they were.
