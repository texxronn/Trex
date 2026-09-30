# V2-INGEST-PROVENANCE-PLAN.md

> **Personal project, single operator, private.** The proposal is the specification; this file is
> the build order for one change to it. Where this file and `V2-PROPOSAL.md` disagree, the
> proposal wins — and for this change the proposal is *deliberately amended* (§3), so read that
> section before anything else.

**Status:** stage 0 applied — `AGENTS.md` and `V2-PROPOSAL.md` are edited (§12); implementation not started.
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
8. **A uniform envelope** on every line, **header fields first**: `n, kind, v, atMs, env, source,
   target` (§4.1). `atMs` is epoch millis (UTC by definition) and is the only time logic may read;
   `at` (ISO-8601 UTC, millisecond) stays an **optional body field for readability**, never used in
   logic — so the timezone question disappears. `v` is a single line-format version, **reset to 1**.
   `kind` is **namespaced and mandatory** (`trex.fact`, `trex.decision`, `trex.ingest`). The dev
   journal is transformed to match (§12); decisions gain the version they never had.
9. **`env`, `source`, `target`** are each `[A-Za-z0-9_]{1,8}`, **right-padded with spaces to exactly
   8**. `env` is the environment (`Dev1`, `Prod1`; mandatory), `source` the writing process instance
   (mandatory — several ingesters of the same `sourceType` have different `source`s), `target` the
   destination stream/consumer (optional; empty ⇒ 8 spaces). Declared in a registry and validated by
   the sequencer. Case is significant; uppercase is the convention. The little-endian
   `source`+`target` composite is **not used here**. **Space-padding is the deliberately fragile
   choice** (§14): it makes `none` sort lowest, and it means *every* short code carries trailing
   whitespace that a plain YAML scalar, a CSV field or a `trim()` would eat.
10. **Weekly full journal snapshots**, independent so any can be deleted, plus the mirror kept
    forever and an explicit `prune-archive` job driven by a keep-N config (§8, §11).
11. **One sequencer, one log, one global `n`.** A sequencer never serves multiple event streams;
    scale is more sequencer instances, each with its own log and its own `n` (the `env` field
    distinguishes them). `target` stays an annotation on a single log, never a partition key, and
    there is **no stream id in the envelope**.
12. **The sequencer stays trex-aware.** Identity (`externalId`), `occ`, whole-observation dedup,
    registry/referential validation and the fold state all remain in `trex-sequencer`. The
    generic-core + application-SPI split is a **separate project** (with the externalized log);
    only the envelope (§4.1) is made generic now. The `AGENTS.md` "no state / no duplicate flags"
    invariant is **narrowed**: no *semantic* state (matching, category, derived review flags); the
    writer keeps only its identity/observation index (§12, §14).

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

## 4. The uniform envelope, and the `ingest` kind

### 4.1 The envelope

Every line carries the same header, written by the sequencer (it owns `n` and stamps the time):

| field | meaning |
|---|---|
| `n` | sequence number; the cursor and the only ordering |
| `kind` | namespaced kind: `trex.fact` \| `trex.decision` \| `trex.ingest` |
| `v` | the line-format version; **1** for the uniform frame |
| `atMs` | event time, epoch millis (UTC) — the only time logic reads |
| `env` | the environment; 8 chars (`Dev1    `) |
| `source` | the writing process instance; 8 chars (mandatory) |
| `target` | the destination stream/consumer; 8 chars (`none` = 8 spaces) |

```
{"n":1892,"kind":"trex.fact","v":1,"atMs":1780000000123,"env":"Dev1    ","source":"ING_0001",
 "target":"        ","at":"2026-09-30T10:31:00.123Z","externalId":...}
```

- **`v`** answers one question: *can this reader parse this line?* Any shape change bumps it;
  additive optional fields do not. Reader policy (§13): unknown `kind` → skip + warn; known `kind`
  with a higher `v` → **refuse**; unknown fields → ignore; never reuse a field name.
- **`source`** is the *writing process instance*, a technical id — not the origin of the data.
  Orthogonal to `sourceType` (the data source/format), `parser` (adapter+version) and `provenance`
  (BANK/AUTHORED). A **`sources` registry** (config) maps the 8-char code to a human label, and the
  sequencer refuses an unregistered code — so a typo cannot invent a source. Several ingesters of
  the same `sourceType` carry different `source`s (e.g. `ING_0001`, `ING_0002`).
