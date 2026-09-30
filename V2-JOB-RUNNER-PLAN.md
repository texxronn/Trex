# V2-JOB-RUNNER-PLAN.md

> **Personal project, single operator, private.** The proposal is the specification; this
> file is the build order for one change to it. Where this file and `V2-PROPOSAL.md`
> disagree, the proposal wins — and the proposal must be edited for this change before
> code lands (§13 below).

**Status:** locked (revision 3 — decisions agreed, spec edited); implementation in progress.
**Authority:** `V2-PROPOSAL.md` §1, §5.2, §5.3, §5.4, §7.4, §11, §12, §14, §19; `AGENTS.md`.
**Supersedes (as the way jobs are scheduled):** the systemd timer drafts
`deploy/v2/systemd/trex-egress-firefly.service` / `.timer`, and the §5.3 sentence
"systemd timers are enough for one person". The batches themselves do not change.

---

## 1. The change, in one paragraph

Add a long-running **job runner** — one new role on the one artifact, `trex runner` — that
exposes a small loopback HTTP **trigger API**, and add a **Jobs** view to the hub UI whose
buttons trigger an on-demand run: **Ingest** and **Firefly egress** (`plan` / `verify` /
`apply`), with more jobs to follow. A job is nothing but an invocation of an existing CLI
subcommand; the runner does not parse, project, derive or interpret. It starts the same
`trex <role> …` a human would type, streams the output to the UI, and records the exit code.
The runner also owns a **staging area** — a web *inbox* — so a statement can be uploaded
from a phone or a desktop, listed, typed, and ingested from the same page without touching
the host's filesystem. The hub proxies the browser to the runner, so the runner never faces
the LAN.

## 2. Why not timers, and what "batch, not a daemon" still means

The operator wants to *see and press* the passes, not discover drift on a schedule. Two
proposal sentences must be reconciled, not silently contradicted:

- §11: "It is a batch, not a daemon… `--plan`/`--verify` are timer-safe; `--apply` runs when
  you say so." **Still true.** The egress pass remains a one-shot batch with no polling and
  no continuous projection. The runner is a *dispatcher* that starts the batch on demand; it
  never projects by itself and never holds Firefly open between runs.
- §5.3: "systemd timers are enough for one person." **Replaced** for these jobs by the
  operator trigger. Timers remain an option for a genuinely periodic pass (e.g. the nightly
  archive mirror), but are no longer the primary mechanism.

The gain is the review loop: **Plan** in the UI, read the diff, press **Apply** — all in one
place, attributed and logged. The staging inbox extends the same idea upstream: the
statement arrives and is ingested from the same page.

## 3. Roles and processes

A new subcommand alongside `sequencer`, `hub`, `ingest`, `egress`, `verify`, `index`,
`reflow`, `export`, `import` (§5.2, §19). One jar, one image, role by command.

| | |
|---|---|
| `trex runner` | always-on; loopback HTTP; **headless** (serves no UI); the only process that starts jobs and stores staged files |
| listens | `127.0.0.1:8091` by default (`--host`/`--port`); never published |
| owns | the job queue, live output, a bounded run history, and the staging inbox — none of it in the journal |
| starts | child `trex` processes (same image/classpath), one worker |

The runner is deliberately **not** the hub: a runaway ingest must not take the blotter down,
and the hub must not hold the index lock while a job wants it. The hub keeps its role (§7.4);
this adds a peer, not a new owner of anything durable.

### 3.1 Threads (J25 virtual threads)

**Already the right model; nothing to convert.** Both HTTP servers already run handlers on
`Executors.newVirtualThreadPerTaskExecutor()` (`HubHttpApi`, the sequencer `HttpApi`), which
is exactly what §1 asks for. The other threads are platform threads *on purpose*:

- `IndexRefresher` runs one `Thread.ofPlatform().daemon(true)` watchdog loop around a
  blocking `WatchService` — a long-lived loop, not request fan-out;
- shutdown hooks are platform threads;
- the derive/index work is CPU-bound and single-threaded behind `synchronized` on `Indexer`.

