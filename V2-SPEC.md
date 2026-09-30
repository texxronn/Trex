# trex v2 — specification (as built)

**Status.** This is the *as-built* specification of the v2 system: what the code in
`trex-v2-*` actually does. `V2-PROPOSAL.md` remains the authoritative source of intent
(`AGENTS.md`), and `V2-IMPLEMENTATION-PLAN.md` remains the build order. Where this document and
the proposal differ, **the proposal wins**; where the difference is deliberate or the proposal is
silent, `docs/V2-PARITY.md` records it. This file is descriptive, not a second authority — if it
drifts, the code and the proposal are the truth.

v1 is archived in the sibling project `../TrexV1` (`trex-core`, `trex-journal`, `trex-sequencer`,
`trex-ingest`, `trex-egress`, `trex-ws`): reference only, never imported by v2 code, and not
migrated from.

---

## 1. Invariants

1. **The log is the only truth.** Everything else — the index, review queues, projection state,
   ACKs — is disposable and reproducible by `trex index --rebuild`.
2. **Facts, decisions, and ingest events only.** A fact is what a source said; a decision is what a
   person concluded; an ingest event is what an ingest did. The writer never interprets: no
   matching, no category, no derived review flags. It does keep an identity/observation index (to
   assign `occ` and dedup identical observations) and returns a `Duplicate`/`Flagged` row outcome —
   bookkeeping, not semantics.
3. **`derive()` is pure.** Same inputs (including `asOf`) → same output. No clock, no I/O, no
   environment, no unordered iteration.
4. **Decisions win over derivation**, and every id resolves through the supersession map before a
   decision is applied.
5. **Nothing is deleted.** Undo is an appended family inverse or `REVOKE`.
6. **One writer, one atomic append, one fsync per batch.**
7. **No category is ever a property of a transaction.**
8. **`externalId` is never rewritten.** Only it and the log's history are permanent.

---

## 2. Modules

| Module | Kind | Responsibility |
|---|---|---|
| `trex-v2-core` | library | model, identity, `Clean`, `MerchantStem`, `occ`, `derive()`, workbook, state hashes. Pure. |
| `trex-v2-log` | library | framed writer/reader + recovery, line codec, evidence store, `Json`/`Yaml`, `ConfigLoader`. |
| `trex-v2-index` | library | SQLite materialiser: log → level-1 mirror → derived tables; index lock; offset; rebuild. |
| `trex-v2-sequencer` | service | the only writer: `POST /facts`, `/decisions`, `/ingest`, `GET /head`, `POST /maintenance/snapshot`, recovery, fsync. |
| `trex-v2-hub` | service | index owner; blotter API + UI; decision precheck/forward; reflow; ACK; SSE; proxy to the runner. |
| `trex-v2-ingest` | library/CLI | adapters → evidence + facts + ingest events; source archive; whole-file validation; day batching; feeds; re-parse. |
| `trex-v2-egress` | library/CLI | archive byte mirror; Firefly projection; export. |
| `trex-v2-runner` | service | on-demand job dispatcher + staging inbox: a loopback trigger API, `ingest`/`egress-firefly`/`journal-snapshot`, sync/async. Never writes the log or the index. |
| `trex-v2-dist` | packaging | one shaded `trex-v2.jar`; picocli subcommands select the role. No logic. |

`trex-v2-hub` depends on `trex-v2-sequencer` for shared wire DTOs only (a type-only dependency).

---

## 3. The log

- **Encoding.** Framed JSONL: one '`\n`'-terminated UTF-8 record per line. Every line leads with
  the **envelope** `{ n, kind, v, atMs, env, source, target }`, then `at` (a readable echo of
  `atMs`) and the kind-specific body. `kind` is namespaced (`trex.fact` / `trex.decision` /
  `trex.ingest`) and `v` is `1`. `atMs` is epoch millis (UTC) and the only time logic reads; it
  echoes a client-supplied `ingestedAt` when present, so it is **not monotonic in `n`** — order by
  `n`, never by `atMs`.
  `env`/`source`/`target` are exactly 8 chars of `[A-Za-z0-9_ ]`, right-padded with spaces: `env` is
  the sequencer's `TREX_ENV`; `source` is the writing process instance, declared in `sources.yaml`
  and refused if unknown; `target` is `none` (eight spaces). A record is complete iff it ends in
  '`\n`' and parses; a partial tail is left unread and a torn tail is truncated. One batch = one
  write + one fsync.
- **Facts** (`Fact`): `externalId`, `accountRef`, `date`, `amount` (cents, signed), `balance`
  (provenance only), `rawDescription` (verbatim), `receipt` (nullable), `occ`, `observation`
  (`posted | pending`), `sourceType`, `provenance` (`BANK | AUTHORED`), `evidenceId` (nullable),
  `parser`. The time is the envelope's `atMs`.