- **`target`** is the destination stream/consumer; `none` is **exactly 8 spaces** (`"        "`),
  which sorts below every code. It is deliberately fragile: the sentinel must never be carried
  through anything that trims whitespace (a plain YAML scalar, an HTTP header, an unquoted shell
  var), and **no code may `trim()`/`strip()` a `source`/`target`**. A JSON string and a SQLite
  `TEXT` column preserve it; `verify` asserts it is exactly 8 spaces.
- Character rules: `[A-Za-z0-9_]`, 1..8 chars, **right-padded with spaces to 8** at the storage
  boundary. Case is significant (`ING_0001` ≠ `ing_0001`); uppercase is the convention.
- Header fields are written **first and in a fixed order** (`n, kind, v, atMs, env, source,
  target`), then the body. `kind` is namespaced: the registry keys on the full string; a reader
  skips an unknown namespace and refuses a *known* kind at a higher `v`.
- **`atMs` is authoritative; `at` is decoration.** Logic reads only `atMs` (epoch millis, TZ-free);
  the optional body `at` is ISO-8601 UTC (`Z`) purely so a human reading `tail` sees a date, and is
  never parsed for decisions, hashing or ordering.
- **The little-endian `source`+`target` composite is not used in trex** (§2.9); the fields are
  carried for a future routable log, whose byte order is decided there.
- **Determinism**: `n`, `at` and `atMs` never enter `stateHash` or `derive()`; order is always `n`.

### 4.2 The `ingest` kind

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
  - §6: the uniform envelope (`n, kind, v, atMs, env, source, target`), the mandatory namespaced
    `kind`, and the `env`/`sources` registries;
  - §6.7 (every sequencer event): add `start`/`complete` rows;
  - §5.4: the archive subdirs and the source archive;
  - §7.2: `ingest_event` + `ingest_batch`;
  - §11: the maintenance snapshot (copy, not rotation);
  - §12: the ingest event pair and the source archive;
  - §14: the runner scheduler and the snapshot rhythm;
  - §19: reverse "no scheduler framework" to admit the **bounded** job runner (§9.1);
  - §17/`RELEASE.md`: the major bump and forward-compatibility note.
- **Dev journal transform** (one-off, dev only): rewrite the existing journal to the uniform
  envelope — add `v:1`, `atMs` (from the old `ingestedAt`/`at`), `source`/`target` defaults; drop
  the per-body version. A byte-level *format translation* of a disposable replica (§2.8); the host
  path is a fresh journal plus re-ingest, not a rewrite of the live log.
- `AGENTS.md`: the invariant bullet in §3, the narrowing of "the writer never interprets — no
  state, no duplicate flags" to the semantic senses (§2.12), and the §19 anti-goal reversal above.
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
10. Every line carries the header first, in order: `n, kind, v, atMs, env, source, target`; `kind` is
    namespaced (`trex.fact`); `v` is `1`; `env`/`source`/`target` are right-padded to exactly 8 and
    registered — an unregistered `source`, or a value over 8 chars, is refused at write. The `none`
    target round-trips as exactly 8 spaces through the log, the index and any export (no trimming).
    Logic reads `atMs`, never the optional body `at`. A known kind with a higher `v` is refused; an
    unknown namespace or kind is skipped per the reader policy.

## 14. Risks and open points

- **Concurrency**: interleaved ingests blur the marker range; documented, not enforced (§4).
- **Forward compatibility is now a hard requirement**: the migration/verify/export paths must skip
  unknown kinds rather than reject the line.
- **Scope creep guard**: the scheduler is intervals only, no cron expressions; the snapshot is a
  copy only, no rotation; no pre-ingest snapshot.
- **The fixed-8 `source`/`target` is a bet on the routable product**: in JSON and in SQLite/in-memory
  maps it buys nothing on its own; the payoff is a 16-char `source||target` routing key. Bounded as
  *fields only* — the externalized, routable log remains a separate project (§1), and this change
  must not grow multi-stream, routing or producer auth.
- **`env`/`source`/`target` are right-padded with spaces**, so *every* short code carries trailing
  whitespace, not just `none`. Ordinary tooling mangles it: quoted JSON and a SQLite `TEXT` column
  are safe, but a plain YAML scalar, an HTTP header, a CSV field or an unquoted shell var is not.
  Rule: never trim, always quote, assert the padded length in `verify`, and normalise in one place.
  If it proves brittle, the fallback is `_`-padding (fixed width, survives everything, but loses the
  sort-low property) or requiring exactly 8 characters.
- **The writer is trex-aware**: `Sequencer.java` mints identity, assigns `occ`, dedups whole
  observations and validates against config — none of that is generic. The externalized core is a
  separate project; the envelope work must not imply otherwise.
- **Version**: this is a MAJOR bump; the tag is cut only when the spec edits and code are green.
