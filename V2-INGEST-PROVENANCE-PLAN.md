# V2-INGEST-PROVENANCE-PLAN.md

> **Personal project, single operator, private.** The proposal is the specification; this file is
> the build order for one change to it. Where this file and `V2-PROPOSAL.md` disagree, the
> proposal wins — and for this change the proposal is *deliberately amended* (§3), so read that
> section before anything else.

**Status:** proposed; no code changes yet.
**Authority:** `V2-PROPOSAL.md` §4, §5.4, §6, §7.2, §11, §12, §14; `AGENTS.md`; `RELEASE.md`.
**Touches an invariant:** yes — the log gains a third line kind (§3). This is a **major** log-format
change per `RELEASE.md` (`externalId` and the log's history remain permanent; readers must tolerate
the new kind).

---

## 1. The change, in one paragraph

An ingest becomes **self-documenting on the stream**: the ingest client appends an
`ingest`/`start` event, then its facts, then an `ingest`/`complete` event with counts. The two
events bracket the batch, so the `n` range, the source file, the account and the outcome are all in
the ordered log — `tail` tells the whole story. Separately, the **source file** is gzipped to a
configured archive location under a dated, human name, and the **journal** can be snapshotted
(a consistent, gzip copy — never a rotation) through a new sequencer maintenance API. Both the
maintenance call and the runner's job call support **sync** (wait up to a configurable timeout,
10 s by default) and **async** (return a handle). The scheduler lives in the **runner**, not the
sequencer.

## 2. Locked decisions

1. **Batch markers on the stream**, not manifests. The log gains the `ingest` kind (§3–§4).
2. **No rotation.** The live journal stays one append-only file; "archival" is a copy.
3. **Naming is automated by the ingest client** (so a CLI ingest and a UI ingest behave identically);
   the runner only supplies the archive location and the original name.
4. **Timeout configurable, default 10 s**, on every sync call; on timeout the call returns the run
   handle rather than hanging past it.
5. **Sequencer exposes the maintenance API; the runner holds the scheduler.**
6. No pre-ingest snapshot — appends cannot corrupt prior lines.
7. **The daemon stays `trex runner`**, and it *is* a workflow engine in a bounded sense
   (§9.1): ordered steps, manual and interval triggers, sync/async, artifacts. The name is
   kept deliberately — it is the industry word for the process that executes jobs/workflows
   (GitHub Actions runner, GitLab Runner), not an undersell.

## 3. The invariant amendment (do this first)

`AGENTS.md` today: *"The log holds **facts** (what a source said) and **decisions** (what a person
concluded). Nothing else. The writer never interprets — no matching, no state, no category, no
duplicate flags in `trex-sequencer`."*

Proposed replacement:

> The log holds **facts** (what a source said), **decisions** (what a person concluded), and
> **ingest events** (what an ingest did). Nothing else. The writer never interprets: it appends each
> line verbatim. An ingest event is bookkeeping about a process, not a fact about the world and not
> a person's conclusion — it is a third, explicitly enumerated kind, and every reader must tolerate
> unknown kinds.

`V2-PROPOSAL.md` §6 must gain the same wording; §4's "log as truth" and §6's "two line kinds"
paragraphs are corrected in the same edit. This is a **major** version bump.

## 4. The `ingest` line kind

```json
{"v":1,"kind":"ingest","n":1892,"phase":"start","batch":"ING-9f3c…",
 "evidence":"sha256:261f…","file":"Orange_Everyday.csv","account":"ing-orange",
 "sourceType":"ing-csv","parser":"ing-csv/3","actor":"ron","at":"2026-09-30T10:31:00Z"}

{"v":1,"kind":"ingest","n":1899,"phase":"complete","batch":"ING-9f3c…",
 "appended":2,"duplicate":0,"flagged":0,"status":"ok","at":"2026-09-30T10:31:02Z"}
```

- **`batch`** is minted by the ingest client and links the pair (it survives a retry and names a
  0-fact run). `evidence`, `file`, `account`, `sourceType`, `parser` are on `start`; counts and
  `status` on `complete`. Both carry `actor` and `at` like a decision.
- **The `n` range is the markers themselves**: facts sit strictly between `start.n` and
  `complete.n`. No separate offset field — `n` is stable, a byte offset is not.