Virtual threads do not reduce resource use for a low-concurrency, single-operator workload;
their value is thread-per-request without a pool, which the two servers already get. The
derive loop must **not** be converted. On JDK 25 the `synchronized` guards in
`Indexer`/`Sequencer`/`JsonlJournal` no longer pin carriers (JEP 491, delivered in 24), so
the mixed model is safe.

The runner follows the same rule: **one serialized worker** (a platform thread) runs jobs;
virtual threads are used only where there is blocking I/O to overlap — pumping a child's
stdout/stderr, and the upload handler (which inherits the server's virtual-thread executor).
No thread pool, no framework.

## 4. The job model

A job is a **declarative catalogue entry** in the runner (code, not a config file), with a
name, a description, a typed parameter schema, an argv template, and a capability
(`read` / `write`). No shell is ever involved: `ProcessBuilder` receives an argv array.

### 4.1 Phase 1 catalogue

| job | params | command (illustrative) |
|---|---|---|
| `egress-firefly` | `mode: plan\|verify\|apply`, `removeOrphans?: bool` | `egress firefly --hub-url … --firefly-url … --accounts /etc/trex/firefly.yaml [--plan\|--verify\|--apply]` |
| `ingest` | `files: string[]` (staged names, or names within the statements dir) | `ingest --source-type … --account … --sequencer-url … --evidence /var/lib/trex/evidence <path>` per file |

`ingest` is a **batch of per-file runs**: the CLI takes one FILE, so a multi-file trigger
yields one child per file, each with its own exit code, counts and tick (§6.1). The job groups
them so the UI can report per file; it never merges their outcomes.

### 4.2 Follow-ons (same shape, no new design)

`egress-archive`, `verify`, `reflow --preview`, `export`, `index --rebuild`.
`index --rebuild` needs the hub stopped (§5.3: "offline: takes the index lock") and is
therefore **out of phase 1** — it wants a hub-owned maintenance path, not a runner job.

### 4.3 Ingest needs a file → (adapter, account) mapping

`ingest-all.sh` hardcodes the standard mapping; a picker cannot. Propose a git-tracked
`/etc/trex/statements.yaml` (no private data — just patterns and refs), e.g.:

```yaml
files:
  - { match: "Salary_Account.csv",   sourceType: ing-csv, account: ing-salary }
  - { match: "Bankwest_*.csv",       sourceType: bw-csv,  account: bw-credit-card }
  - { match: "CBA_NetSaver*.pdf",    sourceType: cba-pdf, account: cba-netsaver }
```

The UI shows the resolved pair and lets the operator override it before running; an
unmatched file is shown as such and cannot be run until mapped. (Alternative: prompt for
adapter + account every time with no config — fewer files, more clicks. Decision §15.4.)

## 5. The runner's trigger API (loopback)

JSON over HTTP, mirroring the hub's `com.sun.net.httpserver` style. Illustrative:

| method | path | effect |
|---|---|---|
| `GET` | `/jobs` | catalogue: name, description, param schema, `running`, `lastRun` |
| `GET` | `/statements` | files the picker may choose: name, size, mtime, resolved `sourceType`/`account` |
| `POST` | `/jobs/{name}/runs` | body `{params}` → `202 {runId}` (queued); `409` if policy refuses |
| `GET` | `/jobs/runs?limit=N` | recent runs, newest first |
| `GET` | `/jobs/runs/{id}` | one run: state, timing, exit code, bounded output |
| `GET` | `/jobs/runs/{id}/events` | SSE: output lines, then the terminal state |
| `POST` | `/jobs/runs/{id}/cancel` | terminate the child (SIGTERM, then SIGKILL) |
| `GET` | `/adapters` | the known source types (`Adapters.types()`) for the type dropdown |
| `GET` | `/health` | liveness |

Staging endpoints are §6. Exit-code convention is the existing CLI's: `0` ok, `64` usage,
non-zero otherwise. The runner passes it through; it never reinterprets a job's result.

## 6. Staging and uploads (the ingest inbox)

**The idea.** A statement is uploaded through the browser into a **staging area**; the page
lists staged files, each with a **type** dropdown (and an account dropdown), and a button
runs the `ingest` job over the selection. This replaces "copy the file to the host, then run
a CLI" with a phone-or-desktop upload and one press — the same manual-trigger model as §2.

**Who owns it — recommended: the runner.** Staging is an *operational* concern (files that
are not journal facts, feeding a job, alongside the Firefly secret), so it lives in the
process that already owns jobs. The hub **proxies** the browser's upload and streams the
body to the runner; the hub never stores the operator's files and stays a read model plus
forwarder. The browser stays same-origin with the hub, so there is no CORS and no second
published port. (Simpler variant, if the proxy is annoying: the hub writes into a shared
`staging` volume and the runner only reads it. Rejected as the default because it puts file
storage in the hub; kept as Decision §15.10.)

**Layout.** `/var/lib/trex/staging/` on the runner's own volume:

- an uploaded file is stored under a **generated** safe name (`<epoch>-<sanitized>`); the
  client's name is kept as metadata, never used as a path;
- per-file state: `staged | ingested | duplicate | failed`, plus the resolved
  `sourceType`/`account`, the resulting `runId`, and a `sha256`;
- **staging is a transient inbox, not a record.** The durable copy is the **evidence store**
  that ingest writes (content-addressed, gzip); once evidence exists the staged file may be
  moved to `done/` or cleaned. Nothing about staging is irreplaceable. This is the key rule:
  no bytes are trusted to the inbox.

**Endpoints (runner):**

| method | path | effect |
|---|---|---|
| `POST` | `/staging` | `multipart/form-data`, bounded size → `{name, size, sha256}` |
| `GET` | `/staging` | list with state + resolved adapter/account + `runId` |
| `GET` | `/staging/{name}` | metadata (and download, for verification) |
| `POST` | `/jobs/ingest/runs` | ingest selected staged files with the chosen `sourceType`/`account` |

Re-uploading the same bytes appends nothing (whole-observation dedup already guarantees
this); the list simply reports `duplicate` after the run.

**The UI (the "SPA").** Still the existing **vanilla-JS module pattern — no reactive
framework.** `AGENTS.md` permits no framework, and none is needed: the page is a drop zone
plus `<input type=file multiple>` (works on mobile), an `XMLHttpRequest` for upload progress
(`fetch` cannot report it), and a list re-rendered from `GET /staging` and the jobs SSE. This
is `eyeball.js`'s pattern, not a new stack. The view is a single page in the one app, served
by the hub; responsive CSS is already in place.

**Limits and safety.** Cap size (e.g. 50 MB) and count, bounded concurrency, and never
execute an uploaded file (parsers only read it and validate rows). Uploads widen the
unauthenticated LAN surface, so the §10 auth caveat applies with the same force as `--apply`.

### 6.1 The tick, and the housekeeping it implies

**A tick means "these bytes are in the ledger", and it is derived, not stored.** The honest
source is the log: every `fact` carries `evidence_id` (the content-addressed `sha256:<hex>` of
the raw file, `schema.sql`), so a file is *ingested* when its content hash appears on facts.
The **hub** owns that derivation: it fetches the runner's raw staging entries (name, size,
mtime, sha256, type/account) and marks each `ingested` from `fact.evidence_id`. The runner
never reads the index (§11).

