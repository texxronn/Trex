# V2-QOL-IMPROVEMENTS-PLAN.md

> **Archived 2026-10-09 — built (PRs #61–#64 and this plan's PR); the outcome lives in `V2-SPEC.md`
> (the specification); this file is the record of why.**

> **Personal project, single operator, private.** `V2-SPEC.md` is the specification; this file is
> the build order for five quality-of-life changes. Each stage's PR updates `V2-SPEC.md` where it
> changes behaviour.
>
> **Status:** built (2026-10-09): Q6, Q4, Q3, Q2 and Q5 are built (§1–§5).
> **Goal:** the daily-use goal (`docs/reviews/CLAUDE-REVIEW.md`, "The goal: daily use") — a calm screen, a
> routine with a finish line, no trips to the terminal. Each item removes friction that actually
> happened while the review fixes were rolled out on the dev stack.
> **Out of scope:** item 1 of the suggestion list (the review-queue *baseline*: a one-off batch of
> `DISMISS` decisions for items older than a cutoff) — the operator does it by hand.

---

## 0. Summary

| # | Change | Friction it removes | Size | Writes the log? |
|---|---|---|---|---|
| Q2 | **Re-read evidence** as a runner job with a preview and a gated Apply | the journal repair needed `docker exec … java … ingest --reparse` | M | only on Apply, through the existing reparse path |
| Q3 | **Config drift**: show which config files the stack runs differ from the repo; `dev.sh sync-config` updates the safe ones | the `profiles.yaml` fix silently never reached the dev stack | M | no |
| Q4 | **Statement age nudge** next to the all-clear | an `awaiting` bill or an old `through` date says nothing about what to *do* | S | no |
| Q5 | **"Since you last cleared"** one-line summary | opening the app means re-scanning to learn whether anything happened | S–M | no |
| Q6 | **Ingest toast**: "2 statements ingested · 47 new rows · 1 new item" | a drop-folder sweep is invisible unless you open Jobs | S | no |

**Order:** Q6 → Q4 → Q3 → Q2 → Q5. Smallest and most visible first; Q2 last-but-one because it
writes the log; Q5 last because it is the one most worth re-judging after a month of daily use.

**Invariants touched:** none. Q3–Q6 are reads. Q2 writes only `SUPERSEDE`/`RETIRE` decisions and
facts through `Reparse.apply`, exactly as `trex ingest --reparse --apply` does today. No derived
table gains a non-disposable column; per-viewer memory (Q5) lives in the browser, never in the index.

---

## 1. Q6 — Ingest toast

### 1.1 Why
The drop folder (`ingest-inbox`, PR #52) works silently: nothing on the Blotter or Expected says a
sweep ran. The only proof is the Jobs page.

### 1.2 Design
Everything needed is already on the wire:
- the SSE delta carries the new head `n` (`HubEvents.Change.n`);
- `GET /api/ingests` returns each batch with `nStart`, `nEnd`, `file`, `accountRef`, `appended`,
  `duplicate`, `flagged`, `status` (`IngestsResponse.IngestRow`);
- `GET /api/status` returns `reviewByKind`.

On each delta, the UI (any mode) compares the new `n` with the last one it saw. When batches
completed in between, it shows one toast:

> **2 statements ingested** · 47 new rows · 1 flagged · 1 new review item — *ING_Salary_Account.csv,
> BW_*.csv*

- "new rows" = Σ `appended`; "flagged" only when > 0 (the honest count, PR #56); "new review item"
  = the review total now minus the total at the previous delta, shown only when positive.
- A batch with `status` ≠ `ok` makes the toast an error-styled one naming the file ("rejected",
  "bad rows").
- Clicking the toast opens Jobs → ingest history.

### 1.3 Changes
- `GET /api/ingests?sinceN=<n>`: an optional filter (`n_end > ?`) so the UI never pulls the whole
  history on a delta. `HubQueries.ingests`, `HubSql`, `HubHttpApi`.
- `web/js/app.js` (the SSE `onDelta` handler) and a new `web/js/ingestToast.js`; `toast.js` gains an
  optional click target.
- No change to the runner, the sequencer or the log.

### 1.4 Tests and acceptance
- `HubIngestsApiTest.sinceNReturnsOnlyLaterBatches`.
- Manual (dev stack): drop two statements in the inbox, run **Ingest inbox**, keep the Blotter open →
  one toast with the right counts; a re-run of the same files toasts "0 new rows · 2 duplicates"
  (the duplicate count is shown when every row was a duplicate, so a no-op sweep is still
  confirmed).

**As built (2026-10-09).** `GET /api/ingests?sinceN=<n>` keeps batches with `n_end > n`, untruncated
— the 50-batch page size applies to the unfiltered history only, so a delta that missed more than 50
batches still reports them all (no filter is the old read; a non-numeric filter is 422). The SQL
stayed inline in `HubQueries.ingests`, so `HubSql` was not touched. A new `web/js/ingestToast.js`
hangs off `app.js`: `onSnapshot` seeds the baseline and fetches the review total the page loaded
with, so the first delta after load/reconnect fetches (and toasts) the batches completed since the
snapshot and diffs its review delta against that total when it had already settled — a status read
that failed, or was still in flight when the delta arrived, shows no review delta rather than a
guess; only a stream that never saw a snapshot treats its first delta as the baseline. An in-flight
fetch is void if a snapshot or rollback rebaselines the journal mid-fetch, so no stale cursor or
toast survives a rebuild. Duplicates are named only when every row was a duplicate — nothing appended,
nothing flagged, no failed batch — and any batch whose status is not `ok` makes the toast
error-styled and names the file with the runner's word (`bad_rows` → "bad rows"). `toast()` gained
an optional click target (a `clickable` class); a click sets `#jobs`, the mode switch the nav
already uses. Nothing writes: the runner, the sequencer and the log are untouched. Pinned by
`HubIngestsApiTest` (`sinceNReturnsOnlyLaterBatches`: whole history without the filter, later-only,
empty at the head, 422; `sinceNReturnsEveryLaterBatchPastTheHistoryLimit`: 55 batches, the plain read
is 50 rows, `sinceN=0` is 55) and `HubAccountsTest` still reads the unfiltered history. The UI was
not checked in a browser; the manual acceptance above is still to run on the dev stack.

---

## 2. Q4 — Statement age nudge

### 2.1 Why
Stage 3 made a late statement honest (`awaiting`, never `missed`), and the status strip says
"all clear — through 2026-09-07". Neither says *which* statement to fetch. The Jobs page has the
per-account fetch frontier (`V2-INGEST-FRONTIER-PLAN.md`), but nobody opens Jobs to learn they
should open Jobs.

### 2.2 Design
- **Per account, a fetch cadence.** `accounts.yaml` gains an optional `fetchEveryDays` (presentation
  only, like `chip_color` and `budget`): the age beyond which the account's statements count as old.
  Default **31** for a `statement` account, none for `declared`/`clearing`; `0` turns the nudge off
  (a closed account that still has statement rows).
- **Age** = today − the account's frontier (`MAX(txn_current.date) ≤ today`, the query
  `HubSql.ACCOUNT_FRONTIERS` already runs).
- `GET /api/status` gains `stale: [{account, frontier, days}]`, oldest first, only accounts past
  their cadence.
- **The strip** shows, after the all-clear or the review count:
  `⧗ 2 statements to fetch` (muted, amber when any is > 2× its cadence), with a tooltip listing
  `ing-salary · 32 days (through 2026-09-07)`; a click opens Jobs at the fetch-frontier strip, which
  already suggests the date range.
- **Expected**: an `awaiting` occurrence's tooltip names its account's age when that account is
  stale ("waiting for the ing-salary statement — 32 days old").

### 2.3 Changes
- `trex-v2-core` `Account`: `Integer fetchEveryDays` (nullable → default by `balanceSource`);
  `ConfigLoader.AccountEntry` reads it. `configRevision` moves on edit, which only re-derives.
- `HubService.status()` computes `stale` from the frontiers it already reads for `through`.
- `web/js/status.js`, `web/js/expected.js`.
- `deploy/config/accounts.yaml`: document the field; set `fetchEveryDays: 0` on any closed
  statement account.

### 2.4 Tests and acceptance
- `HubStatusApiTest.staleListsAccountsPastTheirCadence` (one account inside, one past, one with 0).
- `ConfigLoaderTest.fetchEveryDaysDefaultsByBalanceSource`.
- Manual: on the dev stack (frontiers early September) the strip shows the stale accounts; after
  ingesting a fresh statement for one, it drops off the list on the next delta.

**As built (2026-10-09).** `Account` gained a nullable `fetchEveryDays` and the loader resolves the
default — explicit value wins, including `0`; absent is `31` for a statement account and null for
declared/clearing — so the record holds only resolved values and the existing convenience
constructors pass null. The canonical constructor refuses a negative value. `HubService.status()`
now reads the per-account frontiers once and computes both `through` (its old rule: the oldest
frontier among budget statement accounts, now from that map) and `stale`; `HubQueries` exposes the
map and its through-only wrapper is gone (`headroom` still has its private read). Stale is strict
(`days > cadence`), null and `0` cadences never nudge, an account with no frontier is skipped, and
the list is oldest first with ties by ref. The plan's three fields could not express the amber rule,
so each entry also carries the effective `fetchEveryDays` — the wire shape is
`{account, frontier, days, fetchEveryDays}` (`StatusResponse.Stale`) — and the UI decides
`days > 2 × fetchEveryDays` for amber. The strip places the nudge after the all-clear or the review
count, muted until amber, its tooltip one line per account; the click sets `#jobs?frontier`, which
the existing shell router hands to jobs as `ctx.modeQuery`, and the Jobs heading (now
`id="fetch-frontier"`) scrolls into view once its load has rendered the sections above it.
Expected's `load` fetches `/api/status` in its existing `Promise.all`; that read fails open — a
status error leaves the stale list empty rather than blanking the view — and a module-level load
generation discards a superseded load's results and error bar when a mount races an SSE refresh.
The `awaiting` tooltip names an account only when the commitment's rules resolve to exactly one
distinct account (repeated refs are one account) and that account is stale; the matcher escalates
on the newest frontier over several accounts, so naming one of them would be a guess, and an
unscoped rule, an unknown commitment or no stale account also keeps the generic hint. In
`deploy/config/accounts.yaml` only the header comment
documents the field: every closed account there is `balanceSource: clearing`, which already
defaults to no cadence, so none needed the explicit `0`. Pinned by
`HubStatusApiTest.staleListsAccountsPastTheirCadence` (one account inside its default 31, two past
their explicit 10 and 5 in age order, one `0`, one declared, one with no frontier; `through`
unchanged) and `ConfigLoaderTest.fetchEveryDaysDefaultsByBalanceSource` plus
`negativeFetchEveryDaysIsALoadError`. The UI was not checked in a browser; the manual acceptance
above is still to run on the dev stack.

---

## 3. Q3 — Config drift

### 3.1 Why
`compose.yml`'s `init` seeds the config volume with `cp` **only when a file is missing**, so the
rules you edit in the UI survive a rebuild. The cost: a repo change to a file you never edited never
reaches the stack either. On 2026-10-09 the new `profiles.yaml` rule (and `accounts.yaml`,
`statements.yaml`, `schedule.yaml`) were missing from the dev volume; running the journal repair
without them would have broken the loan's balance chain. It was caught by hand.

### 3.2 Design — a three-way comparison per file
Three versions of each config file exist:
- **S** — *shipped*: the version in the image/compose bootstrap right now;
- **B** — *base*: the shipped version that was last installed into the volume;
- **C** — *current*: what is in the volume (possibly edited in the UI).

| C vs S | C vs B | S vs B | State | Action |
|---|---|---|---|---|
| equal | — | — | `same` | none |
| differs | equal | differs | `repo-newer` | **safe to update** (you never edited it) |
| differs | differs | equal | `edited-here` | none — your edit, the repo did not move |
| differs | differs | differs | `both-changed` | report; merge by hand |
| differs | no base | — | `unknown` | report (first run after this ships); `sync-config --adopt` records C as the base after you look |

- **`init` keeps the rule "never overwrite"**, and additionally writes, on every `up`:
  `/etc/trex/.shipped/<file>` (S, always overwritten) and, when it installs a missing file,
  `/etc/trex/.base/<file>` (B = what it installed).
- **The hub** reads the three directories (it already reads `/etc/trex`) and serves
  `GET /api/config/drift` → `[{file, state}]`. Pure file reads; nothing derived, nothing stored.
- **The Jobs page** shows a one-line strip when any file is not `same`:
  "config: profiles.yaml is newer in the repo (safe to update) · categories.yaml edited here".
- **`deploy/v2/dev.sh sync-config`** (and the same commands documented for the host in
  `docs/DEPLOYMENTS.md`): for each `repo-newer` file, back up C to `/etc/trex/.backup/<file>.<ts>`,
  copy S over it, record B = S. Prints what it did. Never touches `edited-here` or `both-changed`.
  `--adopt <file>` records the current file as the base, for the `unknown` first run.
- The sequencer and the hub already pick up config edits (PR #51 and the hub's watcher), so a sync
  needs no restart.

### 3.3 Changes
- `deploy/v2/compose.yml` (+ `compose.server.yml` by the documented `sed`): the `init` script.
- `trex-v2-hub`: `ConfigDrift` (pure: three byte arrays → a state), `GET /api/config/drift`.
- `web/js/jobs.js`: the drift strip.
- `deploy/v2/dev.sh`: `sync-config [--adopt FILE]`, run in a throwaway `alpine` container against
  the `config` volume, as the 2026-10-09 rollout did by hand.

### 3.4 Tests and acceptance
- `ConfigDriftTest`: one case per row of the table (pure function).
- `HubConfigDriftApiTest`: a temp config dir with `.shipped/` and `.base/` → the expected states.
- Manual: change `profiles.yaml` in the repo, `dev.sh up` → Jobs shows `profiles.yaml` as
  `repo-newer`; `dev.sh sync-config` updates it and leaves an edited `categories.yaml` alone; the
  strip clears.

### 3.5 Risk
The first deploy has no `.base/`, so every differing file reads `unknown` once. That is deliberate:
the tool must not guess whether a past edit was yours. One `--adopt` per file after a look settles it.

**As built (2026-10-09).** `ConfigDrift` in `trex-v2-hub` holds the canonical file list — the
eleven `init` seeds, declaration order is the wire order — and the table as one strict chain:
`C == S` is `same` before anything else (so B is irrelevant and an unedited file reads `same` on
the first run with no `.base/`); then absent `S` or absent `B` is `unknown`; then `C == B` is
`repo-newer` (`S != B` follows); `S == B` is `edited-here`; else `both-changed`. A `null` array is
an absent file and an absent C is a difference, so a deleted file classifies as an edit whenever S
and B allow it. `scan` reads `.shipped/<file>`, `.base/<file>` and `<file>` per request — an
unreadable file fails the read rather than guessing (the route answers 500) — and
`GET /api/config/drift` serves `[{file, state}]` for all eleven in that order, the bare-list shape
`/api/review` already uses. `refdata.yaml` stays out: it is optional and compose does not seed it,
so the three-way read has nothing to compare. The `init` script keeps "never overwrite": it
`mkdir`s `.shipped/` and `.base/`, overwrites `.shipped/<file>` from `/bootstrap/<file>` on every
`up`, and writes `.base/<file>` only when it installs a missing live file. `compose.server.yml` is
the documented `sed` of the repo file and its diff is only that init hunk. `deploy/v2/sync-config.sh`
is the container-side POSIX-sh twin of the pure function (`cmp`, no `md5sum`): it prints every
verdict, backs a `repo-newer` file up to `.backup/<file>.<ts>`, copies S over C and B, and leaves
`edited-here`, `both-changed` and `unknown` alone; a path that exists but is not a regular file
stops the run, and `ADOPT=<file>` copies the live file over `.base/<file>` (refused when there is no
`.shipped/<file>` — one `up` seeds it, and adopting cannot settle that case). The script lives in
its own file so `dev.sh sync-config [--adopt FILE]` (which runs it in a throwaway `alpine` against
`trex-v2_config`) and the host's documented `docker run` execute the same bytes;
`docs/DEPLOYMENTS.md` carries the host invocation — with all eleven configs in the scp list — and
says a sync needs no restart. The Jobs strip renders one line above the staging inbox, naming every
non-`same` file — "is newer in the repo (safe to update)", "edited here", `both-changed` as "merge
by hand", `unknown` as "no base recorded — look, then sync-config --adopt; up first if .shipped is
missing" — tolerates a payload that is not an array of well-formed rows (and a failed drift read)
by rendering nothing rather than throwing. Pinned by
`ConfigDriftTest` (the table's rows, absent S/B, a deleted C, the wire spellings, the file order)
and `HubConfigDriftApiTest` (a temp config dir with `.shipped/`, `.base/` and live files; all
eleven states and their declaration order over HTTP). The UI was not checked in a browser; the
manual acceptance above — including the first-run `--adopt` — is still to run on the dev stack.

---

## 4. Q2 — Re-read evidence (reparse) from the Jobs page

### 4.1 Why
The journal repair ran as `docker exec trex-v2-runner-1 java -cp @/app/jib-classpath-file
trex.v2.Main ingest --reparse=<evidence> --source-type=ing-csv --account=<ref> --evidence=…
--journal=… --sequencer-url=…`, twice per file. A parser fix (the normal reason to reparse) should be
a button with a preview, not a terminal session.

### 4.2 Design
A runner job **`reparse`** over the existing subcommand — no new logic, in the shape of
`egress-firefly`:
- **Params:** `evidence` (required), `mode` = `preview | apply`. `sourceType` and `account` are taken
  from the batch's `trex.ingest start` event, so the person cannot pair evidence with the wrong
  adapter; the hub passes them with the request (the ingest history already knows the batch).
- **Argv:** `ingest --reparse=<evidence> --source-type=<t> --account=<a> --evidence=<dir>
  --journal=<journal> --sequencer-url=<url>` (+ `--apply`). The runner already mounts the journal
  read-only and the evidence store.
- **Gate:** `apply` needs `--allow-apply` (as egress), and the UI enables **Apply** only after a
  **Preview** of the same evidence whose output had `MISSING 0` — a `RETIRE` from a reparse is
  possible (a row the new parser drops), so it is shown in red and needs an explicit tick.
- **Output:** the run log already prints `re-parse with <parser>: N matched, M changed` and one line
  per proposal; the Jobs page renders the counts by kind (`SHIFTED`/`NEW`/`MISSING`) above the raw
  output.
- **Entry point:** a **Re-read** action on each ingest-history row (Jobs page), which fills
  `evidence`, `sourceType` and `account`.

### 4.3 Changes
- `IngestsResponse.IngestRow` gains `sourceType` (from the `start` event; the `ingest_batch` view
  pairs the markers already).
- `JobCatalogue.reparse(config)`; runner `--allow-apply` gate reused.
- `web/js/jobs.js`: the row action, the preview summary, the gated Apply.
- `V2-SPEC.md` §16: `reparse` joins the job list.

### 4.4 Tests and acceptance
- `JobCatalogueTest.reparseArgvPreviewAndApplyGate` (argv shape; `apply` refused without
  `--allow-apply`; unknown evidence refused).
- `HubIngestsApiTest` asserts `sourceType` on a row.
- Manual (dev stack): **Re-read** the ING card evidence → preview `2438 matched, 0 changed` (the
  repair is already applied), Apply stays disabled; on a scratch copy of the master-built journal,
  preview shows `SHIFTED 54 · NEW 108 · MISSING 0`, Apply posts them, a second preview shows 0.

**As built (2026-10-09).** `JobCatalogue.reparse(config)` is a validated builder over the existing
subcommand: `ingest --reparse <evidence> --source-type <t> --account <a> --evidence <dir> --journal
<journal> --sequencer-url <url>` (+ `--apply`), effect `write`, step label `ingest --reparse
<mode>`. `mode` is a choice (`preview` default); `apply` without `--allow-apply` refuses with the
egress wording; blank `evidence`/`account`, an unknown `sourceType` and a missing
`--journal`/`--sequencer-url` refuse; the evidence id must be one `EvidenceStore.contains` finds —
the same refusal `IngestCommand --reparse` makes — so an unknown or mistyped id never queues a run.
The batch's `sourceType` is read per row with a correlated select over the `start` marker in
`HubQueries.ingests`; the `ingest_batch` view pairs the markers but does not carry that column, and
the frontier plan's precedent ("no change to `ingest_batch` or any table") kept it that way —
`IngestsResponse.IngestRow` gained `sourceType` between `accountRef` and `nStart`. The Jobs page
gains a **Re-read** action on each ingest row (disabled when the batch has no evidence id, source
type or account) that starts the preview with the row's triple; the reparse card keeps the last
preview in session (`lastPreview`, the `lastPlan` shape), shows its counts, and enables Apply only
for a preview with exit 0, `changed > 0` and `MISSING 0` — the manual acceptance's "0 changed"
case keeps Apply locked. A `MISSING > 0` preview shows a red count (a summary chip and one red
line) and a tick; Apply unlocks only once it is ticked. A failed preview, a bad-rows run or a
failed detail read never unlocks, and a successful apply resets the state. The summary above the
run output is parsed from `IngestCommand.reparse`'s own lines (`re-parse with <parser>: N matched,
M changed`, then `  KIND  id  detail`) and is also shown when an old reparse run is opened from
History. **Schedulable, like egress** (operator decision, 2026-10-09): a `schedule.yaml` entry may
run `reparse` with its params, including `mode: apply`; that path is gated only by `--allow-apply`
and bypasses the preview and the `RETIRE` tick, which are the manual path's safeguards.
`EvidenceStore` now refuses any id that is not exactly 64 lowercase hex characters after
`sha256:`, so a traversal or mistyped id cannot escape the store for any caller (the CLI
included); `JobCatalogue` still catches the refusal. Pinned by
`JobCatalogueTest.reparseArgvPreviewAndApplyGate` (exact argv, the default mode, the apply gate,
unknown/mistyped evidence and params), `HubIngestsApiTest` (both rows carry `sourceType`),
`EvidenceStoreTest` (a traversal, uppercase, short, long or unprefixed id is refused; a stray
filename is bad evidence, not a crash) and `ScheduleTest.reparseEntryCarriesItsParams` (a
`reparse` entry with its params loads). The UI was not checked in a browser and no reparse ran
against the live journal; the manual acceptance above is still to run on the dev stack.

---

## 5. Q5 — "Since you last cleared"

### 5.1 Why
The all-clear is a finish line; the next morning needs a starting line. Without one, opening the app
means scanning Blotter, Review and Expected to learn whether anything happened — the habit the daily
routine is meant to replace.

### 5.2 Design
- **The marker is per viewer and lives in the browser** (`localStorage`: `trex.clearedAtN`,
  `trex.clearedLeft`), set whenever the status strip shows **all clear**. Not the log (it is not a
  fact or a conclusion) and not the index (nothing there may be non-disposable). A new device simply
  starts without a marker.
- **`GET /api/since?n=<n>`** (a read): facts appended after `n` (total and per account), batches
  ingested after `n`, review items opened after the time of line `n`, occurrences that turned
  `occurred` or `missed` since, and decisions after `n` by user (so your own clearing is not news).
- **The summary line**, above the Expected headroom and on the Blotter, shown once per visit until
  dismissed:
  > **Since Tue 08:40:** 47 new rows (ing-salary, bw-credit-card) · 2 new items · Netflix and NIB
  > paid · left this month −$120 (was −$95)
- "left this month was" compares with `trex.clearedLeft`; omitted when the month rolled over.
- Nothing is shown when nothing happened: "Nothing new since Tue 08:40." is the calm answer.

### 5.3 Changes
- `HubQueries.since(n)`, `HubSql` (four small queries over `fact`, `ingest_batch`, `review_item`,
  `commitment_occurrence`), `GET /api/since`.
- `web/js/since.js`; `status.js` records the marker on all-clear.

### 5.4 Tests and acceptance
- `HubSinceApiTest`: a journal, a marker `n`, then more facts, an item and an occurrence → the
  counts; a marker at the head → all zero.
- Manual: clear the queue, ingest a statement, reload → the line names the new rows and items.

**As built (2026-10-09).** `GET /api/since?n=<n>&user=<id>` is a pure read over the disposable
index, served by a new `SinceResponse`: the marker line's time (`at`), new facts as a total
(`rows`) and a per-account list, completed batches (`batches`), review items opened after the
marker's time (`items`), the occurrences that turned `occurred`/`missed`, decisions after `n`
excluding the acting user's own (per user), and the month's `headroom`. An absent or non-numeric
`n` is 422 ("n is required" / "n must be a number"), the `sinceN` convention. The marker's time
comes from whichever level-1 table holds line `n` — `fact.at_ms`/`ingest_event.at_ms` as
millis, `decision.at` as an instant; a line that is not in the log (`n <= 0`, past the head, or
a rebuilt journal that no longer has it) is **not an error**: `at` is null and every count is
zero (treat as nothing), and the client shows nothing until the next all-clear resets the
marker. Review items compare through SQLite `julianday(opened_at)`: `opened_at` is
`Instant.toString()` text whose fractional-second groups do not collate (".500Z" sorts before
"Z"), so a plain text comparison would misplace a boundary. `occurred` counts when the fact the
matcher attached is after `n` in `txn_current`; `missed` counts when the window closed after the
marker's **UTC** date — the calendar the derive measures windows in. The month's headroom is the
Expected view's own read, factored into one `monthHeadroom` so the two can never drift.

On the client, `since.js` captures `trex.clearedAtN`/`trex.clearedLeft` at **module load** —
before `boot()` runs, so before `status.refresh()` can rewrite them — and `boot()` fetches once
per visit (`since.load(ctx)`) *before* `status.refresh()`, so the all-clear write of
`trex.clearedLeft` has the fetched left to store; `status.js` moves `trex.clearedAtN` to the
head on every all-clear and records the last known left beside it. The cached read renders one
line above the Expected headroom and at the top of the Blotter: "Since Tue 08:40: 47 new rows
(ing-salary, bw-credit-card) · 2 new items · Netflix and NIB paid · left this month −$120 (was
−$95)". The parenthetical comes from the stored `trex.clearedLeft` and is omitted when the
marker's month is not the current one or nothing is stored; zero news says "Nothing new since
Tue 08:40."; a dismiss is module state, so it hides the line for the rest of the visit only; a
new device has no marker and no line. Pinned by `HubSinceApiTest` (a fixture with a mid-journal
marker: 5 new facts split over two accounts, 1 batch, 2 items opened after it — a pre-marker
duplicate pair stays out — the Netflix occurrence that turned `occurred`, the one that turned
`missed` after the marker's date, and only Anna's decision, not ron's; a marker at the head →
all zero; a marker line that is gone → null `at` and zeros; absent/non-numeric `n` → 422). The
UI was not checked in a browser, and the manual acceptance above is still to run on the dev
stack.

---

## 6. After this plan

Freeze features for a month (the 90% line's rule) and use it. Q5 in particular should be re-judged
against how the routine actually feels — it is the easiest of the five to make noisy.