- **`status`** mirrors the ingest contract: `ok | bad_rows | duplicate | transport | rejected`.
  A file that fails validation still emits `start` + `complete` (`status":"bad_rows"`, zero counts):
  the *attempt* is the audit. A crash between the two leaves a dangling `start` — which is exactly
  the truth, and reads as an incomplete batch.
- **One ingest at a time** is assumed (the runner already serializes; the operator has one terminal).
  The markers do not carry a batch id per fact, so interleaved ingests would blur the range. This is
  documented, not enforced — enforcement would add state to the writer, which §3 forbids.

## 5. Write path

- New sequencer endpoint `POST /ingest` (sibling of `/facts`, `/decisions`): validate the shape,
  append the line, return `{n}`. The writer appends verbatim; it holds no open-batch state.
- `IngestRunner` gets three additions, all optional so existing callers are unaffected:
  `POST /ingest` (start) after evidence is stored and before the first `/facts`;
  the existing day-batched `/facts`;
  `POST /ingest` (complete) with the accumulated counts, even when nothing was sent.
- Failure semantics unchanged: a transport failure after `start` leaves the batch open and the
  client surfaces the error; the next run emits a fresh pair.

## 6. Index and verify

- **Level 1** mirrors the log row for row, so `schema.sql` gains an `ingest_event` table (and the
  `Indexer` its level-1 apply). A derived **`ingest_batch`** view pairs the markers:
  `batch, file, evidence, account, nStart, nEnd, appended, duplicate, flagged, status, startedAt,
  completedAt, duration`.
- `derive()` ignores ingest events entirely (they are not transactions). The disposable-index rule
  holds: dropping and rebuilding reproduces the view from the log.
- `trex verify` compares the mirrored `ingest_event` rows like any other level-1 table, and can
  assert every `complete` has a `start`.

## 7. Source-file archive

- On ingest, gzip the exact source bytes to `<archive>/sources/<Y>/<M>/<D>/<HHMMSS>-<name>.gz`,
  where `<name>` is the original name. The ingest client owns this (§2.3):
  `--source-archive <dir>` and `--source-name <original>` (defaults to the file basename).
- The **runner** passes both, taking `--source-archive` from its config and `--source-name` from the
  staging sidecar, so an uploaded `Orange_Everyday.csv` is archived under its human name, not the
  generated one. A CLI ingest gets the same when given the flags.
- **Per attempt, not deduped** — the arrival log is the point, and statements are small. (If storage
  ever matters, key by `sha256` and keep a dated manifest; noted, not chosen.)
- This overlaps the evidence store (same bytes, content-addressed, nameless). That is deliberate:
  evidence is the machine record, the source archive is the human one. Both are covered by the same
  archive backup.

## 8. Journal snapshot (sequencer maintenance API)

- `POST /maintenance/snapshot` produces `<archive>/journal/trex-<datetime>.jsonl.gz` — a consistent
  prefix up to the last fsynced line. The live journal is **untouched**.
- Implementation shape: read the current head offset (a synchronized field), stream bytes
  `[0, offset)` through gzip, all **outside** the append lock, so a large copy never stalls the
  writer. Return `{n, offset, bytes, path}`.
- Sync/async: `?sync=true&timeoutMs=10000`. Sync returns the result if it finishes within the
  timeout, else the job handle; async returns the handle immediately. Default timeout 10 s,
  configurable.
- A snapshot is a **copy**, so it does not bound the live file. That is accepted (§2.2): the log
  stays one file; the archive exists for cold copies and immutability.

## 9. Runner: jobs, sync/async, scheduler

### 9.1 The model, and its ceiling

The runner is a job runner that may grow into a small workflow engine; the vocabulary is
fixed so the growth stays legible:

| noun | meaning |
|---|---|
| **job** | one thing triggered (today: `egress-firefly`, `ingest`, `journal-snapshot`) |
| **step** | one child process inside a job (a multi-file ingest is N steps) |
| **workflow** | an ordered list of steps/jobs — e.g. `snapshot → ingest` |
| **trigger** | manual (UI/API) or scheduled |
| **run** | one execution: state, exit, bounded log, history |
| **artifact** | a staged upload or an archived source file |

**Ceiling (deliberate): ordered steps only.** No DAG, no conditional steps, no first-class
retries, no durable resume, no cron expression grammar. The value is visible, repeatable,
one-`tail` pipelines; adding the rest rebuilds a general workflow engine for one operator and
turns a tool into a system to maintain. This reverses the proposal's §19 anti-goal ("no
scheduler framework") and must be recorded there (§12).