**Why not tick on the evidence store.** `EvidenceStore.put` runs *before* parsing and posting,
so a file that fails validation (`BAD_ROWS`) or dies on transport (`TRANSPORT`) is still in
evidence while no fact exists. Evidence presence is "we have seen these bytes" — the safety
net that makes a parser fix and `--reparse` possible — not "this file is ingested". So the tick
is facts, and evidence is what makes clearing safe.

**The tick is per (bytes, account).** Identity includes `accountRef` (§8.3), so re-ingesting
the same bytes under a *different* account is a legitimate new ingest, not a duplicate; each
account gets its own tick. The UI may note "already ingested for ing-salary" before a second
run.

**The tick is not the run outcome.** A `TRANSPORT` failure can leave some facts posted (a
partial tick) and `BAD_ROWS` leaves none (no tick). The *last attempt* — exit code, counts,
when — belongs to the run history (§8) and is shown beside the tick; the two together are what
the operator reads.

**Housekeeping.**

- A staged file whose bytes are in the log is redundant: **Clear ingested** moves it to
  `staging/done/` (default, reversible) or deletes it (explicit). Either is safe — the evidence
  store holds the canonical copy and the facts hold the ledger.
- **Never prune evidence.** It is immutable, content-addressed, and the only thing that keeps a
  parser fix safe (§8.1). Staging is transient; evidence is the growing footprint, so surface
  its size/count rather than trim it.
