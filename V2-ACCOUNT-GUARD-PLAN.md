# V2-ACCOUNT-GUARD-PLAN.md

An **account guard** for file ingest: before a statement is posted, check that its rows belong to
the account it is being ingested as, and refuse a file that plainly belongs to another account.

**Authority:** `V2-SPEC.md` §6.7 (the balance chain), §10 (ingest), §16 (runner, staging inbox),
§9.3 (Jobs); `AGENTS.md`. **Status:** G-1…G-7 accepted as recommended (operator, 2026-10-17);
**built and merged to `master`** (PR #188, 2026-10-17). Where this plan and `V2-SPEC.md` §10 differ, the
spec wins; the as-built differences are in the **As built** section at the end.

## 0. Why

- Six ING accounts share one format (`ing-csv`, header `Date,Description,Credit,Debit,Balance`).
  Nothing in an ING file names its account; the only link is the filename map in
  `statements.yaml`, or the account the operator picks in the inbox. A renamed download, a
  mis-pick, or a glob that is too wide posts a whole statement onto the wrong account.
- Today that is caught only **after** the fact: derive raises `BALANCE_BREAK` on the target (and the
  real account shows a hole in its weekly strip). By then the facts are in the log, and the cleanup
  is a `RETIRE` per fact plus a re-ingest. Nothing is lost, but it is noisy, and a mis-ingest into a
  quiet account can sit unnoticed.
- Every adapter (`ing-csv`, `bw-csv`, `cba-csv`, `cba-pdf`) reads a running **balance** on every
  row. A statement's balance chain is a fingerprint of its account: it has to connect to what that
  account already has.

## 1. Where it lives (invariants)

- **Not in the sequencer.** The writer never interprets (`AGENTS.md`). The guard is an ingest-side
  check placed exactly where the bad-row check is today (`IngestRunner`: evidence → `start` →
  parse → **guard** → facts → `complete`). A refused file posts **no facts** and closes its batch
  with `complete` `status: guard_refused`, the same way a bad-row file closes with `bad_rows` — the
  log stays self-documenting and the ingest history shows the refusal. The evidence is stored, as
  for a bad-row file; that is what lets **Ingest as X** reuse the bytes.
- **Reads the journal, read-only**, like `--reparse` already does (`IngestCommand.readJournal`
  over `FramedReader`). No index, no hub call: the log is the only truth, and the guard works on a
  freshly rebuilt box. The runner already mounts the journal read-only.
- **Pure check.** `AccountGuard.check(drafts, chosenAccount, currentFactsByAccount) → Verdict` in
  `trex-v2-ingest`: no clock, no I/O, ordered iteration. The CLI does the reading.
- **Current facts only:** posted, `provenance: BANK`, not closed by an effective
  `SUPERSEDE`/`RETIRE` (reuse `Reparse.closedIds`). Manual cash and synthetic legs never count.
- **Downstream:** none. No derive, identity or unit change; ezbookkeeping sees nothing new.

## 2. The check

For the file: its rows' `(balance − amount, balance)` edges, as §6.7 uses them, give the file's own
**opening** (the one previous balance no row in the file closes) and its date span.

For each candidate account `A` with current facts:

1. **Overlap:** some file row matches a fact on `A` on `(date, amount, balance)` (R1: the
   description is not part of the key, so a parser upgrade does not turn an overlap into a
   contradiction). Statements are usually downloaded overlapping, so this is the common,
   strongest proof.
2. **Connects:** the file's opening equals the balance of `A`'s newest current row dated on or
   before the file's first date (the chain continues).
3. **Contradicts:** `A` has current rows dated inside the file's span, and none of the file's rows
   on those dates is identical to them (the account was already covered there by different rows).

**Weak edges do not count as proof (G-3):** a balance of `0.00` (a paid-off card, an emptied saver)
matches too many accounts, so a connection through a zero opening is ignored.

Verdict for the chosen account `C`:

| Verdict | When | Effect |
|---|---|---|
| `FITS` | `C` overlaps or connects | post, as today |
| `ELSEWHERE` | `C` does not fit, and exactly one other account `X` overlaps or connects | **refuse**, naming `X` |
| `NEW` | `C` has no current facts | post (first ingest of an account) |
| `CONTRADICTS` | `C` contradicts, nothing else fits | **refuse** |
| `UNPROVEN` | none of the above (a gap since the frontier, a broken source chain) | G-1 |

The table is the evaluation order (R3: `ELSEWHERE` before `NEW` — an empty chosen account with a file
that fits another account is the classic mis-pick; **Ingest anyway** overrides a genuinely new
account's coincidence). `ELSEWHERE` with two or more other accounts fitting is reported as `UNPROVEN` with the candidates
named — the guard never guesses.

The refusal message is the whole UX on the CLI:

```
account guard: ING_Salary_Account.csv does not fit ing-salary
  fits ing-orange: 14 rows already ingested there (2026-09-28..2026-10-02)
  re-run with --account ing-orange, or --account-guard=off to post anyway
```

### 2.1 The overlap convention (operator, 2026-10-17)

The operator downloads every statement **overlapping** the account's frontier (the newest row
already ingested) wherever the bank allows it. This adds nothing to the log: identical rows come
back `Duplicate`, which is existing writer bookkeeping. What it changes for the guard:

- **Overlap becomes the normal proof.** `FITS` almost always comes from identical rows, not from the
  balance connection, so the zero-balance weakness (G-3) and a coincidental balance match matter
  less.
- **`UNPROVEN` becomes the exception.** On an account with history, a file with no overlap either
  broke the convention or left a gap. It is still posted (G-1), but the job output and the Jobs row
  show it in amber: `posted, no overlap with ing-salary (frontier 2026-09-30, file starts 2026-10-03)`.
  The amber marks the file to check; it is not a refusal.
- **Helping with the convention costs nothing:** the Jobs fetch-frontier table (§16,
  `V2-INGEST-FRONTIER-PLAN.md`) shows a **fetch from** date per account (the frontier minus a few
  days, so the download overlaps). Presentation only, computed by a read.

### 2.2 Identify: which account is this file? (operator, 2026-10-17)

The guard already scores the file against every account (that is how it names `ELSEWHERE`). The
same function, with no chosen account, answers "which account is this?":

```
$ trex ingest --identify --source-type ing-csv --journal … ING_export(3).csv
ING_export(3).csv
  ing-orange       FITS         14 rows overlap (2026-09-28..2026-10-02)
  ing-salary       CONTRADICTS  has other rows on 2026-09-29..30
  ing-savings      no evidence
→ ing-orange
```

- **A dry run:** parse + score, writes nothing (no evidence, no events). `--format json` gives
  `{file, sourceType, candidates: [{account, verdict, overlapRows, span}], suggestion}`;
  `suggestion` is the single account that `FITS`, else `null` (none, or two or more).
- **Exit codes:** `0` whenever it ran (the answer is in the output, `suggestion` may be null);
  `1` bad rows, `64` usage, as for ingest. No new code.
- **One function, two entry points:** `--identify` and the guard inside a real ingest call the
  same `AccountGuard`, so the suggestion and the refusal cannot disagree.
- **Runner:** an `identify` job (params `name` of a staged file, `sourceType`), the subcommand and
  nothing else — like `reparse`. No change to the runner rules (G-8).

**Hub (G-8, option 2).** The Jobs inbox view calls the `identify` job (sync) for each new upload and
prefills the row's account from it:

- one clear `suggestion` → prefilled, marked *identified*;
- `null` → falls back to the `statements.yaml` filename match, as today;
- suggestion and filename match **disagree** → amber on the row, the suggestion is prefilled, both
  are named.

The suggestion is only that until **Ingest** is pressed: the row's account goes in the `ingest` job
item as usual (as built, nothing is written to the sidecar — R4). A scheduled, unattended sweep
does not identify; the guard inside ingest still protects it. With identify prefilling the
account, `statements.yaml` becomes the fallback and a renamed download stops mattering.

## 3. Overrides

- `trex ingest --account-guard=off` posts whatever the verdict (a deliberate correction, a
  restated statement). `--account-guard=check` parses and guards and writes nothing — no evidence, no events (a dry run; it
  prints the verdict and exits `0` on `FITS`/`NEW`, else the guard code).
- In the runner (R4, as built), the `ingest` job's items carry `account` and `guard: "off"`, set
  from the Jobs page (§4); `StagingStore.resolve` also finds `failed/` so a refused file can be
  re-run. No sidecar keys. Nothing else in the runner changes: it still only runs the subcommand and
  files by exit code.

## 4. Jobs page

- A refused file is filed to `failed/` like a bad-row file (G-2) and its row shows the verdict
  text. Two actions on that row:
  - **Ingest as `ing-orange`** — when the verdict named one account: runs the `ingest` job for
    that one file with `account` set (R4).
  - **Ingest anyway** — runs it with `guard: "off"`; a confirm names the account it will land on.
- No new pre-flight step on the happy path: a file that fits ingests exactly as today, with no
  extra click. (A per-row **Check** button on the inbox, running `--account-guard=check`, is cheap
  to add but deferred until the refusal flow has been used — G-6.)

## 5. Decisions for the operator

- **G-1 `UNPROVEN`: post or refuse?** Recommend **post, and say so** in the job output (`posted,
  account guard unproven: gap since 2026-08-31`). A gap after a missed month is legitimate and
  refusing it would add friction to the everyday path; the hard refusals cover the real mix-ups.
- **G-2 Exit code.** Recommend a new code **`4` (guard refused)**, and the `ingest-inbox` sweep
  files `4` to `failed/`. Reusing `3` would blur it with a sequencer rejection; a code the sweep
  does not file would be retried on every sweep, forever.
- **G-3 Zero balances are not evidence.** Recommend yes, as in §2.
- **G-4 `--journal` in the runner.** Recommend the runner always passes `--journal` to `ingest`;
  on the bare CLI the guard is skipped with one line on stderr when `--journal` is absent (old
  scripts keep working).
- **G-5 Record the verdict?** Recommend yes: the ingest `complete` event carries an optional
  `guard` (`fits | new | unproven | off | elsewhere:<ref> | contradicts`), and a refusal uses the
  new `status: guard_refused`. An ingest event says what the ingest did, so this stays inside the
  log's three kinds; it is a line-format addition, documented in §10. The frontier and the ledger
  are unaffected (no facts).
- **G-6 Inbox Check button.** Recommend deferred (§4).
- **G-8 Who triggers identify. Accepted: the hub** (operator, 2026-10-17). The hub's inbox view
  calls the runner's `identify` job per new upload. The rejected alternative made upload →
  identify a second runner composition, which would amend §16's "never new logic; `ingest-inbox`
  is the one allowed composition". The hub route leaves the runner rule intact.
- **G-7 Scope.** File ingest only. Feeds (`FeedRunner`, the BankSync trial) map accounts by the
  provider's own account id, a different failure mode; `--reparse` replays a batch onto the account
  it recorded and is not guarded.

## 6. Later (not in this plan's stages)

- **Account-number hint for PDFs.** CBA statements print the BSB and account number; a
  `numberHint` (last 4 digits) in `accounts.yaml` would make a PDF mismatch a hard, balance-free
  refusal. Measure on a fixture first that the Transaction Summary PDF carries it in extractable
  text.
- **Re-home a batch.** A Jobs action on an ingest-history row that already landed on the wrong
  account: `RETIRE` every current fact from that evidence on the wrong account, then ingest the
  stored evidence onto the right one. New ids are minted there (the account is part of the id), the
  old ones are retired, nothing is rewritten. A plan of its own if the guard leaves a need for it.

## 7. Stages

1. **Guard core.** `AccountGuard` + `Verdict` in `trex-v2-ingest`, pure. Tests: overlap, connect,
   contradict, `ELSEWHERE` naming one account, two candidates → `UNPROVEN`, zero opening ignored,
   retired/superseded and manual facts ignored, `NEW`. A fixture pair: an ING salary file and an
   orange file, each ingested as the other.
2. **CLI.** `IngestCommand`: `--journal` and `--account-guard=on|off|check` on file ingest; exit
   `4`; the message in §2; `guard` and `status: guard_refused` on the `complete` event (G-5). E2E: a
   wrong-account ingest exits `4` and appends only its `start`/`complete` pair, no facts.
   `--identify` (text and `--format json`, §2.2): the fixture pair each identified correctly, the
   journal unchanged.
3. **Runner.** `ingest`/`ingest-inbox` pass `--journal` and the sidecar's `account`/`guard`; the
   sweep files `4` to `failed/`; the `identify` job. Test: a refused file lands in `failed/` and is
   not retried; `identify` on a staged file returns the JSON.
4. **Jobs page.** The inbox prefill from `identify` with the *identified* mark and the
   disagreement amber (§2.2); the verdict on a failed row, **Ingest as X**, **Ingest anyway**; the
   amber no-overlap note on posted rows and the **fetch from** column (§2.1).
5. **Spec.** `V2-SPEC.md` §10 (the guard, `--identify`, exit `4`, the `guard` field), §16 (sweep
   filing, sidecar keys, the `identify` job), §9.3 (Jobs inbox prefill and row actions) — in the
   same PR as the behaviour.

## 8. As built (2026-10-17)

`V2-SPEC.md` §9.3, §10, §16 are the contract. Rulings made while building, and where the code
differs from the text above:

- **R1** match key `(date, amount, balance)`. **R2** a zero balance is never evidence (an overlap row
  or an opening). **R3** `ELSEWHERE` before `NEW`. **R4** `Ingest as X` / `Ingest anyway` use the
  existing `ingest` job items (`account`, `guard: "off"`), no sidecar keys; `failed/` files resolve.
  A consequence: an identified account is not remembered by the unattended sweep (the guard still
  protects it).
- **R5** identify reaches the hub as one JSON line on stdout (`--format json`), read from the run's
  output; the `identify` job has mode `read`.
- **R6** `guard` is a nullable `IngestEvent` field, written only when non-null.
- **R8** with `--account-guard=off`, the bad-row and empty-file completes carry no `guard`: the guard
  never ran on those paths. **R9** an index built before the `guard` column re-mirrors from the log
  once on its first open.
- The identify JSON is `{file, sourceType, candidates: [{account, verdict: fits|contradicts|none,
  overlapRows, overlapFrom, overlapTo, connects}], suggestion}`; there is no `span`.
- `--account-guard=check` needs `--account` and `--journal`; it exits `0`, or `4` on a refusal.
- The posted-`unproven` note in the Jobs history reads *no overlap — check this statement*, not the
  frontier-and-start wording in §2.1; the CLI note carries the newest row and the file start. `new`
  has a CLI note and no history mark. The runner passes `--journal` whenever it has one.
- **Fetch from** is the frontier minus 7 days. Refused rows are linked to their batch by evidence id
  among the newest page of `/api/ingests`; an older refusal shows as a plain `failed`.
- Not built: the per-row **Check** button (G-6), the account-number hint and re-home (§6).