- **Decisions** (`Decision`): `action`, `actor` (`user | migrated | system`), `user` (nullable),
  plus the action's fields; `at` is `envelope.atMs`. The complete action set is `PAIR`, `UNPAIR`,
  `MARK_EXTERNAL`, `SETTLE`, `DISMISS`, `PIN`, `UNPIN`, `SUPERSEDE`, `RETIRE`, `REVOKE`, `USER_ACK`,
  `USER_UNACK`, `NOTE`. On the wire, `REVOKE`'s target is `revokes` — the envelope owns `target`.
- **Ingest events** (`IngestEvent`): `phase` (`start | complete`) and `batch`; a `start` also carries
  `evidence`/`file`/`account`/`sourceType`/`parser`, a `complete` the `appended`/`duplicate`/`flagged`
  counts and a `status`. The facts sit strictly between the pair, so a batch's `n` range is the
  markers themselves.
- **Forward compatibility.** A well-formed line whose `kind` this build does not know is parsed as
  `Unknown` and ignored; a known kind at a higher `v` is refused (never silently misread).
- **Recovery.** A source journal may be materialised over a target at startup; the original is never
  written. A torn tail is truncated to the last complete line.

---

## 4. Identity

`externalId` is the SHA-256 of a canonical string, first 16 lowercase hex characters:

- receipt-keyed: `nk|<accountRef>|<date>|<receipt>`
- otherwise: `ch|<accountRef>|<date>|<amount>|<rawDescription>|<occ>` (raw verbatim)

A transfer id is `TRF-<receipt>`, or `TRF-<sha256("tr|<minId>|<maxId>")[0:16]>` when the legs share
no receipt (order-independent). `occ` follows v1: rows with identical `(account, date, amount, raw)`
in one batch take `0,1,2,…` in batch order; receipt rows are `0` and do not advance; distinct
content rows each take `0`.

---

## 5. Decisions

- **Revocable, never rewritten.** Family inverses (`UNPAIR`, `UNPIN`, `MARK_EXTERNAL`,
  `USER_UNACK`, a later `PIN` or `USER_ACK`) cover the everyday undo; `REVOKE(n)` is the general one
  and the only way back from `SUPERSEDE`, `RETIRE` and `DISMISS`. A later `REVOKE` may revoke a
  `REVOKE`.
- **Effectiveness is derived**, from order and `REVOKE`s, never stored.
- **References and structure are checked at the writer**; a semantically wrong but well-formed
  decision is recorded and surfaced as `INEFFECTIVE_DECISION`, never dropped.
- The hub prechecks against the index and returns `422` (naming the failure), `409` (stale view),
  or `503`/`502` (no writer / writer unavailable).

---

## 6. `derive()`

Pure function of `(facts, decisions, config, asOf)`, in the §9.9 order:

replay → effective decisions → supersession / chain resolution → current transactions (`txn_current`)
→ transfer pairing → pending settlement → categorisation → review items → projectable units →
state hashes → per-user ACK validity.

Every output list is ordered, so an unchanged input yields byte-identical tables. Versions are
recorded alongside, never inside, a hash: `deriveVersion = "derive/3"`, `hashVersion = "statehash/2"`,
and `configRevision` = SHA-256 over the sorted config files that can move derived state.

**Transfer pairing.** `PAIR` decisions win. The matcher tiers are T1 (shared receipt), T2 (same day)
and T3 (within `windowDays`), requiring equal magnitude, opposite sign, different accounts, same
currency, and — for T2/T3 — an equal `transferStem`. Ambiguity (more than one candidate) becomes a
review item, never a guess.

**Review items** (`(subject, kind)` is the key): `POTENTIAL_DUP`, `RESTATEMENT`, `AMBIGUOUS_TRANSFER`,
`AMBIGUOUS_SETTLEMENT`, `UNMATCHED_LEG`, `STALE_PENDING`, `INEFFECTIVE_DECISION`. Duplicate and
restatement rows are grouped into clusters, so one item lists every member.

**Pending.** A pending fact is never current, never counted, never projected; it is `OPEN`, `SETTLED`
by a derived counterpart, or `STALE` past the account's `settlementWindowDays`, and is closed by
`SETTLE` or `DISMISS`.

---

## 7. The read model

SQLite, owned by the hub. Level 1 mirrors the log (`meta`, `fact`, `decision`, `ingest_event`); level
2 is derived and rebuilt wholesale: `supersession`, `chain_resolved`, `txn_current`, `transfer`,
`pending`, `review_item`, `category_current`, `pin_current`, `ineffective_decision`, `unit`,
`projection_state`, `user_ack`, `source_cursor`, `evidence`, and the `ingest_batch` view (the
markers paired). Every table can be dropped; `trex index --rebuild`
reproduces them. The hub holds one writer connection and a small read pool, with `query_only` reads.