- Re-uploading the same bytes is a no-op end to end (evidence `put` is idempotent and
  whole-observation dedup appends nothing), so a stuck partial upload is fixed by uploading
  again, not by cleanup.
- Aborted uploads leave `<name>.part` files; the runner sweeps them on startup.
- **Staging is not backed up.** The journal + evidence + config are; a staged copy is
  recoverable by re-uploading.
- The host `statements/` directory gets the same ticks — same hash, same check — so the bulk
  path also shows what is already in.

## 7. Hub proxy and UI

**Hosting boundary (settled): the hub hosts the SPA; the runner is headless.** The ingest page
is a mode beside Blotter/Review/Eyeball/Rules, so it shares one origin, one nav, one theme and
one refdata fetch — no second static pipeline, no CORS. Decisively, the **runner token must
never reach the browser**: the browser talks to the hub, and the hub authenticates to the
runner. If the runner served the page, the token would leak into JS or the runner would have
to trust the LAN, both worse than the loopback-only posture (§10). The hub is already the only
published port and already owns "Indexer + API + UI" (§14); the runner stays a headless
dispatcher. Assets live in `trex-v2-hub/.../web/`; the runner serves JSON only.

The hub gains an optional `--runner-url`; without it the Jobs view is hidden, exactly as the
hub is read-only without `--sequencer-url`. Browser traffic stays on the hub's origin:

| hub route | proxied to |
|---|---|
| `GET /api/jobs`, `GET /api/jobs/statements`, `GET /api/jobs/adapters` | runner reads |
| `POST /api/jobs/{name}/runs` | runner `POST /jobs/{name}/runs` |
| `GET /api/jobs/runs[/{id}]`, `…/events` | runner run reads + SSE pass-through |
| `POST /api/jobs/runs/{id}/cancel` | runner cancel |
| `POST /api/jobs/staging` (multipart) | runner `POST /staging`, body streamed |

The UI adds one nav item, **Jobs**, and one module `js/jobs.js` (same shape as `eyeball.js`:
`mount(container, ctx)` + `refresh()`). Contents:

- **Staging**: drop zone + file picker; the staged list with per-row **Type** and **Account**
  dropdowns (prefilled from `statements.yaml`), multi-select, and **[Ingest selected]**;
- **Egress Firefly**: **[Plan] [Verify] [Apply]**;
- a live pane for the active run, fed by a dedicated `EventSource` on
  `/api/jobs/runs/{id}/events` (the existing `sse.js` is bound to `/api/events`, so this is a
  second, small stream), with **Cancel**;
- a history list (state, duration, exit code) that opens a run's output;
- **Apply is two-step**: the operator reads a **Plan** first, then confirms Apply. `apply`
  and any future destructive job get a confirmation dialog.
- Buttons disable while a job is queued/running; the state comes from `GET /api/jobs`.

The dev stack serves the UI from the working tree (`webDir`, `compose.dev.yml`), so the whole
view is iterable with a reload; only the runner and proxy are image changes.

## 8. Run state, output, history — and the invariants

- **Runs are operational telemetry, never facts or decisions.** No run line may ever enter
  `trex.jsonl`; the journal stays facts + decisions (§6, AGENTS). An ingest's durable record
  is its evidence + the facts it posted; an apply's is Firefly + `projection_state`. Test:
  after running a job, `n` advances by exactly what the job itself appended and no more.
- **Runs live in memory** in the runner: the live run plus a bounded ring (e.g. last 50),
  output capped per run (e.g. last 2000 lines / 256 KB), oldest trimmed. Lost on restart —
  acceptable, because nothing important is only there. If cross-restart history is wanted,
  write a bounded JSONL under the runner's own state dir and document it as disposable; it
  must not be the journal or the hub's index (the hub owns the index, §7.4).