- New job **`journal-snapshot`** (calls the sequencer maintenance endpoint; async by default).
- **Sync/async on the job API**: `POST /jobs/{name}/runs?sync=true&timeoutMs=10000` returns the
  terminal `RunDetail` when it finishes in time, otherwise `202 {runId}` — the same response shape
  either way, so a caller always has a handle. Async (202 + SSE/poll) stays the default.
- **Scheduler in the runner** (§2.5): RunnerConfig gains an optional schedule
  (`schedule.yaml`: `[{job, params, every: "24h"}]`, or `at: "02:30"`), default **empty** (manual
  only). One daemon timer thread; no framework. A sensible default for the host is a daily
  `journal-snapshot`; nothing else is scheduled — `--apply` and ingest stay manual.
- Runner config gains `--archive <dir>` and `--sync-timeout` (default `10s`), passed down to
  `ingest --source-archive` and used by the sync wrappers.

## 10. Hub and UI

- New **"Ingests"** section (under Jobs) rendering `ingest_batch` from the hub: file, account,
  `n` range, counts, status, duration — the self-documenting history, derived from the markers.
- A **"Snapshot journal"** button (calls the runner job) and the snapshot history.
- The hub stays a proxy + derivation owner: it never archives a file and never writes the log.

## 11. Config: the archive location

- One setting, `<archive>` (default the existing `archive` volume, `/var/lib/trex/archive`), with
  subdirs `journal/`, `sources/` beside the existing `archive.jsonl`/`evidence`.
- Passed to the sequencer (`--archive`) and the runner (`--archive` → `ingest --source-archive`),
  in the same `trex.env`/compose style as `TREX_FIREFLY_URL`.

## 12. Proposal and AGENTS edits (stage 0, before any code)

- `AGENTS.md`: the invariant bullet in §3 above.
- `V2-PROPOSAL.md`:
  - §4/§6: "two line kinds" → three, with the `ingest` kind and its shape;
  - §6.7 (every sequencer event): add `start`/`complete` rows;
  - §5.4: the archive subdirs and the source archive;
  - §7.2: `ingest_event` + `ingest_batch`;
  - §11: the maintenance snapshot (copy, not rotation);
  - §12: the ingest event pair and the source archive;
  - §14: the runner scheduler and the snapshot rhythm;
  - §19: reverse "no scheduler framework" to admit the **bounded** job runner (§9.1);
  - §17/`RELEASE.md`: the major bump and forward-compatibility note.
- `AGENTS.md`: the invariant bullet in §3, and the §19 anti-goal reversal above.
- `V2-SPEC.md`: as-built, after the code lands.

## 13. Acceptance tests

1. A clean ingest appends `start`, the facts, `complete`, in that order, with `start.n < facts <
   complete.n`; `tail` reads as one story.
2. A bad-row file appends `start` + `complete{status:bad_rows, 0 counts}` and **no** facts.
3. Re-delivering the same file appends a second pair with `duplicate` counts; `n` does not move for
   the facts.
4. `derive()` is unchanged by ingest events (same rows, same `stateHash`); `trex index --rebuild`
   reproduces `ingest_batch`.
5. The source file is archived under its dated human name; a staged upload uses the original name,
   not the generated one.
6. `POST /maintenance/snapshot` yields a gzip whose decompressed bytes equal the journal prefix; the
   live journal is byte-identical before and after.
7. Sync returns within the timeout; a slow sync returns `202` with a handle instead of hanging.
8. The runner's schedule fires the daily snapshot; with no schedule, nothing fires.
9. **Forward-compatibility**: a reader that knows only `fact`/`decision` ignores `ingest` lines
   (checked against the v1-shaped reader path).

## 14. Risks and open points

- **Concurrency**: interleaved ingests blur the marker range; documented, not enforced (§4).
- **Forward compatibility is now a hard requirement**: the migration/verify/export paths must skip
  unknown kinds rather than reject the line.
- **Scope creep guard**: the scheduler is intervals only, no cron expressions; the snapshot is a
  copy only, no rotation; no pre-ingest snapshot.
- **Version**: this is a MAJOR bump; the tag is cut only when the spec edits and code are green.