---

## 8. Sequencer API (`trex-v2-sequencer`)

| method | path | purpose |
|---|---|---|
| `POST` | `/facts` | append a batch of fact drafts (`allOrNone`, `source` required, `target?`); per-row outcome `Appended \| Duplicate \| Flagged \| Rejected`. |
| `POST` | `/decisions` | append a batch of decisions (`source` required); reference/structure validation only. |
| `POST` | `/ingest` | append one ingest event (`source` required); the writer stamps the envelope and assigns `n`. |
| `GET` | `/head` | the current log head `n` and byte offset. |
| `POST` | `/maintenance/snapshot` | write a dated gzip copy of the journal prefix to the archive; `?sync=false` runs it in the background. |

Every batch carries `source` (exactly 8 chars, declared in `sources.yaml`); an unknown source is a
`400`. The sequencer stamps `env` from `TREX_ENV`. Single-writer `FileLock`; the API is
unauthenticated and binds loopback by default.

---

## 9. Hub API and UI (`trex-v2-hub`)

`/head`, `/api/status`, `/api/refdata`, `/api/ledger`, `/api/review`, `/api/transfers`, `/api/units`,
`/api/reconcile`, `/api/opening`, `/api/workbook`, `/api/projection` (GET/POST), `/api/cursors`
(GET/POST), `/api/decisions` (POST), `/api/acks` (GET/POST), `/api/eyeball`, `/api/ingests`,
`/api/reflow/preview` (POST), `/api/config/categories` (GET/PUT), `/api/jobs*` (proxied to the
runner), and `/api/events` (SSE snapshot then deltas).

The UI has five modes: **Blotter** (SQL-backed filters, inline decisions, status strip), **Review**
(the derived queue, one decision away from clear), **Eyeball** (§10.3 — open items, the nine anomaly
checks with an explicit `asOf`, and transactions bucketed by day/week/month with a per-row
`Ack`/`Unack` and a per-row pin), **Rules** (editor with blast-radius preview, lint, fixtures,
coverage), and **Jobs** (the trigger runner: the staging inbox, egress Plan/Verify/Apply, Snapshot
journal, the ingest history, and the ops strip of staleness — last plan/apply and
unprojected/drifted/orphaned unit counts).

---

## 10. Ingest and evidence (`trex-v2-ingest`)

- **Adapters** by `sourceType`: `ing-csv`, `bw-csv` (debit sign inferred per file; mixed rejected),
  `cba-csv` (header-less), `cba-pdf` (Transaction Summary PDF).
- **Whole-file validation**: any bad row sends nothing. **Day-atomic batching**: a day is never split
  across calls. Exit codes: `0` ok, `1` bad rows, `2` transport, `3` rejected, `64` usage.
- **Evidence**: files are stored content-addressed (SHA-256) and never rewritten; a re-parse replays
  stored evidence, diffs it against the facts it produced, and (with `--apply`) posts the new facts
  plus `SUPERSEDE`/`RETIRE`.
- **Feeds** carry a `source_cursor` (GET/POST `/api/cursors`).
- **Pending** facts are recorded with `observation: pending` and resolved in `derive()`.
- **Ingest events.** Each run appends `trex.ingest` `start` (after evidence) and `complete` (counts
  and `status`), so the log is self-documenting; a bad-row file still emits the pair, and a transport
  failure leaves the batch open.