- **No new durable truth.** `projection_state` stays the only projection record and stays
  rebuildable from Firefly (§11.6); staging bytes are re-obtainable from evidence.

## 9. Concurrency and preconditions

- **One worker, FIFO.** Everything is serialized: a single operator, and ingest/apply mutate
  external state. A trigger while busy queues and shows `queued`; the UI disables the
  buttons meanwhile. (Read-only jobs could later run in parallel; not now — Decision §15.3.)
- **Uploads are not jobs**: a file copy may proceed while a job runs; only the *ingest run*
  is serialized. The ingest job reads a snapshot of the selected staged names and runs them
  one file at a time, each ticked independently (§6.1).
- **Preconditions checked before start**: `ingest` needs the sequencer reachable; `egress
  firefly` needs the hub and Firefly reachable; a failed precondition fails the run with a
  clear message and a non-zero code, it does not crash the runner.
- **Timeouts**: a wall-clock cap per job (configurable), after which the run is cancelled and
  reported as timed out — distinct from a job's own non-zero exit.

## 10. Security

The hub is **unauthenticated and LAN-exposed** (`docs/DEPLOYMENTS.md`). Triggering `--apply`,
ingesting, and accepting uploads from that origin widens the blast radius, so:

- the runner binds **loopback only** and is never published;
- the hub authenticates to the runner with a shared secret header (`TREX_RUNNER_TOKEN`,
  generated at deploy, in `/opt/trex/.env` 0600), so only the hub can drive it;
- the runner accepts **only** catalogue jobs and validated params — `files` must resolve
  inside the staging or statements dir (reject `..`, absolute escapes), `mode` must be one of
  three literals, uploads are size-capped with generated names, no free-form argv, no shell;
- `apply` (and later `--remove-orphans`, rebuild) sits behind a runner config flag that is
  **off by default** on a LAN-exposed host until the hub is fronted with auth
  (Caddy/Tailscale). Plan/verify/ingest/uploads may be enabled without it. (Decision §15.5.)

## 11. Deployment

Runner processes the jobs; the hub proxies to it. The runner's staging is a volume, and both
processes share the same image.

| mount (runner) | for |
|---|---|
| `config:/etc/trex:ro` | `firefly.yaml`, `statements.yaml`, rules |
| `staging:/var/lib/trex/staging` | the upload inbox |
| `statements:/statements:ro` | the host's bulk statement path (kept for `ingest-all.sh`) |
| `evidence:/var/lib/trex/evidence` | writes the durable copy during ingest |
| `journal` | not mounted — jobs never touch it |

The runner must not mount the index, and must not write `/etc/trex` (the hub owns the rules,
§7.4). Compose sketch:

```yaml
runner:
  <<: [*trex, *hardening]
  command: [runner, --host, 127.0.0.1, --port, "8091",
            --sequencer-url, http://sequencer:8080, --hub-url, http://hub:8090,
            --config, /etc/trex, --statements, /statements, --staging, /var/lib/trex/staging]
  volumes:
    - config:/etc/trex:ro
    - staging:/var/lib/trex/staging
    - statements:/statements:ro
    - evidence:/var/lib/trex/evidence
  environment:
    FIREFLY_TOKEN: ${FIREFLY_TOKEN:?set FIREFLY_TOKEN for egress}
    TREX_FIREFLY_URL: ${TREX_FIREFLY_URL:?set TREX_FIREFLY_URL}
```

Hub gains `--runner-url http://runner:8091` and the same token; its port stays the only
published one. `FIREFLY_TOKEN` and `TREX_RUNNER_TOKEN` live in `/opt/trex/.env` (0600), never
flags, never git. A large upload is streamed by the hub, not buffered. The `runner` container
may run several child JVMs over a long life: give it a slightly larger heap and a
`stop_grace_period` so a running child can finish.

## 12. Reconcile what this replaces

- `deploy/v2/systemd/trex-egress-firefly.{service,timer}` (bare-jar, hourly plan) become
  obsolete under this model and should be removed with it; the archive timer can stay if the
  mirror stays periodic.
- `deploy/v2/README.md` "systemd" section and the `trex-egress-firefly.timer` paragraph need
  updating.
- The compose `egress-firefly` tools service (hardcoded `--plan`, and missing
  `FIREFLY_TOKEN`) becomes either a thin convenience wrapper or retires in favour of the
  runner.
- `ingest-all.sh` remains the bulk host path; the staging inbox is the interactive one.

## 13. Proposal edits required before code

Per `AGENTS.md`, do not silently choose. Edit `V2-PROPOSAL.md` first:

- §5.3: add `trex runner` to the process table; replace "timers are enough" with the runner
  as the mechanism, timers as optional for periodic mirrors.
- §11: state that the egress stays a batch and the runner dispatches it on instruction.
- §12: add the staging inbox and the upload path as an ingest source (alongside the file
  adapters), with "staging is not a record; evidence is".
- §14: the compose `runner` service and its mounts.
- §19 (one artifact): add the `runner` subcommand.

## 14. Acceptance tests

1. Trigger **Plan** from the UI; the diff streams live; exit code and the `done:` line are
   recorded; nothing is written to Firefly.
2. Trigger **Apply**; a following **Plan** is empty and **Verify** exits 0.
3. Upload a statement from a **desktop** browser, into staging; it appears in the list with a
   resolved type; press Ingest; facts land (`n` advances).
4. **Tick**: before ingest the file has no tick; after a clean ingest it has one, derived from
   `fact.evidence_id`.
5. A file with a **bad row** is stored to evidence but shows **no tick**, and its run history
   shows the reason.
6. The same bytes ingested under a **different account** is not treated as already-done; it
   earns its own tick.
7. Upload the **same bytes again**; the run reports `duplicate`, `n` does not move, and the
   tick persists.
8. Upload from a **mobile** browser (same responsive page); the flow works.
9. Trigger **Ingest** over a set of staged files and over host statement files; each file's
   `Appended/Duplicate/Flagged/Rejected` outcome and its own tick are shown.
10. **Clear ingested** removes only staged bytes; the evidence copy and the facts remain, and
    the file can be re-uploaded.
11. A failing job (Firefly down, bad token, missing account) reports the CLI's message and a
    non-zero exit; the hub and sequencer stay up.
12. A second trigger while a job runs is queued or refused with a clear message; the UI
    reflects it.
13. **The journal gains no job or staging lines**: after all of the above, `n` moved only by
    the facts and decisions the jobs themselves created, and no staged file is copied into the
    log.
14. Cancel terminates a running job.
15. With the runner stopped, the hub still serves every existing view (the proxy degrades).

## 15. Locked decisions

All recommendations below were accepted. The first is the architecture; the rest are the
small choices the implementation depends on.

1. **Runner placement** — a separate `trex runner` service (loopback), **not** embedded in
   the hub.
2. **Run history** — an in-memory bounded ring (last 50 runs, capped output); nothing
   durable.
3. **Concurrency** — a single worker, FIFO; read-only jobs are serialized too, for now.
4. **Statement mapping** — a new git-tracked `/etc/trex/statements.yaml`; the UI may override.
5. **`apply` gating** — a runner flag (`--allow-apply`), default **off**; enabled in the dev
   stack for testing, and on the host only once the hub is fronted with auth.
6. **Hub↔runner auth** — a shared token header (`TREX_RUNNER_TOKEN`).
7. **Phase-1 scope** — `ingest` and `egress-firefly` only; archive/verify/export/rebuild
   deferred.
8. **Name/port** — `trex runner` on `127.0.0.1:8091`.
9. **Old artifacts** — the systemd egress timer drafts are removed by this change.
10. **Staging ownership** — runner-owned, with the hub proxying uploads.
11. **Staging retention** — ingested files move to `staging/done/`; nothing is deleted by
    default.
12. **Upload limits** — 50 MB per file, 20 files staged; no extension allowlist (content is
    validated by the adapters).
13. **Tick source** — derived from the log (`fact.evidence_id`), hub-enriched.
14. **Tick vs run outcome** — two separate columns: the derived tick and the last run's exit
    code.