- **Source archive.** With `--source-archive` the exact bytes are gzipped to
  `archive/sources/<Y>/<M>/<D>/<HHMMSS>-<name>.gz`; the runner supplies `--source-name` (the staged
  file's original name).

---

## 11. Egress and export (`trex-v2-egress`)

- **Firefly** (§11): `plan` / `apply` / `verify` over the hub's projectable units. Legs are never
  projected; an `ATTESTATION` is never projected; `HELD`/`REVIEW`, `PENDING` and retired facts are
  withheld. The transaction type follows the account kinds; a re-tag preserves splits and
  `group_title` and clears a stale `category_id`; drift and de-projected units are reported, never
  deleted automatically. Projection state is an accelerator, rebuilt from Firefly when missing.
- **Archive**: a byte mirror of the journal plus an evidence copy, verified by hash.
- **Export**: `csv`, `json`, or a portable `sqlite` file.

---

## 12. Configuration (`deploy/config`)

`sequencer.yaml` (bind host/port, journal source/target), `accounts.yaml` (ref, currency,
`balanceSource`, `settlementWindowDays`), `users.yaml` (non-empty; id, name, active, cadence),
`categories.yaml` (declared categories + ordered rules), `categories.tests.yaml` (golden fixtures,
run on load), `transfers.yaml` (window, tolerances, allowlist), `firefly.yaml` (account mapping),
`sources.yaml` (the declared 8-char sources the sequencer accepts), `statements.yaml` (a filename →
`sourceType`/account map for the runner's upload picker), and an optional `refdata.yaml` overriding
the declared category names. The rules are the tuned contract; a rebuild consumes them as-is. The
sequencer's environment is `TREX_ENV` (not a file); the archive location is `--archive` on the
sequencer and runner.

---

## 13. CLI

One artifact, one role per subcommand (`trex-v2.jar`): `sequencer`, `hub`, `runner`, `index`,
`ingest`, `egress archive`, `egress firefly`, `snapshot`, `reflow`, `verify`, `export`, `import`
(dev). Exit code `64` is `EX_USAGE`. No config is baked in.

---

## 14. Operations and deployment

- **Back up three things**: the journal, the evidence store, and the config (git). The index,
  projection state, review queues and ACK copies are rebuilt, never backed up.
- **The archive** (`--archive`) holds the byte mirror (kept forever), dated **journal snapshots**
  (`journal/trex-<ts>.jsonl.gz`, a consistent copy taken from the live log — never a rotation), and
  the **source archive** (`sources/…`). Snapshots are pruned by an explicit `prune-archive`
  (planned).
- **Staging** (`trex runner`) is a transient inbox; evidence is the durable copy of the bytes.
- **`--apply` is opt-in** (`--allow-apply` / `TREX_RUNNER_ALLOW_APPLY`); the base compose leaves it
  locked, and the UI gates it behind a fresh Plan.
- **Recovery drill**: `stop hub; rm index; trex index --rebuild; trex verify`, run on a schedule.
- **One image** (`trex/trex-v2`), role by command; `deploy/v2/` carries compose and systemd units.
  The sequencer mounts the journal read-write; every other role reads it.

---

## 15. Testing

Three classes per module: contract tests (record shapes, round-trip, schema), invariant tests named
after the §15 guarantees (`deriveIsPure`, `deriveComposeDeriveIsDerive`, `rebuildEqualsIncremental`,
`decisionsWinOverReflow`, …), and regression tests carrying the measured story. Fixture-dependent
tests are `@Tag("fixture")` and skip with a printed reason, so `mvn verify` is green on a fresh
clone. `docs/V2-PARITY.md` holds the v1↔v2 equivalence ledger; `StatementsE2ETest` is the end-to-end
run over real statement files.

---

## 16. The runner and its schedule

`trex-v2-runner` is a loopback service the hub proxies. A **job** is an invocation of an existing
subcommand — `ingest`, `egress-firefly`, `journal-snapshot` — never new logic: the runner starts the
process, streams its output and records the exit code. One serialized worker, a bounded in-memory run
history, and a **staging inbox** for uploads. `POST /jobs/{name}/runs` is async (`202 {runId}`) or,
with `?sync=true&timeoutMs=`, returns the terminal detail or the handle.

**The schedule** (`schedule.yaml`, empty by default) enqueues jobs through the same queue. It is
intervals only, with a phase — no cron:

```yaml
jobs:
  - job: journal-snapshot
    every: 24h               # 1h,2h,3h,4h,6h,8h,12h,24h,7d
    at: "02:30"              # local time of day — required (the phase)
    on: Sun                  # weekly only; required for 7d, refused otherwise
    zone: Australia/Sydney   # default UTC
```

- `at` pins the phase; `every` is the period. A period that is a whole number of days is a
  **calendar** cadence (the local wall time is preserved across a DST change); a sub-day period is a
  grid anchored at `at`, stepped by `every`.
- DST: a non-existent local time shifts forward; an ambiguous one fires once.
- A due job that is already active is **skipped**, not queued twice.
- **No catch-up**: a slot missed while the runner was down is missed; the manual button covers it.
- Job names are validated at load against the catalogue; a bad entry fails startup.

Runs carry a `trigger` (`manual` | `schedule`), and `GET /jobs` reports each job's `nextRun`.

---

## 17. Deltas from the proposal

Recorded, with the tests that pin them, in `docs/V2-PARITY.md`:

- the matcher's equal-`transferStem` requirement at T2/T3 (stricter than v1);
- pending settlement and its sign convention (v1 skipped pending entirely);
- `transferStem`, the restatement similarity, and the payment-noise word list (no v1 counterpart);
- `occ` restated to v1's exact rule;
- the hub↔sequencer type-only dependency;
- the uniform envelope, namespaced kinds and `v = 1` (the pre-envelope v2 log was `v = 2` on facts
  with no header — a MAJOR line-format change);
- `trex.ingest` events and the derived `ingest_batch`, a third line kind;
- `trex runner`, the Jobs UI, `trex snapshot`, and the archive layout (post-proposal);
- `REVOKE`'s wire field renamed `target` → `revokes` (the envelope owns `target`);
- migration (§16) is retired — there is no migration, and the importer is a dev tool.
