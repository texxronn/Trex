# trex v2 — a refined v1

> **Personal project, single operator, private.** Nothing here is a public contract.
> The invariants are self-imposed because a ledger that silently loses a row is broken
> regardless of who is reading — not because anyone else depends on these shapes. A
> breaking change to the API, the config or the line format is a refactor, not a
> versioning event; only identity (`externalId`) and the log's history are permanent.

**A rewrite proposal.** Written 2026-09-29, against `SPEC.md` as it stands and the
six-module tree that implements it. Revised the same day to close the open semantics:
decisions are revocable (`REVOKE`), facts can be retired (`RETIRE`), settlements can be
decided (`SETTLE`), `derive` takes an explicit `asOf`, and provenance uses
`configRevision` alongside `deriveVersion` and `hashVersion`. (The answers are folded into
the sections; §20 of the archived original lists them.) The v1 tree is retained as reference
only; there is no migration (§16).

---

> **Scope of this document.** This is the authority for **intent, invariants, contracts and
> forward design** — the *why*, and the parts that must not move. It is **not** an as-built
> description: what the code actually does lives in `V2-SPEC.md`. Where the two differ, this
> document wins; where a difference is deliberate, `docs/V2-PARITY.md` records it. Implemented
> mechanics are named, not restated — follow the pointer to `V2-SPEC.md`.

## 0. One page

The single structural change: **stop writing conclusions into the log.**

v1's journal is one versioned stream in which a bank observation and a human decision
look the same, because both are "the latest line for an `externalId`". That was enough
while everything was frozen at ingest. It is exactly what blocks reflow: automatic
results — matched transfers, HELD/EXTERNAL, `POTENTIAL_DUP` — are written as history,
so tuning a rule cannot change a past that is already recorded. v1 says this out loud
(§3.2, "rules are never re-evaluated on replay") and the dry-run harness exists only
because of it.

v2 keeps the append-only log. It is the right spine. It changes what goes in it:

- **facts** — what a source said, once, never rewritten;
- **decisions** — what a human concluded, small, explicit, append-only and revocable.

Everything else is **derived** by one pure function
`derive(facts, decisions, config, asOf)` and materialized into a **rebuildable
SQLite read model** that the blotter queries with SQL. Delete the database, rebuild it,
get the same answer for the same derivation instant. That property is the crown: reflow,
parser upgrades, rule tuning, schema changes, corruption recovery and egress convergence
all become the same operation — throw away derived state, derive again, show the diff
before anything outside trex moves.

**The spine does not move.** Ingest still parses whole files and validates every row; the
client still posts over HTTP to the sequencer; the sequencer is still the only writer,
still assigns `n`, still fsyncs each batch; the journal is still a framed JSONL file with
the same recovery and offset semantics. What leaves the sequencer is *interpretation*:
transfer matching, HELD/REVIEW/EXTERNAL, duplicate flags and TRANSFER lines are all
derived by the index from the log. The write path gets smaller; the read side does the
thinking. §6.5 spells out the seam.

What you get that v1 does not have:

1. **Reflow** — tune `transfers.yaml` or a category rule and *see* the whole history
   move, with a diff, without touching one fact.
2. **A blotter that queries SQL** instead of hand-rolled in-memory filtering, so the
   weekly eyeball (and anomaly detection) is cheap to build.
3. **An evidence store** — the raw statements are kept, content-addressed, so a parser
   fix is a re-parse and a review, not a permanent identity accident.
4. **Firefly convergence** — plan/apply/verify instead of fire-and-hope; drift is a
   report, not a surprise.
5. **A categorisation workbook** — lint, fixtures, suggestions and health, so the
   "deeply fine-tuned" part keeps getting better instead of rotting.
6. **An eyeball habit** — the routine gets a home, a per-user marker at whatever cadence
   each person keeps, and a warning when a reflow invalidated something already reviewed.
7. **One jar, one image** — every role (`sequencer`, `hub`, `egress …`) is a subcommand
   plus config, so there is one artifact to version, sign and ship.

And what stays exactly as it is: the identity function, the append-only single-writer
journal, `balance` as provenance, no category stored on a transaction, the whole-file
validation, the reconciliation tripwire, and Firefly as a one-way tag. Those are the
80–90%.

If you only ever do one thing from this document: **build §7 (the index) and point the
blotter at it.** It is additive, reversible, needs no format change, and immediately
improves the workflow that matters most.

---

## 1. Why Java, and what that means

You are right to stay. The hard problems here are invariants, data modelling and
workflow — not throughput. Java 25 gives you the good parts for this domain, and your
expertise means you can open the engine and fix it, which is worth more than any
runtime's elegance.

- **Language:** records and sealed interfaces for `Fact`, `Decision`, `Transfer`,
  `ReviewItem`; pattern-matching `switch` for derivation; no preview features.
- **Threads:** keep the single writer; `HttpServer` handlers on a virtual-thread
  executor if you want them (final since 21, so no preview flag). The derive step is
  pure and can run off the request path.
- **Storage:** `sqlite-jdbc`, WAL, hand-written SQL in `.sql` files, plain JDBC. No ORM,
  no JPA, no jOOQ unless the SQL genuinely outgrows string constants.
- **Everything else as v1:** Jackson JSON/YAML, picocli, PDFBox, SLF4J, WatchService,
  `java.net.http`.
- **The "get dirty" affordance:** the log stays text (`jq`, `diff`, `tail`), the SQL
  stays in files you can open in the sqlite3 shell, and there is one CLI with
  subcommands so every operation is one obvious command.

One packaging change worth making: **one shaded `trex` jar and one container image.**
picocli subcommands select the role at launch — nothing else distinguishes the
artifacts. You already did this within egress; done across the system it collapses six
`--help` outputs into one, turns "which jar runs what" into a single answer, and gives
one version to build, sign, scan and ship:

```
trex sequencer                     # the writer (service)
trex hub                           # index + blotter API + UI (service)
trex runner                        # on-demand jobs + the staging inbox (service)
trex ingest --source-type … FILE   # adapters
trex index [--rebuild]             # materialize the read model; --rebuild is offline (hub stopped)
trex reflow --preview              # show what a candidate rule set would change before saving
trex egress archive|firefly…       # targets: the byte mirror and the Firefly projection
trex snapshot --sequencer-url …    # a dated gzip journal copy in the archive
trex verify                        # all invariants: framing, reconcile, index, evidence, egress plan
trex export --format csv|json|sqlite  # the living truth, portable
```

Same artifact, many roles: `trex sequencer`, `trex hub`, `trex egress firefly` are one
image with different commands and config. **One jar is not one process** — the writer
stays its own process, and the read side stays another (§5.3).

---

## 2. What v1 got right — do not touch it

Reviewing a system this size usually produces a list of "also fix this". Most of that
list does not exist here. Explicitly preserve:

| v1 decision | Why it stays |
|---|---|
| Identity = natural key (account, date, receipt) else content hash, hashing **verbatim** `rawDescription`, first 16 hex of SHA-256, frozen strings | It is the contract everything else keys on. It survives re-imports, description cleanup, and two sources of the same row. |
| Append-only, single writer, one fsync per batch, framed JSONL, byte-copy materialize, torn-tail truncation | Boring, auditable, `jq`-able, restorable. Do not trade this for a binary store. |
| `balance` is provenance, never identity or semantics | Correct and unusual. It powers the one check no other consumer performs. |
| No category stored on a transaction; rules derived; corrections as decisions | It is why recategorising history is free, and why a correction survives rule churn. |
| Source type binds the parser, one parser per source; whole-file validation before sending | Bank formats lie in different directions; this is how you find out loudly. |
| `ATTESTATION` as a structural line kind, `balanceSource` per account | Cash is the honest edge of a ledger; keep it structural. |
| HELD / REVIEW / EXTERNAL; ambiguity to review; double-confirmed decisions | The workflow is the product. |
| The reconciliation tripwire (`Σ amount == closing − opening`, never guess when broken) | This is the difference between a log and a ledger. |
| One-way Firefly projection; category as tag, never Firefly's `category` field | Keeps the two systems from fighting over the same field. |
| `DECISIONS.md` and the tests-as-spec habit | The reason this proposal can be concrete instead of hand-wavy. |

---

## 3. Where the next 5–10% is

| Symptom in v1 | Root cause | v2 move |
|---|---|---|
| Changing a matching rule cannot change a past decision; dry-run is the only way to see rule effects. | Automatic results are written as journal lines. | Split **facts** from **decisions**; derive matching. Reflow. |
| A parser fix (PDF extraction, normalization) re-mints ids for affected rows, permanently. | Parsed output is hashed; the raw source is discarded. | **Evidence store** + `SUPERSEDE` decisions; identity contract unchanged. |
| Every decision appends a full event copy; mirrors carry every version; each reader re-implements "latest line wins". | One stream conflates observation and conclusion. | Facts never repeat; decisions are tiny; one fold, one derivation. |
| The blotter's query engine is hand-rolled (`GridIndex`/`GridQuery`), and adding a filter is Java work. | No query layer. | Rebuildable **SQLite** read model; the UI sends SQL-shaped intent. |
| Weekly review has no memory and no cadence; anomalies are noticed by staring. | Review is a queue, not a routine. | **Eyeball mode**, per-user `USER_ACK` markers, anomaly queries, "changed since read". |
| Firefly can drift (missed runs, hand edits, supersedes) and nothing reports it. | Egress is fire-and-forget. | **Convergence**: plan / apply / verify with a drift taxonomy. |
| Category rules have no health signals; pins accumulate unexamined. | Rules are data but have no feedback loop. | **Workbook**: lint, regression fixtures, suggestions from pins and uncategorised clusters. |
| A mis-pairing or bad `MARK_EXTERNAL` is permanent by design. | Decisions cannot be undone. | Decisions remain append-only but are revocable: family inverses plus `REVOKE` (§6.2, §13). |

---

## 4. Storage: log as truth, database as derived

You asked "running journal or a DB". My recommendation is neither extreme:

> **The append-only text log is the system of record. SQLite is a derived, disposable
> read model.** Nothing may be written to SQLite that is not reproducible from the log,
> the evidence store, the config and the derivation instant (`asOf`, §9.1).

### Why not "just a database"

- The log's properties — text, diffable, `jq`-able, trivially backed up, append-only by
  construction — are the reason a decade of history will still be readable. A schema is
  a promise you have to migrate; a line format is a promise you can extend.
- The audit trail is the product. "Show me what the bank said and what I decided" is a
  `grep`, not a join across mutable tables.
- You already have the log, the recovery path and the tests for it. Throwing that away
  to gain query convenience trades the strongest part for the weakest.

### Why not "journal only" (v1)

- The moment you want "total by category for Q3, excluding transfers, only accounts I
  own", you are writing a query engine. v1 did — `GridIndex`, `GridQuery`, hand-rolled
  sort/paging/caching. That is the tax this proposal removes.
- Derived state is not just for the UI: reconciliation, review queues, projections and
  anomaly reports are all queries, and SQL is how you write them.

### Why SQLite specifically

Single file, no server, transactional, WAL readers-don't-block, embedded in the JVM you
already ship, and stable for decades. One writer is the indexer; everyone else opens
read-only. Corruption is a non-event because the file is disposable.

### Options considered, and rejected at this stage

| Option | Verdict |
|---|---|
| SQLite as system of record | Rejected. Loses the audit/diff/backup properties that v1 is built on. |
| PostgreSQL | Rejected for one user. It is a server to run, back up, secure and upgrade; the derived model needs none of that. |
| DuckDB as the primary store | Rejected as primary, but **keep as an optional read-only analytics attach** (Parquet export of the index) if weekly/trend analysis ever outgrows SQLite. |
| An event-sourcing framework (Axon et al.) | Rejected. You need 20% of event sourcing — append-only, rebuildable views — and none of the ceremony. `derive()` is a pure function; that is the whole framework. |
| Embedding a rules engine | Rejected for the same reason as v1's C3: no library knows this domain. Reflow makes rule semantics *more* important, not less, so keep them typed and load-time-checked. |

### The rebuild test

The design has one acceptance criterion above all others:

```sh
systemctl stop trex-hub   # the index is private to it (§7.4)
rm -rf index/             # the SQLite file and anything cached
trex index --rebuild      # from log + evidence + config; refuses while the hub holds the lock
trex verify               # reconciliation green, review queues identical, projections plan-empty
systemctl start trex-hub
```

`trex index --rebuild` is the offline spelling of "stop the hub, delete the file, start
it": it takes the same index lock as `trex-hub` and fails loudly if the hub is live.

If that is ever untrue, the read model has grown a secret. Make it a test.

---

## 5. Target architecture

### 5.1 Layers

```
   sources                        ┌────────────────────────────────────────────────┐
   (CSV, PDF, feed, manual)       │                      trex                      │
        │                         │                                                │
        ▼                         │   evidence/                    config/          │
   ┌───────────┐   parse+validate │   (raw bytes,                  (yaml, git)      │
   │  ingest   │                  │    content-addressed)                          │
   └─────┬─────┘                  │                                                │
         │  writes evidence       │                                                │
         └───────────────────────►│                                                │
         │  POST /facts (HTTP)    │   ┌────────────┐    append + fsync            │
         └────────────────────────┼──►│ sequencer  │───────────────► trex.jsonl     │
                                  │   └────────────┘    (facts + decisions:         │
                                  │                      the source of record)      │
                                  └───────────────────────┬────────────────────────┘
                                                          │ watch, offset
                                                          ▼
                                  derive(facts, decisions, config, asOf)
                                                          │
                                    ┌─────────────────────┼────────────────────┐
                                    ▼                     ▼                    ▼
                               SQLite index        review queue         projections
                               (disposable)        (disposable)         (disposable)
                                    │                     │                    │
                                    ▼                     ▼                    ▼
                               blotter UI       eyeball/exceptions        Firefly
```

Read it as: **left of `derive` is truth; right of it is cache.**

### 5.2–5.4 Modules, processes, disk (as built)

The module boundary, the process set, the one-artifact/many-roles deployment and the on-disk
layout are **as built**: specified by `V2-SPEC.md` §2, §14 and `docs/DEPLOYMENTS.md`, not
restated here. The invariant to hold:

> **Only `trex-sequencer` writes the log.** Every other component is a reader or a derivation;
> `trex-hub` owns the disposable SQLite index and the rule files and never writes the journal.

One artifact, many roles: the role is chosen at launch (`trex sequencer`, `trex hub`,
`trex egress firefly`), never a separate artifact and never baked-in config.

### 5.5 The trigger runner

`trex runner` is the on-demand dispatcher. It exposes a small loopback HTTP API and a Jobs
view in the hub UI (the **hub** proxies to it; the runner is never published). A job is an
invocation of an existing subcommand — `ingest`, `egress firefly --plan/--verify/--apply`,
`stream export|ingest` — never new logic: the runner starts the same process a person would, streams its output and
records its exit code. It is a dispatcher, not a daemon in the §11 sense — it never polls,
and the passes it starts are the same one-shot batches.

It also owns the **staging inbox**: a statement uploaded through the browser (phone or
desktop) lands as a file, is listed with its source type and account, and is ingested on a
press (§12.5). Job runs are operational telemetry: they are never written to the log, and
never to the index the hub owns. The runner keeps a bounded, in-memory run history; the
durable record of an ingest is its evidence and its facts, and of a projection is Firefly
plus `projection_state`. A file's "ingested" **tick** is derived from the log (its evidence
id appears on facts), never stored — evidence is written *before* parsing, so ticking on
evidence would mark a rejected file as done.

The runner also **schedules**: an optional, intervals-only schedule triggers jobs such as the
**journal snapshot** — `POST /maintenance/snapshot` on the sequencer writes a dated
gzip copy of the log (`archive/journal/trex-<ts>.jsonl.gz`) and leaves the live journal
untouched. Snapshots are **copies, never rotations**; the byte mirror is kept forever and
snapshots are pruned by an explicit `prune-archive` job. A sync call waits up to a
configurable timeout (10 s by default) and otherwise returns the run handle; async returns
it immediately. An entry is `job`, `params?`, `every` (`1h…24h`, `7d`) and `at` (a local
`HH:MM` phase), plus — only for `7d` — `on` (one weekday) and `zone` (default UTC). It is a
*phase*, not a cron expression; a day-sized period is a calendar cadence, so the local wall
time survives a DST change, and a due job already running is skipped, never queued twice.

---

## 6. The log, v2

One file, `trex.jsonl`, still framed, still UTF-8, still one `n` per line, still
fsync-per-batch. The line becomes a discriminated union on `kind`.

Why one file rather than two: one writer, one offset, one fsync, one recovery path, and
the order of "fact, then the decision about it" is preserved naturally. The *kinds* are
what matter, not the file count. There are three: `fact`, `decision`, and `ingest` (§6.7).

**The envelope.** Every line carries the same header, written by the sequencer, before its
kind-specific body:

| field | meaning |
|---|---|
| `n` | sequence number; the cursor and the only ordering — global to the log, gapless |
| `kind` | namespaced kind: `trex.fact` \| `trex.decision` \| `trex.ingest` |
| `v` | the line-format version; **1** |
| `atMs` | event time, epoch millis (UTC) — the only time logic reads |
| `env` | the environment (`Dev1`, `Prod1`) |
| `source` | the writing process instance |
| `target` | the destination stream/consumer; `none` in trex |

`env`, `source` and `target` are `[A-Za-z0-9_]{1,8}` **right-padded with spaces to 8**; `env` is the
sequencer's own environment (`TREX_ENV`), `source` is declared in `sources.yaml` and refused if
unknown, and `target` is `none` (eight spaces) in trex. They are padded, not trimmed — no consumer
may `strip()` them. `at` is an **optional body field** (ISO-8601 UTC, millisecond) for readability
only. `n`, `atMs` and `at` never enter a hash or `derive()`. `atMs` echoes a client-supplied
`ingestedAt` when the batch carries one, so it is **not monotonic in `n`**: nothing may order by it
— order by `n`, and note that `DISMISS` already compares `n`.

A reader **skips an unknown `kind`** (warn, do not fail) and **refuses a known kind at a
higher `v`** (never silently misread); unknown fields are ignored, so an additive field is
not a version bump. Never reuse a field name with a new meaning. This is a **MAJOR**
log-format change (`RELEASE.md`): a v2 journal is transformed (§12.6) or re-ingested, not
read in place.

### 6.1 Fact

```json
{"n":8421,"kind":"trex.fact","v":1,"atMs":1790669462000,
 "env":"Dev1    ","source":"ING_0001","target":"        ",
 "externalId":"9e546cc0260ead1e",
 "accountRef":"ing-savings","date":"2026-09-24",
 "amount":-7599,"balance":399132,
 "rawDescription":"VISA PURCHASE COLES 1234 SYDNEY",
 "receipt":null,"occ":0,"observation":"posted",
 "sourceType":"ing-csv","provenance":"BANK",
 "evidenceId":"sha256:2f9c…","parser":"ing-csv/3",
 "at":"2026-09-29T08:11:02.000Z"}
```

`observation` is the one field v1 did not have, and it exists because of pending
authorisations: `posted` or `pending` is a property of what the source published, not a
conclusion trex reached, so it belongs on the fact. A pending fact is never dropped and
never counts — it is excluded from current totals, reconciliation and projection, shown
in its own lane, and settled or expired by derivation (§9.6, §12.3).

A fact stores **only what the source said, plus what identity needs**. Deliberately
gone: `description` (cleaning is a pure function — derive it), `state`, `flags`,
`transferKey`, `legIds`, `confidence`, `corrects`, `typeHint`, `currency`. Those are
conclusions or interpretations; each becomes a derivation:

| Field | v1 | v2 | Why |
|---|---|---|---|
| `description` | stored | `clean(rawDescription)` | Pure, swappable, never identity. Storing it freezes a decision. |
| `currency` | stamped from registry | derived from registry | A registry change is a config act; reflow should apply it. |
| `typeHint` | stored | derived (`ATTESTATION` when declared ∧ amount = 0, else sign) | Removes the v1 ambiguity; the rule lives in one place. |
| `observation` | n/a — pending rows were skipped | stored: `posted` \| `pending` | A property of what the source published. Pending is recorded, never counted (§9.6). |
| `state` | stored per version | derived from derivation + decisions | The whole point. |
| `transferKey`, `legIds`, `confidence` | stored on TRANSFER lines | derived | Pairing is either automatic (derived) or a decision (log). |
| `corrects` | stored, unused in phase 1 | `SUPERSEDE` decision | A correction is a decision with a reason, not a field. |
| `balance` | stored | stored | It is an observation — a fact. Whether it acts as a chain edge is a derived role (§6.9). |

**No fact carries a category.** A category is an answer computed from the latest fact
plus `categories.yaml` (rules), `refdata.yaml` (the declared names) and any `PIN`
decision naming that id. The invariant's precise form: **no category is ever a property
of a transaction** — rules derive one, and a person may decide one, and a decision is an
event, not a field. `category_current` in the index and the Firefly tag are copies of
that answer. A rule change is therefore retrospective for everything unpinned and inert
for everything pinned, which is the same decisions-win sentence as everywhere else.

Identity is unchanged and still frozen: `externalId` is minted from
`(accountRef, date, receipt)` or `(accountRef, date, amount, rawDescription, occ)`, with
`rawDescription` verbatim. v2 does not touch §2.4 of the SPEC. That is non-negotiable.

`occ` is assigned at first ingest (per account, per day, in the source's row order) and
**frozen on the fact**. Precisely: rows are processed in source order; an incoming row
whose `(amount, rawDescription)` matches an existing fact of the same account and day
claims that fact's `occ` one-for-one (the k-th identical incoming row claims the k-th
existing fact, in `occ` order); every remaining row takes the lowest `occ` not yet used
that day. A row the parser no longer reads keeps its index reserved; a row whose text
changed is matched by the re-parse tool (§8.2) and the replacement fact inherits its
`occ`. Even then, identity moves only because the text moved — never because `occ` did.
This is the one field where "derive again" must not move.

### 6.2 Decision

```json
{"n":8425,"kind":"trex.decision","v":1,"atMs":1790670660000,
 "env":"Dev1    ","source":"HUB_0001","target":"        ",
 "action":"PAIR","legA":"9e546cc0…","legB":"c3d41f…",
 "comment":"moved to savings","actor":"user","user":"ron",
 "at":"2026-09-29T08:31:00.000Z"}
```

The full action set — small on purpose:

| Action | Payload | Meaning |
|---|---|---|
| `PAIR` | `legA`, `legB`, `comment?` | This is a transfer between these two legs. Overrides the matcher. |
| `UNPAIR` | `legA`, `legB`, `comment?` | Not a transfer; never auto-match this pair. |
| `MARK_EXTERNAL` | `externalId`, `comment?` | An ordinary transaction, not a transfer leg; leave it alone. |
| `SETTLE` | `pendingId`, `postedId`, `comment?` | This pending observation was settled by that posted row. |
| `DISMISS` | `item`, `externalIds`, `comment?` | Silence review item(s) of that kind for those ids; `item` is a review kind (§7.2). |
| `PIN` | `externalIds`, `category`, `comment?` | Those ids are this category, regardless of the rules. The latest event mentioning an id wins. |
| `UNPIN` | `externalIds`, `comment?` | Release those ids back to rule evaluation. |
| `SUPERSEDE` | `fromId`, `toId`, `reason` | A re-parse or correction replaces one fact with another. |
| `RETIRE` | `externalId`, `reason` | The fact no longer counts and has no replacement. |
| `MARK_NOOP` | `externalId`, `reason` | Recorded, but not a posting of this account: no chain edge, no unit, no transfer leg, no sum. A classification, never a correction (§6.9). |
| `UNMARK_NOOP` | `externalId`, `comment?` | Return the row to its profile's default; the family inverse of `MARK_NOOP`. |
| `ATTACH_ACCOUNT` | `externalIds`, `account`, `comment?` | These transfer-shaped legs are transfers to/from `account` (a clearing account, §6.10); the contra is not held. |
| `REVOKE` | `revokes`, `comment?` | Undo decision `n = revokes`; the general escape hatch. |
| `USER_ACK` | `externalId`, `stateHash`, `configRevision`, `deriveVersion`, `hashVersion`, `comment?` | "I have read this row; its derived content was X." One line per row per user; the `user` is on the line and other users' markers are untouched. |
| `USER_UNACK` | `externalId`, `comment?` | Release that row's read marker for this user; the family inverse of `USER_ACK`. |
| `NOTE` | `externalId`, `text` | Free annotation; one per target row; a group is a batch of `NOTE`s. They accumulate as a thread, are removed only by `REVOKE`, and are never edited. Never identity, never logic. |
| `DECLARE_COMMITMENT` | `commitmentId`, `name`, `direction`, `cadence`, `amountKind`, `commitmentKind`, `matches[]` (`{match, account?}`), `amount?`, `anchor?`, `fromCandidate?`, `comment?` | Declare a commitment; confirm a detected candidate (`fromCandidate` = its key; the UI prefills `matches` from its descriptors). A re-declare with the same id is the edit. |
| `RETIRE_COMMITMENT` | `commitmentId`, `endedAt`, `reason` | End it (cancelled, past, provider move). |
| `IGNORE_RECURRING` | `candidate`, `reason` | Silence a detected candidate for good (revocable; newer facts do not reopen it). |
| `PIN_COMMITMENT` | `commitmentId`, `externalIds`, `comment?` | Those facts are occurrences of that commitment, whatever its rules say — the category `PIN` gesture. |
| `UNPIN_COMMITMENT` | `externalIds`, `comment?` | Release those facts back to rule matching; the family inverse of `PIN_COMMITMENT`. |
| `NOTE_COMMITMENT` | `commitmentId`, `text` | Free annotation on a commitment; accumulates as a thread, removed only by `REVOKE`, never edited — the `NOTE` gesture, targeted at a commitment. |
| `SETTLE_OCCURRENCE` | `commitmentId`, `dueDates[]`, `comment?` | Those occurrences are paid (or received): a conclusion, no fact — the off-journal settle. |
| `EXCLUDE_COMMITMENT` | `commitmentId`, `externalIds`, `comment?` | Those facts are not part of that commitment: a one-off, not the series. The pair is never claimed (a pin falls through to the rules); the fact itself is untouched. |
| `INCLUDE_COMMITMENT` | `commitmentId`, `externalIds`, `comment?` | Undo an exclusion; the family inverse of `EXCLUDE_COMMITMENT`. |

The commitment's kind is `commitmentKind` on the wire, not `kind`: the envelope already owns
`kind` (§6). Commitment curation is `V2-COMMITMENTS-PLAN.md` §2.6.

Every decision carries `actor` (`user`, `migrated`, `system`), a `user` id when a person
acted, and `at`. Nothing here is a full copy of anything. The complete catalogue with an
example of every event is §6.7.

**Decisions are never final.** A decision can always be undone by a later one, and the
undo is itself a decision (§13):

- **Family inverses** are the everyday path: `UNPAIR` answers `PAIR`, `MARK_EXTERNAL`
  answers a pairing, `UNPIN` answers `PIN`, `USER_UNACK` answers `USER_ACK`,
  `UNMARK_NOOP` answers `MARK_NOOP`, a later `PIN` re-pins, a later `USER_ACK` re-reads.
  For a leg, the latest effective pairing decision naming it wins; for an id, the latest
  category decision naming it wins (§9.8).
- **`REVOKE` is the general undo.** It names the `n` of the decision it revokes and is
  the only way back from `SUPERSEDE`, `RETIRE` and `DISMISS`. A `REVOKE` is itself a
  decision, so revoking a `REVOKE` restores the original: the latest answer wins.
- **A `DISMISS` speaks for what it saw.** It silences its items while no newer fact lands
  for any of the named ids; a new observation or a `SUPERSEDE` re-opens the question
  instead of letting changed content hide under an old dismissal.

Which decisions are effective is derived from their order and their `REVOKE`s — never
stored in a line, and never evaluated by the sequencer (§6.3, §9.8).

### 6.3 The fold is now trivial

```
for each line in n order:
    kind == fact     -> facts[n]  = line
    kind == decision -> decisions += line
```

There is no state carried on records and no rule evaluation in the writer. "Current" is
still a latest-observation rule (`txn_current` takes the highest `n` per chain-resolved
`externalId`), but it is a *content* rule, not a state machine: two observations of the
same id can differ only because the bank said something different, never because trex
changed its mind. Which decisions are effective — including every `REVOKE` — is computed
by `derive()` from the log's order (§9.8); the sequencer never evaluates it. The
sequencer becomes small enough to audit in one sitting, which is where you want it: it
is the only component that can destroy data.

### 6.4 Recovery

Identical to v1 (§3.2), and it gets simpler because the log is thinner:

- scan to the last complete, parseable line; truncate a torn tail;
- materialize `source → target` byte-for-byte when configured, SHA-256 verified;
- fold facts/decisions for validation only (counts, no parsing of derived semantics);
- on replay: `derive()` runs, but **decisions always win over derivation**. A rule
  change can never flip a decision, only change what is undecided.
- ids named by a decision are resolved through the supersession map before the decision
  is applied, so a re-parse that mints new ids cannot orphan an old `PAIR` or `PIN`
  (§9.8); a decision that no longer resolves is surfaced as `INEFFECTIVE_DECISION`,
  never dropped.

### 6.5 The write path: ingest → sequencer → journal

**It stays.** Same pipeline, same transport, same guarantees; only interpretation moves
out of it.

```
source file / feed
   │
   ▼
trex-ingest        parse all, validate all            (unchanged)
   │               group whole days, batch            (unchanged)
   │               POST /facts  { allOrNone, facts:[FactDraft…] }
   ▼
trex-sequencer     validate fields + registry         (unchanged)
   │               assignOcc (day-atomic)             (unchanged)
   │               mint externalId (frozen, §8.3)     (unchanged)
   │               dedup against seen observations     (unchanged, renamed outcome)
   │               append facts + fsync                (unchanged)
   │  ◄── Appended | Duplicate | Flagged | Rejected
   ▼
trex.jsonl ──watch──► trex-hub indexer ──derive(facts, decisions, config, asOf)──► SQLite ──► blotter
```

**Stays verbatim:**

- one parser per source type, whole-file validation before anything is sent;
- day-atomic batching and the `occ` guarantee (never split a calendar day);
- HTTP from ingest to sequencer (`java.net.http`, gzip both ways — and gzip still never
  touches the journal);
- one batch = one atomic append + one fsync; `n` assigned in append order; offsets;
- `allOrNone` validation semantics; per-row results; exit codes 0/1/2/3/64;
- the sequencer as the only writer; trex-hub still never writes the journal.

**Changes shape:**

| v1 | v2 | Why |
|---|---|---|
| `Candidate` | `FactDraft` | It never was a "would-be canonical event"; it is a draft observation. Drops `state`, `typeHint`, `transferKey`, `legIds`, `confidence`. |
| `POST /candidates` | `POST /facts` | Body `{ allOrNone, facts: […] }`. Cheap to rename: both ends are yours. |
| `Resolved(ref,id,n)` | `Appended(ref,id,n)` | Nothing is resolved at append time; the fact is recorded. |
| `Held`, `Flagged(REVIEW)` | gone from the response | HELD/REVIEW are derived. The client needs to know *what was recorded*, not what the ledger currently thinks. |
| `DroppedDuplicate` | `Duplicate` | Same meaning. |
| `Flagged(POTENTIAL_DUP)` | `Flagged` (kept) | Same id, new observation: the observation is appended **once**, and the review item is derived from the log — not written as a journal flag. |
| matching + state machine in sequencer | `derive()`, in the index | The change that buys reflow. |
| `GET /held`, `/review`, `/reconcile` on sequencer | on the index (`trex-hub`) | They are derived views; the writer has no opinion. |
| TRANSFER lines in the journal | derived `transfer` rows | `TRF-…` identity is unchanged; it is just not a fact. |

The sequencer's read surface therefore shrinks to `POST /facts`, `POST /decisions`,
`GET /head` — and that is a feature: the component that can destroy data should do the
least.

**Dedup, precisely** — the one place v1's behaviour is subtle, and the place v2 must be
exact. The rule in one line:

> The dedup key is the **whole observation**, not the row and not the `externalId`.
> Each distinct observation appears in the log exactly once; a re-delivered batch
> appends nothing.

Concretely, the key is the fact's stored content minus the operational metadata:
`externalId`, `accountRef`, `date`, `amount`, `rawDescription`, `receipt`, `occ`,
`balance`. `sourceType`, `provenance`, `evidenceId`, `parser`, `atMs` and `n` are
excluded — otherwise a PDF import of a row already seen via CSV would look new, and a
re-import would never match.

| Incoming draft | Outcome | Log effect |
|---|---|---|
| New `externalId` | `Appended` | one fact |
| Existing id, **identical observation** (all key fields, balance included) | `Duplicate` | nothing |
| Existing id, **new observation, same content, different balance** | `Flagged` | one fact, once; a `POTENTIAL_DUP` item is then **derived**, not written |
| Existing natural-key id (same `receipt`), **amount or text differs** | `Flagged` | one fact, once; a `RESTATEMENT` item is then **derived**, not written |
| Same batch, two identical content-hash rows | two ids (`occ` 0 and 1) → two `Appended` | two facts — they are two transactions, not duplicates |
| Same batch, two identical natural-key rows (same `receipt`) | first `Appended`, second `Duplicate` | one fact |
| Existing id, same content, arriving from a second source | `Duplicate` | nothing — the second source is visible in the evidence store, not as a second fact |

Why this shape and not the alternatives:

- **Drop every same-id draft** would rebuild v1's `POTENTIAL_DUP` as an ephemeral
  response message: the conflicting balance would exist only in a log line at most, the
  review item would not be reproducible from the log, and a genuine second transaction
  hidden by a cross-file `occ` collision would leave no durable trace.
- **Append every same-id draft** breaks invariant §0.5: re-delivering a batch would
  grow the log every time.
- The observation key gets both: **re-delivery is a no-op**, and a new observation is
  kept exactly once and handed to derivation as a question, never silently accepted.

Consequences worth stating:

- `Duplicate` is the **only outcome that writes nothing**; a batch of nothing but
  duplicates appends no line and performs no fsync.
- The observation set is itself a fold of the log, so it is rebuilt on recovery — dedup
  survives a restart with no sidecar.
- The current fact for an id is the **latest observation of its supersession chain**
  (`txn_current`, §8.3); the review item shows the older and newer side by side;
  `DISMISS` silences it, and `REVOKE` un-silences it.
- A mass balance restatement (the bank re-issues a statement with rebased running
  balances) is N new observations and N review items. The review UI recognises the
  cluster — same account, one import, balances shifted together — and offers one action
  for the set: a single `DISMISS` may name many `externalIds` (§6.2). It is the same
  event, not N coincidences.

Everything else — what "transfer-shaped" means, which legs match, whether a row is HELD,
whether a potential duplicate is real — is a rule in `derive()`, so it can be tuned and
reflowed. The write path's only opinions are the ones identity cannot exist without.

### 6.6 Users and attribution

Users exist because a household has more than one pair of eyes, not because trex has
permissions. The model is deliberately small: **attribution, not authorization.**

- `users.yaml` in the config directory declares the people: `id` (stable, stamped into
  decisions forever), `name` (display), `active` (false for someone who has stopped; ids
  are never reused) and an optional `cadence` (`weekly`, `fortnightly`, `monthly`,
  `yearly`, …) used only to phrase a gentle reminder — never for logic. A missing or
  empty file is a startup error, and the sequencer rejects a decision naming an unknown
  id — the same posture as an unknown `accountRef`.
- Every decision carries `actor` (`user | migrated | system`) and, when a person acted,
  `user`. That is the whole permission model: anyone may decide anything, and the log
  says who did.
- Every user sees every transaction. There is no per-user scoping in phase 1: this is a
  trusted household, the services are loopback-first, and the label answers "who did
  this, and who has looked".
- **Resolution is global.** HELD, REVIEW and stale pending are one shared work queue:
  any user may resolve any item. Two users acting at once is safe — the hub's precheck
  rejects the second decision with a `409` because its view moved, and the UI shows
  "resolved by X" as soon as the SSE frame lands. If a stale decision still reaches the
  sequencer, it is recorded (§6.8) and derivation marks it ineffective rather than
  silently applying it.
- **Eyeballing is personal, and so is its cadence.** `USER_ACK` is per `(user, row)`:
  four people looking at the same ledger produce four independent attestations, not one
  shared sign-off (§9.4). One may read daily, one weekly, one yearly — the queue waits,
  the backlog is visible to that person, and no one else's view changes. There is
  deliberately no "jointly reviewed" state.
- Ingest optionally records the operator on the evidence record; facts themselves stay
  bank-only and carry no user. A `PIN`/`UNPIN` is a decision, so attribution is native;
  rules stay attributed by git.
- `users.yaml` is config, not index — a person cannot live only in a database that is
  disposable. If per-user account scoping is ever wanted (the kids' accounts), it is a
  view filter over this file, never a partition of the journal.

API: `GET /api/users` returns `[{id, name, active}]`; `GET /api/acks` returns every
user's read markers with staleness computed (`{user, externalId, stateHash, stale,
ackedAt}`), and `POST /api/acks` takes `{user, externalId, action: "ACK" | "UNACK",
comment?}` for one row at a time. Mutating requests carry the acting user — the UI has a
user switcher, the CLI takes `--user` where it matters — and the sequencer validates it.

### 6.7 Every sequencer event, with an example

Three line kinds, from three write endpoints (`POST /facts`, `POST /decisions`,
`POST /ingest`), with the migration and re-parse tools using the same endpoints. This is
the complete catalogue — anything absent is not in the journal.

Every line below carries the envelope of §6 (`n, kind, v, atMs, env, source, target`) before
its body.

| # | Event | Producer | Line | Written when |
|---|---|---|---|---|
| 1 | Posted observation | `POST /facts` | `fact` | A source publishes a settled row |
| 2 | Pending observation | `POST /facts` | `fact` | A source publishes an authorisation |
| 3 | Manual purchase | `POST /api/cash` → `POST /facts` | `fact` | A person records cash spending |
| 4 | Attestation | `POST /api/cash` → `POST /facts` | `fact` | A person states a cash balance |
| 5 | `PAIR` | `POST /decisions` | `decision` | Confirm a transfer |
| 6 | `UNPAIR` | `POST /decisions` | `decision` | Reject a match |
| 7 | `MARK_EXTERNAL` | `POST /decisions` | `decision` | Rule out a transfer leg |
| 8 | `SETTLE` | `POST /decisions` | `decision` | Close a pending observation against the row that settled it |
| 9 | `DISMISS` | `POST /decisions` | `decision` | Silence review item(s) |
| 10 | `SUPERSEDE` | `POST /decisions` (re-parse tool or a correction) | `decision` | Replace a fact after a re-parse or correction |
| 11 | `RETIRE` | `POST /decisions` (re-parse tool or a correction) | `decision` | A fact must no longer count and has no replacement |
| 12 | `REVOKE` | `POST /decisions` | `decision` | Undo an earlier decision by its `n` |
| 13 | `USER_ACK` | `POST /decisions` | `decision` | A person reads one transaction row |
| 14 | `USER_UNACK` | `POST /decisions` | `decision` | A person releases a row's read marker |
| 15 | `NOTE` | `POST /decisions` | `decision` | Annotate |
| 16 | `PIN` | `POST /decisions` | `decision` | A person overrides a category |
| 17 | `UNPIN` | `POST /decisions` | `decision` | A person returns a row to the rules |
| 18 | `MARK_NOOP` | `POST /decisions` | `decision` | The row is recorded but is not a posting of this account (§6.9) |
| 19 | `UNMARK_NOOP` | `POST /decisions` | `decision` | Return the row to its profile's default |
| 20 | `DECLARE_COMMITMENT` | `POST /decisions` | `decision` | Declare a commitment, or confirm a detected candidate |
| 21 | `RETIRE_COMMITMENT` | `POST /decisions` | `decision` | End a commitment (cancelled, past, provider move) |
| 22 | `IGNORE_RECURRING` | `POST /decisions` | `decision` | Silence a detected candidate for good |
| 23 | `PIN_COMMITMENT` | `POST /decisions` | `decision` | Those facts are occurrences of that commitment |
| 24 | `UNPIN_COMMITMENT` | `POST /decisions` | `decision` | Release those facts back to rule matching |
| 25 | `NOTE_COMMITMENT` | `POST /decisions` | `decision` | Annotate a commitment |
| 26 | `SETTLE_OCCURRENCE` | `POST /decisions` | `decision` | Those occurrences were paid without a fact |
| 27 | `EXCLUDE_COMMITMENT` | `POST /decisions` | `decision` | Those facts are one-offs, not part of that commitment |
| 28 | `INCLUDE_COMMITMENT` | `POST /decisions` | `decision` | Undo an exclusion (the family inverse) |
| 29 | Ingest started | `POST /ingest` | `ingest` (`start`) | An ingest begins: evidence stored, facts about to be sent |
| 30 | Ingest completed | `POST /ingest` | `ingest` (`complete`) | The ingest ends, with counts and status |

**Facts (1–4)**

```json
// 1 · posted bank row
{"n":8421,"kind":"trex.fact","v":1,"atMs":1790669462000,
 "env":"Dev1    ","source":"ING_0001","target":"        ",
 "externalId":"9e546cc0260ead1e",
 "accountRef":"ing-savings","date":"2026-09-24",
 "amount":-7599,"balance":399132,
 "rawDescription":"VISA PURCHASE COLES 1234 SYDNEY",
 "receipt":null,"occ":0,"observation":"posted",
 "sourceType":"ing-csv","provenance":"BANK",
 "evidenceId":"sha256:2f9c…","parser":"ing-csv/3",
 "at":"2026-09-29T08:11:02.000Z"}
```

```json
// 2 · pending authorisation — recorded and visible, never counted
{"n":8422,"kind":"trex.fact","v":1,"atMs":1790669560000,
 "env":"Dev1    ","source":"ING_0001","target":"        ",
 "externalId":"c3d41f7a9b2e4061",
 "accountRef":"bw-credit-card","date":"2026-09-28",
 "amount":-4995,"balance":0,
 "rawDescription":"AUTHORISATION ONLY  BP FUEL 1234",
 "receipt":null,"occ":0,"observation":"pending",
 "sourceType":"bw-csv","provenance":"BANK",
 "evidenceId":"sha256:77ab…","parser":"bw-csv/2",
 "at":"2026-09-29T08:12:40.000Z"}
```

```json
// 3 · hand-entered cash purchase — the MAN- ref is the receipt, so identity is the natural key
{"n":8423,"kind":"trex.fact","v":1,"atMs":1790704931000,
 "env":"Dev1    ","source":"HUB_0001","target":"        ",
 "externalId":"a7e1c94d06f3b28a",
 "accountRef":"cash-ron","date":"2026-09-28",
 "amount":-4000,"balance":0,
 "rawDescription":"Market stall - vegetables",
 "receipt":"MAN-01J8ZQ4K2W7C3M6T9VYB2F0NHA","occ":0,"observation":"posted",
 "sourceType":"manual","provenance":"AUTHORED",
 "evidenceId":null,"parser":"manual/1",
 "at":"2026-09-29T18:02:11.000Z"}
```

```json
// 4 · cash attestation — a declared account plus amount 0 is derived as ATTESTATION
{"n":8424,"kind":"trex.fact","v":1,"atMs":1790704982000,
 "env":"Dev1    ","source":"HUB_0001","target":"        ",
 "externalId":"b2f0a6e18c4d7f39",
 "accountRef":"cash-ron","date":"2026-09-29",
 "amount":0,"balance":16000,
 "rawDescription":"Cash attestation",
 "receipt":"MAN-01J8ZR7P5X0D4Q8W3NZK6C1TBV","occ":0,"observation":"posted",
 "sourceType":"manual","provenance":"AUTHORED",
 "evidenceId":null,"parser":"manual/1",
 "at":"2026-09-29T18:03:02.000Z"}
```

**Decisions (5–26).** All share the envelope of §6 and the body fields `action`, `actor`
(`user | migrated | system`), `user` when a person acted, and the optional `at`. `source` is
the process that submitted the decision.

```json
// 5 · PAIR
{"n":8425,"kind":"trex.decision","v":1,"atMs":1790705400000,
 "env":"Dev1    ","source":"HUB_0001","target":"        ",
 "action":"PAIR","legA":"9e546cc0260ead1e","legB":"c3d41f7a9b2e4061",
 "comment":"moved to savings","actor":"user","user":"ron",
 "at":"2026-09-29T18:10:00.000Z"}
```

```json
// 6 · UNPAIR
{"n":8426,"kind":"trex.decision","v":1,"atMs":1790705551000,
 "env":"Dev1    ","source":"HUB_0001","target":"        ",
 "action":"UNPAIR","legA":"9e546cc0260ead1e","legB":"c3d41f7a9b2e4061",
 "comment":"not a transfer — paid Sam for the tyres",
 "actor":"user","user":"priya","at":"2026-09-29T18:12:31.000Z"}
```

```json
// 7 · MARK_EXTERNAL
{"n":8427,"kind":"trex.decision","v":1,"atMs":1790705582000,
 "env":"Dev1    ","source":"HUB_0001","target":"        ",
 "action":"MARK_EXTERNAL","externalId":"9e546cc0260ead1e",
 "comment":"ordinary payment to a person",
 "actor":"user","user":"priya","at":"2026-09-29T18:13:02.000Z"}
```

```json
// 8 · SETTLE — a person closes the pending lane with the row that settled it
{"n":8428,"kind":"trex.decision","v":1,"atMs":1790705660000,
 "env":"Dev1    ","source":"HUB_0001","target":"        ",
 "action":"SETTLE","pendingId":"c3d41f7a9b2e4061","postedId":"f0a1b2c3d4e5f607",
 "comment":"the authorisation became the fuel purchase",
 "actor":"user","user":"ron","at":"2026-09-29T18:14:20.000Z"}
```

```json
// 9 · DISMISS — one event can silence a cluster (a rebased statement)
{"n":8429,"kind":"trex.decision","v":1,"atMs":1790705744000,
 "env":"Dev1    ","source":"HUB_0001","target":"        ",
 "action":"DISMISS","item":"POTENTIAL_DUP","externalIds":["77ab04c1d9e3f802","18ce25aa0b7f4e91"],
 "comment":"bank re-issued the statement with a new running balance",
 "actor":"user","user":"ron","at":"2026-09-29T18:15:44.000Z"}
```

```json
// 10 · SUPERSEDE — written by the re-parse tool, so the actor is system
{"n":8430,"kind":"trex.decision","v":1,"atMs":1790708400000,
 "env":"Dev1    ","source":"ING_0001","target":"        ",
 "action":"SUPERSEDE","fromId":"9e546cc0260ead1e","toId":"4b81d0c9f27a6e34",
 "reason":"cba-pdf/4 fixed continuation joining",
 "actor":"system","at":"2026-09-29T19:00:00.000Z"}
```

```json
// 11 · RETIRE — the re-parse no longer reads this row; it must not count
{"n":8431,"kind":"trex.decision","v":1,"atMs":1790708430000,
 "env":"Dev1    ","source":"ING_0001","target":"        ",
 "action":"RETIRE","externalId":"a7e1c94d06f3b28a",
 "reason":"manual entry was a double-up; no replacement row",
 "actor":"system","at":"2026-09-29T19:00:30.000Z"}
```

```json
// 12 · REVOKE — undo an earlier decision by its n; nothing is deleted
{"n":8432,"kind":"trex.decision","v":1,"atMs":1790708520000,
 "env":"Dev1    ","source":"HUB_0001","target":"        ",
 "action":"REVOKE","revokes":8429,
 "comment":"those were real duplicates after all",
 "actor":"user","user":"ron","at":"2026-09-29T19:02:00.000Z"}
```

```json
// 13 · USER_ACK — one line per user per row
{"n":8433,"kind":"trex.decision","v":1,"atMs":1790708650000,
 "env":"Dev1    ","source":"HUB_0001","target":"        ",
 "action":"USER_ACK","externalId":"9e546cc0260ead1e",
 "configRevision":"sha256:7c1a…","deriveVersion":"derive/2","hashVersion":"statehash/2",
 "stateHash":"sha256:6e21…","comment":null,
 "actor":"user","user":"ron","at":"2026-09-29T19:04:10.000Z"}
```

```json
// 14 · USER_UNACK — release this row for this user
{"n":8434,"kind":"trex.decision","v":1,"atMs":1790708700000,
 "env":"Dev1    ","source":"HUB_0001","target":"        ",
 "action":"USER_UNACK","externalId":"9e546cc0260ead1e","comment":"double-checking this one",
 "actor":"user","user":"ron","at":"2026-09-29T19:05:00.000Z"}
```

```json
// 15 · NOTE
{"n":8435,"kind":"trex.decision","v":1,"atMs":1790708880000,
 "env":"Dev1    ","source":"HUB_0001","target":"        ",
 "action":"NOTE","externalId":"9e546cc0260ead1e",
 "text":"reimbursed by work, not a personal expense",
 "actor":"user","user":"priya","at":"2026-09-29T19:08:00.000Z"}
```

```json
// 16 · PIN — one event can cover a cluster
{"n":8436,"kind":"trex.decision","v":1,"atMs":1790709120000,
 "env":"Dev1    ","source":"HUB_0001","target":"        ",
 "action":"PIN","externalIds":["9e546cc0260ead1e","c3d41f7a9b2e4061"],
 "category":"TAXES","comment":"ATO instalment, not a bank fee",
 "actor":"user","user":"ron","at":"2026-09-29T19:12:00.000Z"}
```

```json
// 17 · UNPIN — back to the rules
{"n":8437,"kind":"trex.decision","v":1,"atMs":1790709240000,
 "env":"Dev1    ","source":"HUB_0001","target":"        ",
 "action":"UNPIN","externalIds":["9e546cc0260ead1e"],
 "comment":"rule now covers this",
 "actor":"user","user":"priya","at":"2026-09-29T19:14:00.000Z"}
```

```json
// 18 · MARK_NOOP — recorded, but not a posting of this account
{"n":8438,"kind":"trex.decision","v":1,"atMs":1790709300000,
 "env":"Dev1    ","source":"HUB_0001","target":"        ",
 "action":"MARK_NOOP","externalId":"a7e1c94d06f3b28a",
 "reason":"fee paid from the loan-offset account; this loan line is a reference",
 "actor":"user","user":"ron","at":"2026-09-29T19:15:00.000Z"}
```

```json
// 19 · UNMARK_NOOP — back to the profile's default
{"n":8439,"kind":"trex.decision","v":1,"atMs":1790709360000,
 "env":"Dev1    ","source":"HUB_0001","target":"        ",
 "action":"UNMARK_NOOP","externalId":"a7e1c94d06f3b28a",
 "comment":"the charge was real after all",
 "actor":"user","user":"ron","at":"2026-09-29T19:16:00.000Z"}
```

```json
// 20 · DECLARE_COMMITMENT — confirm a detected candidate; the rules come from its descriptors
{"n":8440,"kind":"trex.decision","v":1,"atMs":1790709480000,
 "env":"Dev1    ","source":"HUB_0001","target":"        ",
 "action":"DECLARE_COMMITMENT","commitmentId":"netflix","name":"Netflix",
 "direction":"out","cadence":"monthly","amountKind":"fixed","commitmentKind":"subscription",
 "matches":[{"match":"PAYPAL \\*NETFLIX AUS","account":"ing-savings"},
            {"match":"NETFLIX\\.COM","account":null}],
 "amount":999,"anchor":"2025-09-15","fromCandidate":"PAYPAL NETFLIX AUS",
 "comment":"confirmed from the detected series",
 "actor":"user","user":"ron","at":"2026-09-29T19:18:00.000Z"}
```

```json
// 21 · RETIRE_COMMITMENT — a provider move: AGL ends here; Origin is a new commitment
{"n":8441,"kind":"trex.decision","v":1,"atMs":1790709540000,
 "env":"Dev1    ","source":"HUB_0001","target":"        ",
 "action":"RETIRE_COMMITMENT","commitmentId":"agl","endedAt":"2026-08-01",
 "reason":"provider move to Origin; the two histories stand alone",
 "actor":"user","user":"ron","at":"2026-09-29T19:19:00.000Z"}
```

```json
// 22 · IGNORE_RECURRING — a candidate silenced for good; REVOKE is the way back
{"n":8442,"kind":"trex.decision","v":1,"atMs":1790709600000,
 "env":"Dev1    ","source":"HUB_0001","target":"        ",
 "action":"IGNORE_RECURRING","candidate":"YOUTUBEPREMIUM",
 "reason":"cancelled; newer facts must not reopen it",
 "actor":"user","user":"ron","at":"2026-09-29T19:20:00.000Z"}
```

```json
// 23 · PIN_COMMITMENT — a person places a fact the rules miss; the latest pin wins
{"n":8443,"kind":"trex.decision","v":1,"atMs":1790709720000,
 "env":"Dev1    ","source":"HUB_0001","target":"        ",
 "action":"PIN_COMMITMENT","commitmentId":"netflix","externalIds":["9e546cc0260ead1e"],
 "comment":"BPAY payment; the descriptor never reaches a rule",
 "actor":"user","user":"ron","at":"2026-09-29T19:22:00.000Z"}
```

```json
// 24 · UNPIN_COMMITMENT — release the facts back to the rules (the family inverse of PIN_COMMITMENT)
{"n":8444,"kind":"trex.decision","v":1,"atMs":1790709780000,
 "env":"Dev1    ","source":"HUB_0001","target":"        ",
 "action":"UNPIN_COMMITMENT","externalIds":["9e546cc0260ead1e"],
 "comment":"the rule now covers this descriptor",
 "actor":"user","user":"ron","at":"2026-09-29T19:23:00.000Z"}
```

```json
// 25 · NOTE_COMMITMENT — a thread on the commitment; removed only by REVOKE, never edited
{"n":8445,"kind":"trex.decision","v":1,"atMs":1790709900000,
 "env":"Dev1    ","source":"HUB_0001","target":"        ",
 "action":"NOTE_COMMITMENT","commitmentId":"netflix",
 "text":"price rise 2025-09: 7.99 to 9.99",
 "actor":"user","user":"ron","at":"2026-09-29T19:25:00.000Z"}
```

```json
// 26 · SETTLE_OCCURRENCE — off-journal paid: a conclusion, no fact
{"n":8446,"kind":"trex.decision","v":1,"atMs":1790710020000,
 "env":"Dev1    ","source":"HUB_0001","target":"        ",
 "action":"SETTLE_OCCURRENCE","commitmentId":"netflix",
 "dueDates":["2026-07-15","2026-08-15"],"comment":"paid in cash",
 "actor":"user","user":"ron","at":"2026-09-29T19:27:00.000Z"}

// 27 · EXCLUDE_COMMITMENT — a one-off is not the series; the fact stays a fact
{"n":8447,"kind":"trex.decision","v":1,"atMs":1790710080000,
 "env":"Dev1    ","source":"HUB_0001","target":"        ",
 "action":"EXCLUDE_COMMITMENT","commitmentId":"nrma-ltd",
 "externalIds":["1f4a2c9be7d30855"],"comment":"windscreen claim, not the premium",
 "actor":"user","user":"ron","at":"2026-09-29T19:28:00.000Z"}

// 28 · INCLUDE_COMMITMENT — undo the exclusion (the family inverse)
{"n":8448,"kind":"trex.decision","v":1,"atMs":1790710140000,
 "env":"Dev1    ","source":"HUB_0001","target":"        ",
 "action":"INCLUDE_COMMITMENT","commitmentId":"nrma-ltd",
 "externalIds":["1f4a2c9be7d30855"],"comment":"wrong row",
 "actor":"user","user":"ron","at":"2026-09-29T19:29:00.000Z"}
```

Migration writes the same decision shapes with `actor:"migrated"` — a `PAIR` for every
transfer the v1 journal had resolved, a `MARK_EXTERNAL` for every leg it had marked,
no `user` — so the migrated state is exactly what v1 had (§16).

**What is deliberately not an event.** No request batch header — a `POST /facts` batch is a
request, not a line (the *ingest workflow* around it emits `ingest` events, 29–30); no state
transitions (derived); no TRANSFER lines (derived); no control or watermark lines (removed in
v1); no edits (a correction is a `SUPERSEDE`, `RETIRE` or `REVOKE` — lines are never
rewritten); no period review state (a period is only the view the Eyeball buckets rows by,
never something to clear, §9.4). Category *rules* are not events — files are their home
— but a category *decision* is (`PIN`/`UNPIN`, 16–17).
If it is not in the table above, the journal does not contain it.

**Responses are not events either.** `POST /facts` answers
`Appended | Duplicate | Flagged | Rejected`; `POST /decisions` answers
`Resolved | Rejected`; both share `{ batchHandle, batchStatus }`; `POST /ingest` appends one
event and answers `{ n }`. A response describes an *attempt*; the line describes what was
actually recorded.

### 6.8 The lifecycles, explicitly

§6.7 is the alphabet; this is the grammar. Nothing is ever deleted, so every lifecycle
is a chain of appended events plus derived transitions:

| Lifecycle | States | Transition driven by |
|---|---|---|
| Observation | `pending → settled` | derivation: a plausible posted row arrives, or a `SETTLE` decision |
| | `pending → stale` | derivation: the posted-fact frontier passes it unsettled; `DISMISS` silences it |
| | `posted → superseded` | event: `SUPERSEDE` after a re-parse or correction |
| | `posted → retired` | event: `RETIRE` — the fact no longer counts, with no replacement |
| Role | `transaction → noop` | derivation: an account profile rule, or a `MARK_NOOP` decision |
| | `noop → transaction` | derivation: `UNMARK_NOOP`, or the profile rule removed |
| Decision | `issued → effective` | derivation applies it |
| | `issued → ineffective` | derivation rejects it (e.g. a `PAIR` of same-signed legs, an unresolvable id) → review item |
| | `issued → revoked` | event: a later `REVOKE` (or a family inverse); revoking the `REVOKE` restores it |
| Transfer leg | `new → held → paired` | derivation (contra arrives) or `PAIR` |
| | `paired → unpaired` | event: `UNPAIR` or a reflow; the legs return to the pool |
| | `held → external` | event: `MARK_EXTERNAL` |
| Category | `rule answer → pinned → re-pinned \| unpinned` | events: `PIN` / `UNPIN`; otherwise derived |
| Review item | `open → resolved` | derivation: the cause is gone |
| | `open → dismissed → open` | event: `DISMISS`; a newer fact or a `REVOKE` re-opens it |
| Read marker | `unread → read → changed → read` | `USER_ACK`, a reflow, then a re-ack; `USER_UNACK` releases |
| Projection | `planned → posted → drifted → corrected` | the egress; `human-owned` is terminal-but-reported |

One diagram, seven lifecycles — every transition is labelled by what drives it:
**event** (an appended decision), **derive** (a pure recomputation) or **reflow** (a
rule/config change). Rendered standalone: `docs/v2-lifecycles.html`.

```mermaid
stateDiagram-v2
    direction LR

    state "Fact · observation (§6.1)" as Fact {
        direction TB
        [*] --> f_posted : source publishes a settled row
        [*] --> f_pending : source publishes an authorisation
        state "posted" as f_posted
        state "pending" as f_pending
        state "settled" as f_settled
        state "stale" as f_stale
        state "superseded" as f_superseded
        state "retired" as f_retired
        f_pending --> f_settled : derive · a unique posted row (settled_by)
        f_pending --> f_settled : event · SETTLE
        f_pending --> f_stale : derive · frontier + settlementWindowDays
        f_stale --> f_settled : event · SETTLE (late)
        f_superseded --> f_posted : event · REVOKE (restore)
        f_retired --> f_posted : event · REVOKE (restore)
        f_posted --> f_superseded : event · SUPERSEDE (re-parse / correction)
        f_posted --> f_retired : event · RETIRE
    }

    state "Decision (§6.2)" as Decision {
        direction TB
        [*] --> d_issued
        state "issued" as d_issued
        state "effective" as d_effective
        state "ineffective" as d_ineffective
        state "revoked" as d_revoked
        d_issued --> d_effective : derive · it applies
        d_issued --> d_ineffective : derive · it rejects → INEFFECTIVE_DECISION
        d_issued --> d_revoked : event · REVOKE, or a family inverse
        d_effective --> d_revoked : event · a later decision answers it
        d_revoked --> d_effective : event · REVOKE of the REVOKE (latest wins)
        d_ineffective --> d_effective : event · corrected (REVOKE / replacement)
    }

    state "Transfer leg (§9.7)" as Leg {
        direction TB
        [*] --> l_pool : posted, not transfer-shaped
        state "in the pool" as l_pool
        state "held" as l_held
        state "paired" as l_paired
        state "unpaired" as l_unpaired
        state "external" as l_external
        l_pool --> l_held : derive · transfer-shaped, no contra
        l_held --> l_paired : derive · contra arrives, or event · PAIR
        l_paired --> l_unpaired : event · UNPAIR, or reflow
        l_unpaired --> l_paired : event · PAIR
        l_held --> l_external : event · MARK_EXTERNAL
        l_external --> l_pool : event · REVOKE
    }

    state "Category (§9.3)" as Category {
        direction TB
        [*] --> c_rule : derive · first matching rule (or UNCATEGORIZED)
        state "rule answer" as c_rule
        state "pinned" as c_pinned
        c_rule --> c_rule : reflow · rules move
        c_rule --> c_pinned : event · PIN
        c_pinned --> c_pinned : event · later PIN (re-pin)
        c_pinned --> c_rule : event · UNPIN
    }

    state "Review item (§7.2)" as Review {
        direction TB
        [*] --> r_open : derive · dup / restatement / ambiguity / settlement / ineffective
        state "open" as r_open
        state "resolved" as r_resolved
        state "dismissed" as r_dismissed
        r_open --> r_resolved : derive · the cause is gone
        r_open --> r_dismissed : event · DISMISS
        r_dismissed --> r_open : derive · a newer fact lands, or event · REVOKE
    }

    state "Read marker (§9.4)" as Ack {
        direction TB
        [*] --> a_unread
        state "unread" as a_unread
        state "read" as a_read
        state "changed" as a_changed
        a_unread --> a_read : event · USER_ACK (user, row, stateHash)
        a_read --> a_unread : event · USER_UNACK
        a_read --> a_changed : reflow · the row's content moved
        a_changed --> a_read : event · re-acknowledge
    }

    state "Firefly projection (§11)" as Proj {
        direction TB
        [*] --> p_planned : derive · a projectable unit exists
        state "planned" as p_planned
        state "posted" as p_posted
        state "drifted" as p_drifted
        state "orphan" as p_orphan
        state "human-owned" as p_human
        p_planned --> p_posted : event · --apply (create)
        p_posted --> p_drifted : derive · category or content moved
        p_drifted --> p_posted : event · --apply (retag / update)
        p_posted --> p_orphan : derive · unit de-projected (UNPAIR / REVOKE / RETIRE)
        p_orphan --> [*] : event · --remove-orphans (on instruction)
        p_posted --> p_human : derive · a hand edit is detected (terminal, reported)
    }
```

Two rules make this a lifecycle system rather than a pile of states:

1. **Nothing is deleted, so terminal states are retired, not removed.** A superseded
   fact, an overridden decision and a dismissed review item all stay in the log; the
   index resolves them to their current state, and a rebuild resolves them identically.
2. **Every transition is either a derivation or an event.** If a state can change
   without one of the two, the model is wrong — that is the test for any new feature.

**The sequencer is event-based, not lifecycle-based, on purpose.** It validates
references and structure — the `user` exists, the referenced `externalId`s exist, a
`REVOKE` names an existing decision `n`, the category named by a `PIN` is declared in
`refdata.yaml`, the payload is well-formed —
because a dangling reference corrupts the log's meaning. It
does **not** enforce semantics — equal-and-opposite legs, same currency, legal state
transitions — because those are rules that must stay tunable and reflowable. The hub
prechecks them against its index for a fast, friendly `422`; `derive()` is where they are
true. A semantically bad decision that gets past both is recorded, surfaced as
`INEFFECTIVE_DECISION`, and corrected by a later decision — never silently dropped, and
never a write-path rule that a reflow cannot revisit.

### 6.9 Roles, the balance check, and conflict resolution

A bank statement is not always a ledger. ING's loan exports, for example, carry fee lines
whose balance column is a statement snapshot rather than a running balance: the row is
true as printed, but it is not a posting of that account. Treating every balance as a
chain edge turns such a statement into a reconciliation fault no parser can fix — and
rewriting the row to make the chain close would destroy what the bank said.

The system keeps every row exactly as stated (`balance` included) and separates **what a
row is** from **what the chain can prove**.

**Roles.** Every current fact has a derived role: `transaction` (the default) or `noop` —
recorded and visible, but not a posting: no chain edge, no transfer leg, no unit, no
contribution to the account's sums. A role is never stored on the line; `derive()`
computes it from the facts, `profiles.yaml` and the decisions, so the log stays faithful
and a role change is a reflow, not a re-parse. A `noop` row stays in the Blotter, marked
and filterable: it is evidence, not noise.

**Account profiles.** `profiles.yaml` binds rules to accounts — the account-scoped
analogue of `categories.yaml`:

```yaml
profiles:
  ing-variable-rate:
    rules:
      - match: 'Orange Advantage annual fee'
        action: MARK_NOOP
        reason: 'fee paid from the loan-offset account; this loan line is a reference'
```

A profile classifies by default; a `MARK_NOOP`/`UNMARK_NOOP` decision overrides it in
either direction — decisions win over derivation, latest effective wins, the same
precedence as `PIN`/`UNPIN` over category rules.

**The balance check.** Reconciliation runs over `transaction` rows only: exactly one
value with net `+1` (the opening), one with net `−1` (the closing), and
`Σ amount == closing − opening`. Every excluded `noop` row is named in the result with
the rule or decision that classified it. A fork with no explanation is `broken`; a chain
with explained exclusions is `reconciled` **with those exclusions listed** — never
silently clean. Nothing is rebalanced: the excluded row's edges and amount are not part
of the chain, and no other row's balance is adjusted. `RETIRE` is not this: it corrects
a source claim that must not count at all; `MARK_NOOP` classifies a claim as not a
posting.

**Forks are conflicts, resolved per side.** A fork is a value claimed by two rows — two
claimants at the same previous balance, or two rows closing into it. The hub pairs them
from the facts alone (a side never needs to remember the other), and presents the
**sides**, not the pair as a unit. Resolution is per row and iterative, like resolving a
merge conflict:

- `MARK_NOOP` one side: that side stops posting, the other lives, and the chain is
  recomputed. Only one side may resolve the conflict in practice — removing a
  load-bearing row (whose balance is another row's previous) simply leaves a different
  fork, which the preview shows.
- **Keep both**: the row counts; the bank's arithmetic genuinely disagrees. The fork is
  accepted as explained rather than resolved. Whether that is a config exception or a
  decision is deliberately still open.
- Every choice is previewed before it is written: "noop this side → reconciled at X;
  noop that side → two forks remain". The resolver never picks for you.

The hub opens a derived `BALANCE_BREAK` review item for a fork with no explanation, and
the ingest history annotates the batch that completed the fork. There is no announcement
line in the log: like every review item, it appears while the condition holds and clears
when it stops.

**What does not happen.** No rebalancing, no invented balancing entries, no rewrite of a
stated balance. A `noop` row already projected to Firefly becomes an orphan on the next
plan and is removed only on explicit instruction (§11.5). Undo is `UNMARK_NOOP` or
`REVOKE`; a profile edit re-derives immediately.

**Deferred.** A writer-side provisional flag (a facts-only chain warning at append time,
independent of the hub) was considered and is on the shelf: the hub owns meaning, and the
writer boundary stays intact (§6.8). If it is ever built it is a provisional ingest
warning only, and amending the writer's "no derived review flags" boundary is part of
that change, not an accident of it.

### 6.10 Clearing accounts

Some counterparties close before the journal begins: an old credit card, a paid-off loan. Their
statements are gone, but the money that moved to them is real, and calling those movements
"expenses" would distort both spending and net worth. A **clearing account** records such a
counterparty without inventing facts:

```yaml
accounts:
  - ref: "westpac-card"
    currency: "AUD"
    balanceSource: clearing
    closingBalance: 0          # cents; what the account was worth when it closed
    closedAt: "2025-06-30"     # optional; flags any later movement
  - ref: "nab-fixed"
    currency: "AUD"
    balanceSource: clearing
    closingBalance: 0
    closedAt: "2023-09-30"
  - ref: "nab-offset"          # the variable-rate facility's offset
    currency: "AUD"
    balanceSource: clearing
    closingBalance: 0
    closedAt: "2023-02-28"
```

- It holds **no facts** and is never chained — a declared position, not a statement. Its
  **opening is computed backwards** from the movements and the declared closing:
  `opening = closing − Σ movements`, so the derived balance lands exactly on the declared
  value. The computed opening is shown wherever the account is (Accounts view, reconcile):
  the approximation is visible, never silent.
- A transfer pattern may name a clearing account as its counterpart:

  ```yaml
  ing-orange:
    - { match: 'NAB Fixed Payments', rail: BANK_TRANSFER, clearing: nab-fixed }
    - { match: 'Nab Offset',         rail: BANK_TRANSFER, clearing: nab-offset }
    - { match: 'WestPac Auto Payment|Monthly payment .* To WESTPAC|WESTPAC CARDS',
        rail: BPAY, clearing: westpac-card }
  ```

  A matching leg is **paired directly with the account** — no contra fact, no window, no
  ambiguity — and stops being a unit of its own. The emitted transfer has one real leg and one
  account side; direction stays structural.
- **Retrofit is a config edit.** When real statements turn up, register the account as a
  statement account, ingest the history, and point the patterns at it (or drop the `clearing:`
  line). The next reflow re-pairs against real legs and the synthetic side disappears — there
  is nothing to revoke, because the pairing was derived, not decided.

- **A decision may choose the account side.** A `clearing:` pattern is date-blind, so it cannot
  target the pre-coverage legs of an *open* account (whose later history is held). For those, an
  `ATTACH_ACCOUNT` decision (§6.2) names the legs and the clearing account directly; it is a
  conclusion, never a fabricated fact, and `REVOKE` releases the legs back to the matcher. This is
  how a pruned *period* of an account lands on a legacy clearing position.

- **The clearing side is materialised as a derived leg.** So a clearing transfer has two concrete
  legs and per-account queries are complete, derive emits a `synthetic` transaction row in the
  clearing account (reserved `clr|…` id, no evidence, never a decision target), with a running
  balance computed from the declared closing. Every transaction row carries a `synthetic` boolean:
  false for a mirrored fact, true only for a clearing leg. A clearing account holds only synthetic
  rows; a real account holds none.
- Reconciliation reports a clearing account as `CLEARING` (opening computed, closing declared,
  no chain), the way `DECLARED` is never "wrong".
- The egress provisions the Firefly account with the computed opening and posts the transfers,
  so the projection's balance lands on the declared closing; `verify` checks it.

Stated plainly: the computed opening absorbs everything the journal cannot know — a card's
pre-history purchases, a loan's interest split. That is the price of clean flows; a later
statement ingest replaces the approximation with the real chain.

### 6.11 Commitments and expected transactions

The ledger answers "what happened"; a commitment answers "what is coming". A **commitment** is
a named expectation of a recurring money movement — a subscription, a bill, insurance, a fee,
tax, income — with a cadence, a direction and a set of match rules. It is derived state of the
same significance as categorization: curation is journaled, the derivation is pure, the tables
are disposable, and **Expected** is a first-class mode (§10.1).

**The unit and its faces.**

| Face | Values |
|---|---|
| origin | `detected` (a predictable cadence found in the facts) · `declared` (a person said so) |
| direction | `out` (−) · `in` (+ income) |
| cadence | `weekly · fortnightly · monthly · bimonthly · quarterly · semiannual · annual · irregular` |
| amount | `fixed` · `variable` (usage) · `range` (declared floor/ceiling) |
| kind | `subscription · services · bill · insurance · fee · tax · income · interest_earned · interest_paid · loan · other` |
| rules | an ordered set of match rules (§6.2) |
| lifecycle | `candidate · active · dormant · ended`; `lapsed` is an overlay, not a lifecycle |
| id | `commitmentId`, a slug frozen in the declaring decision; never rewritten |

`candidate` is detected and not yet declared or ignored; `active` expects occurrences; `dormant`
is silence against an advancing account frontier; `ended` is a `RETIRE_COMMITMENT` — or, for a
candidate only, the detector's coverage test. An `irregular` commitment has no cadence and is
never dormant. `lapsed` colours the current expected occurrence while older misses accumulate
as **arrears**.

**Rules, not vendors.** There is no vendor entity. A commitment is exactly a name, its faces
and its rules; a rule is `{match: <regex>, account?: <ref>}` evaluated against
`clean(rawDescription)` with the same pattern semantics and lint as `categories.yaml` /
`transfers.yaml` (a bad rule is a `422`, §9.3). A rule that does not compile and still reaches
the log through another writer makes its declaration ineffective and visible
(`INEFFECTIVE_DECISION` naming the rule) rather than failing the derivation; an earlier or later
good declaration of the id stands. Descriptor churn is just another rule
(`ANTHROPIC`, `ANTHROPIC* CLAUDE SUB`, `CLAUDE.AI SUBSCRIPTION` are three rules on one
commitment), and a provider move is a workflow, not a link: `RETIRE_COMMITMENT` ends the old
series and `DECLARE_COMMITMENT` starts the new one; the two histories stand alone.

**Core fields only.** A rule, and the domain it runs over, may use `rawDescription`, `account`,
`amount`/sign and `date` — fact fields — and never a derived one (`leg`, `pairing`, `category`,
`role`, `synthetic`). A later reflow may change a pairing or a category; a commitment must not
move with it. Internal movements are commitments like any other: a matched transfer leg (a
home-loan repayment) is in scope and counts. The one conclusion that removes a fact from the
domain is `noop`; pending observations and synthetic clearing rows are not facts at all.

**Curation is decisions** — the §6.2 actions, append-only, attributed and `REVOKE`-able:

- `DECLARE_COMMITMENT` declares, or confirms a detected candidate (`fromCandidate`). A
  re-declare with the same id replaces the curated fields and the whole rule set, latest
  effective wins; a declare after a retire revives it, family-inverse style. Cadence and anchor
  are not edited in place: a schedule that genuinely changed is retire + declare.
- `RETIRE_COMMITMENT` ends it (cancelled, past, provider move), with the date it ended at.
- `IGNORE_RECURRING` silences a detected candidate for good; newer facts do not reopen it — that
  is what `REVOKE` is for.
- `PIN_COMMITMENT`/`UNPIN_COMMITMENT` place a fact the rules miss on a commitment — the category
  `PIN` gesture, per fact, latest effective wins. A pin naming a commitment that is unknown or
  retired at the time is ineffective and visible; a later retirement does not unwrite a pin that
  predates it.
- `EXCLUDE_COMMITMENT`/`INCLUDE_COMMITMENT` declare a fact not part of a commitment — a one-off
  inside a series (`V2-COMMITMENT-EXCLUSIONS-PLAN.md`) — per (commitment, fact), latest effective
  wins. An excluded pair is never claimed: a pin falls through to the rules and a rule scan skips
  it; the fact itself is untouched, and `INCLUDE_COMMITMENT` (or `REVOKE`) restores it. A decision
  naming an unknown commitment or an unresolvable fact is ineffective and visible.
- `NOTE_COMMITMENT` is a free-annotation thread on a commitment, removed only by `REVOKE`,
  never edited; notes on a retired commitment remain part of its history.
- `SETTLE_OCCURRENCE` states that occurrences were paid (or received) off-journal: a conclusion
  with no fact, attributed and revocable. When the money should appear on an account, the honest
  path is an authored fact (§12.4), matched like any other.

Fact ids a decision names resolve through the supersession map as every decision does;
commitment ids are decision-local and never touch the fact chain. A decision naming an unknown
commitment is ineffective and surfaces as `INEFFECTIVE_DECISION`, never dropped.

**Detection** is pure and deterministic over the current posted facts — every account, matched
transfer legs included. Facts group by the frozen `MerchantStem.stem`; a series needs at least
three occurrences and at least 70% of its gaps within the nearest bucket of
`{7, 14, 30, 61, 91, 182, 365}` days (tolerance `max(2 days, 20%)`). Same-day repeats collapse
into one occurrence, a refund nets against the charge it reverses, and a change of at least 5%
or 50¢ between consecutive occurrences is a price step; residual variation flags a `variable`
amount. Coverage is relative to the accounts' posted frontier, never a clock: active inside one
cadence plus tolerance, ended beyond two periods, otherwise dormant. A candidate is suppressed
when an effective `IGNORE_RECURRING` names its key, a declaration named it as `fromCandidate`,
or a declaration's rules cover every fact of its group.

**Occurrences** are generated from cadence and anchor with `java.time` calendar arithmetic — a
monthly bill on the 30th clamps in February, never "add 30 days" — for the recent past and a
forward horizon, and are as disposable as the rest. A fact that matches one of the commitment's
rules (or is pinned to it) and has the commitment's sign **attaches to the occurrence whose window
contains its date** (below); several facts in one window sum. The window decides status, not
admission — a window that closes unmatched is `missed`, and a later fact in the window catches it
up. Facts are consumed in `(date, n)` order and each belongs to at most one occurrence. Status is
`occurred` (a fact landed in the window: the row carries the fact id and the amount that moved —
a price, never an inferred shortfall), `settled` (a person concluded it without a fact), `due`, or
`missed` (the window closed with no fact — a hole). `partial` is retired from automatic output.
An `irregular` commitment generates no dates: each matching fact becomes an occurrence at its own
date — tracked by observation, never predicted, never missed, never in arrears, never dormant.
A pin never re-anchors; a pinned fact with no window is an `off_schedule` occurrence at its own
date — "charged twice this month" is a true statement, never a silent match.

**Arrears and catch-up are manual.** An occurrence is in arrears while it is `missed` — a window
that closed with no fact, a hole; an `irregular` commitment has no due dates and therefore none.
Nothing is allocated across occurrences and nothing pre-pays: a payment attaches to its own
window, and how a lump maps onto holes is a person's conclusion, recorded by `SETTLE_OCCURRENCE`
(paid off-journal) or by pinning the fact that paid them. The backlog is visible until a person
clears it: nothing is auto-forgiven and nothing is auto-retired.

**Dormancy is a review question.** A tracked, non-retired, regular commitment whose last
satisfied occurrence is more than one cadence plus tolerance behind the frontier of the accounts
it matched on (or its rules name) is dormant. It raises `DORMANT_COMMITMENT`, is ended by a
person (`RETIRE_COMMITMENT`) or kept by a `DISMISS`, and the next matched fact re-opens the
question. A commitment that has never matched is not dormant: it is in arrears.

**Nothing here is a property of a transaction.** Candidates, rules, assignments, occurrences,
prices, arrears and the dormancy question are all derived from `(facts, decisions, config,
asOf)`; the five tables (§7.1) are materialised like every other derived table, and
`trex index --rebuild` reproduces them. `occurred` (evidence) is never conflated with `settled`
(a conclusion); no `externalId` is ever rewritten and no journal line is ever edited.

---

## 7. The derived read model

### 7.1 Two levels

1. **Mirror tables** — the log, row for row: `fact`, `decision`, plus the indexer's
   offset. No derived columns; rebuildable from the log alone.
2. **Derived tables/views** — `supersession`, `txn_current`, `pending`, `transfer`,
   `review_item`, `category_current`, `pin_current`, `commitment`, `commitment_rule`,
   `commitment_occurrence`, `commitment_note`, `projection_state`, `user_ack`,
   `source_cursor`, `evidence`. Rebuildable from level 1 + config + `asOf`.

Splitting the two means the indexer is an ordinary follower: apply new lines and the
offset in one SQLite transaction (the same exactly-once trick v1's sqlite follower
already uses), then refresh derivation. A rebuild truncates level 2 only. The one
non-log input is `asOf` (§9.1): a rebuild at a later instant may change only
time-relative statuses (stale badges, ages), and those never enter a `stateHash` (§9.5).

### 7.2 Schema (as built)

The level-1 mirrors, the derived tables and the fold are **as built**: the normative DDL is
`trex-v2-index/src/main/resources/trex/v2/index/schema.sql`, summarised in `V2-SPEC.md` §7 and
§2. The commitment tables (`commitment`, `commitment_rule`, `commitment_occurrence`,
`commitment_note`, §6.11) are derived like the rest: candidates and declared rows, the effective
rule set, the materialised occurrences and the note thread. The point is not the columns:

> **Every table here can be dropped.** The UI never queries the log, the log is never written
> from here, and `trex index --rebuild` is the recovery.

### 7.3 Materialization strategy

- **On log change:** apply new lines + offset in one transaction; recompute only what
  changed:
  - new facts → insert, recompute duplicates and transfer candidates for them;
  - new decisions → apply, recompute affected items;
  - rules/registry change → full re-derive (at 10⁴–10⁵ rows this is a query, not a
    project; measure before optimising).
- **Incremental only where it stays obviously correct.** A full re-derive at the same
  `asOf` must always produce the same answer; that equivalence is a test (§15).
- **`asOf` is passed in per refresh** and used only for time-relative statuses (stale
  badges, ages); those fields are recomputed on every refresh and never enter a
  `state_hash`.
- **Coalesce** index refreshes (e.g. 200 ms) so a backfill does not re-derive per line.

### 7.4 Who owns and runs the index

**`trex-hub` owns it and runs it.** SQLite is not a service: there is no daemon to start,
no port to open, nothing to monitor. `trex-index` is a library, `trex-hub` embeds it, and
`trex-hub` is the file's only writer.

Why `trex-hub`:

- It already owns the read side — v1's fold lives there. The index is that fold made
  durable, so this is a continuation, not a new responsibility.
- Queries and SSE come from the same process and the same materialisation, so every
  browser and every API client sees one instant of the journal.
- The sequencer stays free of JDBC and of every query concern. The component that must
  never fail gets no new dependency.

Why not the sequencer: it would drag rules, categories and SQL into the writer, and
"the writer does the least" is the property that keeps it auditable.

Why not a separate indexer daemon: it buys one thing — the UI can restart without
pausing indexing — and costs a process, a lifecycle and a second failure mode. Indexing
already resumes from an offset and a rebuild is seconds; a restart is cheap. If the UI
ever genuinely needs restarting without pausing, extracting the daemon is a packaging
change, because the materialiser is already a library.

**The ownership contract:**

1. The file is **private to `trex-hub`**. Its schema is not a public interface and may
   change between versions, because the only supported maintenance is "delete and
   re-derive".
2. **One writer at a time**, inside `trex-hub`. No other process opens it for write.
3. Everything else reads through the **API** (`/api/snapshot`, `/api/ledger`,
   `/api/units`, …) or
   `trex export`. The Firefly egress remains an API client, so it never projects under
   a config revision the UI never showed.
4. **Excluded from backups.** Back up the log, the evidence store and the config
   (git). The index is not worth backing up; it is worth rebuilding.
5. **Rebuilt, never repaired.** Missing, corrupt or schema-mismatched → move aside and
   rebuild from the log.
6. Rule edits are applied **through `trex-hub`**, which already owns the rule files, so
   a rule swap and an index write serialise behind the same lock.

Blessed read-only exceptions: you at a `sqlite3` shell; `trex verify` and
`trex reflow --preview` opening it `mode=ro`; `trex verify` rebuilding into a scratch
file to compare hashes. The one writer exception is `trex index --rebuild`, which takes
the index lock and fails loudly if the hub is live. None of them may run against a file
that is mid-rebuild.

**Inside the process:**

- one writer connection guarded by the index lock; a small read pool (2–4 connections)
  for HTTP handlers — WAL lets readers proceed during a write;
- pragmas: `journal_mode=WAL`, `synchronous=NORMAL`, `busy_timeout=5000`;
- every applied batch writes the log rows **and the journal offset in one SQLite
  transaction**. Power loss therefore loses both or neither, so the index can never
  skip or duplicate a line — the same exactly-once trick v1's SQLite follower already
  uses, with the index's durability relaxed because the log is the truth;
- a rules reload takes the same lock before recomputing the derived tables.

**The pattern has a name: snapshot + delta.** Every reader is a snapshot at a sequence,
plus a stream. The persisted offset is the snapshot point; the log is the delta stream
and `n` is the sequence number; a rule change is a new reference-data snapshot, and the
`409` is the stale-snapshot check a trading system applies to a delta built against an
old instrument file. The race that bites in trading systems — a delta arriving while the
snapshot is being taken — is closed the same way: the change watcher is registered
*before* the first pass (v1 §5.1), so nothing falls between snapshot and subscription.

One deliberate difference from a trading reference-data feed: trex's reference data
(category names, rules, accounts, users) is local config, not an upstream publisher, so
its deltas are file edits reconciled by `configRevision` rather than sequenced events.
Pins are the exception that proves the rule — they are per-row *decisions*, not
reference data — which is exactly why they are events and the category *names* are not.

**Lifecycle:**

| Situation | Behaviour |
|---|---|
| Index missing at start | Full rebuild from the log, then tail. |
| Index corrupt | Detected at open; moved aside; rebuilt. |
| Schema / `deriveVersion` mismatch | Rebuilt. Derived tables always; mirror tables if needed. |
| Journal shrinks (materialize, v1 S5) | Offset > head detected; refold from 0. |
| `trex-hub` down | The journal grows, the index goes stale; on start it catches up from its offset. |
| Restart mid-batch | Transaction + offset atomic ⇒ resume with no gap and no duplicate. |
| Rebuild while `trex-hub` runs | Not supported — the hub holds the index lock. Stop it, delete the file, start it; or run `trex index --rebuild`, which takes the same lock and refuses while the hub is live. Worst case is minutes. |
| Want the data elsewhere | `trex export` writes a separate file; never share the live one. |

One gotcha worth recording: in WAL mode a "read-only" open still needs the `-wal`/`-shm`
files and a writable directory (or the unsafe `immutable=1`). Keeping the index private
to `trex-hub` sidesteps the whole question; every other consumer uses the API or an
export file.

### 7.5 What lives where — follower state, not truth

The mental model worth keeping: **the index is follower state.** It is the state of a
follower of the log at a known offset — it lags by design, catches up, and can be thrown
away. Within it, tables are either *replicas* (the log copied: facts, decisions) or
*reductions* (folded from the log plus config: current state, transfers, review items,
categories, pending, ACKs). The truth is the log's head; the follower's offset says how
far behind it is, and every read is consistent at that offset.

Yes: when you look at the blotter, the *state you see* is SQLite tables — current
transactions, transfers, review items, categories, pending observations, projection
status, and the `user_ack` copies. And yes, the decision table is there too. But each of
those is a materialisation of something that exists first somewhere durable:

| Thing | Canonical home | In the index |
|---|---|---|
| Facts (observations) | log | `fact` mirror |
| Decisions (every human action) | log | `decision` mirror |
| Users | `users.yaml` + git | — |
| Read markers (row read) | log (`USER_ACK`/`USER_UNACK` decisions) | `user_ack` (one row per user per row) |
| Category ref data | `refdata.yaml` + git | — |
| Rules | `categories.yaml` + git | `category_current` |
| Pins | log (`PIN`/`UNPIN` decisions) | `pin_current` |
| Current transaction state | `derive()` | `txn_current` + derived state |
| Transfers | `derive()` + `PAIR`/`UNPAIR` decisions | `transfer` |
| Supersession and retirement | log (`SUPERSEDE`/`RETIRE`/`REVOKE` decisions) | `supersession`, `fact_resolved` |
| Review items | `derive()` + `DISMISS` decisions | `review_item` |
| Pending observations | log (`observation: pending`) | `pending` |
| Firefly projection state | Firefly (recoverable from its own notes/tags) | `projection_state` |
| Index progress | — | index offset |

**The rule that decides the side of the line is not "is it state?" — SQLite may hold any
state — but "would you be sad to lose it?"** Everything in the right column is
recomputable: wipe the file and it comes back, row for row. Everything in the middle
column is not: facts, decisions, `USER_ACK` attestations, rules and evidence are the
only copies, so they live outside. If you ever catch yourself wanting to back up the
index, that is the signal that something in it is really irreplaceable and belongs in
the log or a file.

The consequence to hold onto: **nothing a human did exists only in SQLite.** A decision
is appended by the sequencer before `trex-hub` ever sees it; a `USER_ACK` is a decision
line; a rule edit is a file. The `user_ack` table is a queryable copy of those lines,
with staleness computed against the current `stateHash` — never stored as truth. Delete
the index and every one of those comes back, which is the rebuild test in §4. SQLite
holds state; it does not hold truth.

---

## 8. Identity, evidence, and re-parse

### 8.1 Evidence first

Before parsing, the source bytes (or, for feeds, the provider's row payloads) are
written to `evidence/` keyed by SHA-256. The fact carries `evidenceId` and `parser`
(name/version). Evidence is immutable and content-addressed; storing it is cheap
(compressed statements, kilobytes each; a lifetime is still tens of megabytes).

This buys three things v1 cannot do:

1. **Prove what the bank said.** "The PDF contained this text on this date" is now
   answerable after the file is gone.
2. **Re-parse safely.** A parser upgrade replays the evidence and produces candidate
   facts, which are diffed against the current ones.
3. **Backfill replays.** Ingest the same evidence again through a fixed parser without
   re-downloading anything.

### 8.2 Re-parse workflow

```
trex ingest --reparse evidence:sha256:2f9c… --parser cba-pdf/4
  → candidates (facts with ids; matched rows keep their occ, §6.1)
  → diff against current facts for that evidence:
       matched   (same external_id)             → no-op
       shifted   (same row, new id)             → propose SUPERSEDE(from,to)
       new       (row previously dropped)       → propose new fact
       missing   (row previously read, now not) → propose RETIRE, always review
  → preview; apply writes the new facts and the SUPERSEDE/RETIRE decisions
```

A `SUPERSEDE` decision is how v1's "a restatement mints a new id and the tripwire catches
it" becomes a first-class, reviewable operation; a `RETIRE` covers the opposite case — a
parser that over-reported, corrected without inventing a replacement. Neither deletes
anything: the old id stays in the log, Firefly notes, bookmarks and URLs keep working,
and `txn_current` resolves old → new through the supersession map (§8.3). A wrong
supersede is not permanent either — `REVOKE` undoes it (§6.2).

### 8.3 Identity rules (unchanged, restated for v2)

- Natural key: `nk|accountRef|date(ISO)|receipt`; date in the key because receipts recur.
- Content hash: `ch|accountRef|date(ISO)|amount|rawDescription|occ`; `rawDescription`
  verbatim, untrimmed, exactly as the source published it.
- SHA-256, UTF-8, lowercase, first 16 hex chars. Frozen.
- 64-bit truncation is accepted; state the scale assumption (collision probability at
  10⁶ rows is ~2.7×10⁻⁸) so the trade is explicit.
- Cross-source agreement is a feature to protect: when two sources publish the same row
  text, they must mint the same id. The cba-pdf/csv test is the template for every
  future source.
- Supersession never rewrites an id: it records a chain (`from → to`, or `from` alone
  when retired). `fact_resolved` resolves any id in a chain to the current fact, and
  every decision naming an id is applied through that map, so a re-parse cannot silently
  orphan a `PAIR`, `PIN` or `DISMISS` (§9.8). A `SUPERSEDE` that would close a cycle, or
  point at a retired fact, is ineffective and surfaced for review.

### 8.4 Sources that cannot agree

If a re-parse or a second source produces a *different* reading of the same economic
event, the system must not silently pick one:

- same `externalId` → idempotent no-op;
- different `externalId`, matching `(account, date, amount)`, **different merchant stems** and
  similar text → a `RESTATEMENT` review item with both sides shown, resolved by `SUPERSEDE`,
  `RETIRE` or `DISMISS`. The two kinds are disjoint: a same-stem pair is the duplicate case
  (`POTENTIAL_DUP`), never a restatement — a restatement is a *different reading* of the amount,
  which is what a re-parse or a second source produces. "Similar" is deterministic, never a
  scoring library: compare merchant stems (the first alphabetic token of ≥ 3 characters after
  stripping payment-network noise such as `VISA`, `EFTPOS`, `POS`, `AUTHORISATION`), case-folded,
  as token sets, with the overlap threshold from `transfers.yaml`; the comparison is part of
  `deriveVersion`;
- otherwise → two transactions, which the reconciliation chain will judge.

---

## 9. Reflow — the crown

### 9.1 Definition

```
derive : (facts, decisions, config, asOf)
       → { current[], supersession[], transfers[], review[], categories[],
           pending[], projections[] }
```

Pure, deterministic, total. Same inputs, same output — including `asOf`, the derivation
instant, which is an explicit input because pending staleness and age-related statuses
are part of the model. `asOf` is the only non-log, non-config input, and everything it
touches is excluded from `stateHash` (§9.5), so a later rebuild can move a stale badge
but never a verdict. No I/O, no network, no ambient clock. `derive` is the only place
that knows what a transfer, a duplicate, a review item or a category is.

### 9.2 Triggers

| Trigger | What changes | Touches the log? |
|---|---|---|
| Category rule edit (`categories.yaml`) | categories | No |
| `PIN` / `UNPIN` decision | categories | Yes — the decision |
| `transfers.yaml` edit | automatic pairings, HELD/EXTERNAL, review items | No |
| Registry edit (`accounts.yaml`: `balanceSource`, currency, settlement window) | type semantics, reconciliation scope, stale pending | No |
| Parser upgrade / re-parse | facts (new + supersede/retire) | Yes — new facts and `SUPERSEDE`/`RETIRE` decisions |
| New human decision | state | Yes — the decision |
| Clock advances (`asOf`) | stale/age statuses only — never a fact, never a hash | No |
| Code change in `derive` | anything | No (log untouched; see §9.5) |

Every "No" row is applied by the watcher the moment the file lands — rule edits,
ref-data changes, registry changes and all. (`asOf` is not a file: it advances on every
refresh and moves nothing but statuses.) None of them has a separate apply step;
pins and every other decision arrive through the log instead.

### 9.3 Preview, and why there is nothing to apply

A rule change has exactly one gate: the file write. Save it — through the API, which
previews first, or by hand, which is immediate — and the watcher picks it up, `derive()`
re-runs over the whole journal, and the categories move. There is no separate apply step
inside trex, because a category is not stored anywhere that would need one.

`trex reflow --preview` is the sandbox: the same `derive()` run against a candidate rule
set, showing what would move before anything is saved.

```
trex reflow --preview
  config revision: 7c1a… → 9f03…
  transfers:  12 new matches, 3 unmatched, 1 ambiguous
  review:     4 opened, 2 cleared, 1 changed kind
  categories: 318 rows moved
    UNCATEGORIZED → GROCERIES       261
    UNCATEGORIZED → FOOD             41
    DISCRETIONARY → SPORT_AND_LEISURE 16
  read rows invalidated: 3 (9e546cc0…, c3d41f7a…, 7f2b…)
  egress impact: 44 Firefly groups to retag, 12 to create
```

What leaves trex after a save is only the projection: Firefly converges through
`trex egress firefly --plan/--apply`, which is the egress's gate, not categorisation's.
The journal is untouched either way.

**Rule changes are retrospective for the undecided, inert for the decided.** `derive()`
re-runs over the whole journal, so a rule edit recategorises old rows, re-matches old
legs and moves old projections — that is the point of reflow, and v1's "rules are never
re-evaluated on replay" is precisely what it replaces. What a rule change *cannot* touch
is a decision: `PAIR`, `UNPAIR`, `MARK_EXTERNAL`, `SETTLE`, `DISMISS`, `SUPERSEDE`,
`RETIRE`, `REVOKE`, `PIN` and `UNPIN` are inputs to `derive()`, not outputs of it, so no
rule edit can silently undo one. The actionable
rule: **if you want something to survive a rule change, make it a decision; everything
else is rule output and will move when the rules move.** And nothing moves silently — a
moved row invalidates its `USER_ACK`, so history never changes under a stale read mark.

**Pins are decisions, not rules.** A `PIN` names one or more `externalId`s and a
category; an `UNPIN` releases them. They are events like `PAIR`, which means attribution
is native (`user`, `at`, `n`), a correction is visible in the same timeline as everything
else, and the latest event mentioning an id wins. Precedence is fixed and lives in
`derive()`: structural `TRANSFER` → pin → first matching rule → `UNCATEGORIZED`.

A pin is what you reach for when the rules cannot be right about one row — and it is
*immune to rule churn by construction*, exactly as `PAIR` is immune to a `transfers.yaml`
edit. The three homes are now clean:

- **Names** — `refdata.yaml`, declared and frozen once used; additive only.
- **Rules** — `categories.yaml`, hand-written, ordered, git-audited, retroactive.
- **Pins** — `PIN`/`UNPIN` decisions, attributed, revocable, per row.

Two revisions, on purpose:

- **`rulesRevision`** hashes `refdata.yaml` + `categories.yaml` — the rule editor's
  optimistic lock. A rule write composed against a stale revision is a `409`.
- **`configRevision`** hashes every file that feeds `derive()`: `refdata.yaml`,
  `categories.yaml`, `transfers.yaml`, `accounts.yaml`. It is the provenance stamped on
  projections, exports and `USER_ACK` lines, because any of the four can move derived
  state.

Pin events move neither — they are log events with an `n`.

Prechecks at the hub, before the sequencer sees anything: the ids exist, the category is
declared and not retired, `TRANSFER` and `UNCATEGORIZED` are refused, and pinning a
structural transfer leg is refused (a pin that can never win is a lie). The sequencer
validates the same references on the log's terms. A pin made redundant by a later rule is
not an error — the workbook offers an `UNPIN`; a pin whose id no longer exists is an
orphan, and a lint item.

### 9.4 Per-user read markers and invalidation

`USER_ACK` records `(user, externalId, stateHash, configRevision, deriveVersion,
hashVersion)` — one row per person per transaction. Reading is a deliberate, per-row
ceremony: a person opens a row, looks at it, and acknowledges **that one row**. There is
no bulk "ack the period" action, because the small friction is the point — it is what
makes the habit an actual look rather than a gesture. `USER_UNACK` releases a row's
marker; the latest effective decision naming `(user, externalId)` wins. The marker names
the hash machinery it used, so an old marker is always interpretable. Eyeballing is
personal: each user reads the same ledger, and their markers are their own attestations.
After every reflow, each user's stored hash is compared with the recomputed hash of that
row — comparing only when `hashVersion` matches:

- unchanged → that row stays read **for that user**;
- changed → that row is marked **changed since read** for that user, and re-enters their
  unread queue while every other row keeps its marker.

There is no period state and nothing to clear. A period — a day, week, month, quarter or
year — is only the grain the Eyeball buckets and filters rows by; the read state is
entirely per row. `cadence` in `users.yaml` only suggests the grain someone tends to look
at (§6.6) and phrases the nudge ("N rows still unread"); it never gates anything, and
there is no joint status to chase. Users fall out of step naturally — one reads daily,
one weekly, one yearly — and that is the point, not a problem. That is what makes an
irregular habit compatible with a reflowable history: you never silently review a state
that no longer exists, and you never have to redo a row that did not move.

### 9.5 Determinism and the one caveat

Reflow means the same log can produce different derived state at different times — by
design. A code change in `derive` is a silent reflow for every installation. Three
consequences worth stating:

1. **Anything that leaves trex must carry the revision it was derived under.**
   Firefly projections record `configRevision`, `stateHash` and `deriveVersion`; exports
   embed them. That is how "as of March" stays answerable: log + decisions + the config
   files at that commit + the `derive` code at that version.
2. **`stateHash` is content, not provenance.** It hashes a canonical, ordered
   serialisation of the derived content it protects: for a read marker, one row — its
   resolved id, account, date, amount, category and origin, pairing state, pending/settled
   state, retirement; for a projection, the unit's fields (§9.9.G); for a review item, its
   detail payload. It never includes ages, display formatting, `n` ordering noise, or any
   revision below. So a version bump re-evaluates an acknowledged row only when something
   actually moved — "you never redo a row that did not move" holds across upgrades too.
3. **The machinery is versioned.** `hashVersion` (e.g. `statehash/1`) stamps the hash
   function; `deriveVersion` stamps the derivation; `configRevision` stamps the inputs.
   All three are recorded on projections and ACK lines. If `hashVersion` differs from the
   stored one, old hashes are not comparable and the period is treated as stale — the one
   case where a re-look is forced without the content having changed. A `deriveVersion`
   or `configRevision` difference alone forces nothing.

### 9.6 Open items cannot be lost

Because everything open is derived, deletion is not loss:

- **HELD** — a transfer-shaped posted fact with no contra leg. It is recomputed from
  facts + rules + decisions on every rebuild; there is no queue to lose, no aging and no
  watermark (v1's rule stays). If it was HELD yesterday, it is HELD after a rebuild
  today, unless a rule or a decision changed it — and then the reflow diff says so.
- **REVIEW** — ambiguous matches, potential duplicates, restatements and stale pending
  are all `review_item` rows from `derive()` plus `DISMISS` decisions. Same guarantee.
- **PENDING** — a provisional fact (`observation: pending`) in the log. It is not a
  queue entry that can be missed: it is an observation trex recorded, excluded from
  current totals, reconciliation and projection, and settled or expired by derivation
  (§12.3). Settlement is derived when exactly one posted row is plausible, or decided
  with `SETTLE` (§6.2).
- **Nothing ages silently.** HELD/REVIEW never expire (v1's no-aging rule); a pending
  observation becomes `STALE_PENDING` when the account's posted-fact frontier — the
  newest posted date for that account — has passed its date by more than the account's
  `settlementWindowDays` (default 7, `accounts.yaml`), meaning a statement covering it
  arrived without it settling. That rule is config, versioned by `configRevision`, needs
  no clock beyond the explicit `asOf`, and survives a rebuild — and it is a report, not
  a deletion.

The status strip (§14) makes the counts visible at all times: open review by kind,
unmatched transfer-shaped rows, open and stale pending. If a count is not zero, the
reason is on screen and resolvable — and if the index is wiped, the same counts return.

### 9.7 HELD, PENDING and REVIEW are three different kinds of waiting

They answer different questions, live in different places, and resolve differently. The
short version: **PENDING waits on the bank, HELD waits on the contra leg, REVIEW waits on
you.**

| | HELD | PENDING | REVIEW |
|---|---|---|---|
| Waits on | the other leg of a transfer | the bank to settle | you |
| Nature | derived pairing state (`txn_current`) | a property of the observation (log) | derived review item |
| Produced by | transfer-shaped fact with no match | source published an authorisation | ambiguity, duplicate, restatement, settlement |
| Resolved by | automatic match, `PAIR`, `MARK_EXTERNAL` | a unique posted row (`settled_by`) or a `SETTLE` decision | `PAIR` / `MARK_EXTERNAL` / `DISMISS` / `SUPERSEDE` / `RETIRE` / `REVOKE` |
| Ages | never (v1's rule) | yes, into `STALE_PENDING` | never |
| Counts in ledger totals | yes — the money moved | no — it has not | yes — the transaction is posted |
| Firefly | withheld until resolved | excluded | withheld until resolved |

They compose in one order, the order of reality: settlement *(pending → settled)*, then
pairing *(new → HELD → MATCHED \| EXTERNAL)*. A pending authorisation is not yet
matchable — its text and amount can change on settlement — so it never enters the
matching pool; when the posted row arrives, it is matched like any other.

### 9.8 Precedence and effectiveness, precisely

`derive()` first computes the **effective decision set**: decisions in `n` order, less
any decision revoked by a later `REVOKE` (a `REVOKE` of a `REVOKE` restores the
original). Every id a decision names is then resolved through the supersession map
(§8.3); a decision that no longer resolves is **ineffective** and becomes an
`INEFFECTIVE_DECISION` review item — recorded, visible, revocable, never dropped.

Then, per question:

- **Pairing.** Pairing decisions are resolved per leg, latest effective wins.
  `MARK_EXTERNAL` takes a leg out of the transfer pool; `UNPAIR(A,B)` marks both its
  legs as not a transfer and suppresses that pair for the matcher; `PAIR` holds the
  pair. A `PAIR` holds only while it is the latest decision for **both** legs — a later
  decision about either leg overturns it and frees that leg back to the pool. With no
  effective decision, the matcher's answer stands.
- **Settlement.** A pending observation is settled by derivation when exactly one posted
  row is a plausible settlement, or by `SETTLE` when a person says which one; ambiguity
  is an `AMBIGUOUS_SETTLEMENT` item, and `SETTLE` beats derivation (§12.3).
- **Category.** `PIN`/`UNPIN`, latest effective naming an id wins; then the first
  matching rule; then `UNCATEGORIZED`. A structural `TRANSFER` leg is never categorised.
- **Commitments.** `DECLARE_COMMITMENT`/`RETIRE_COMMITMENT`, latest effective per id wins, and
  a declare after a retire revives it (family inverse); `IGNORE_RECURRING` silences a candidate
  key until `REVOKE`d. `PIN_COMMITMENT`/`UNPIN_COMMITMENT` assign per fact, latest effective
  wins, ids resolved through the supersession map; commitment ids are decision-local and never
  resolve. Notes accumulate; `SETTLE_OCCURRENCE` is latest per `(commitmentId, dueDate)`. A pin
  naming a commitment that is unknown or retired at the time, and any retire/note/settle naming
  an unknown commitment, are ineffective and surfaced; a later retirement does not unwrite an
  earlier pin, and a settle on a retired commitment is allowed — it is a conclusion about the
  past.
- **Review.** `DISMISS` silences an item while no newer fact lands for its subject — an id in
  the chain, an account, a candidate's series or a commitment (§9.9.F); `REVOKE` of a `DISMISS`
  re-opens it. `INEFFECTIVE_DECISION` is cleared by revoking or replacing the offending
  decision, not by `DISMISS`.
- **Supersession.** `SUPERSEDE`/`RETIRE`, latest effective per `from` id wins; a chain
  that would close a cycle, or a `toId` that is itself retired, is ineffective and
  surfaced.
- **Read marker.** A `USER_ACK` stays effective until a later `USER_UNACK` or `USER_ACK`
  for the same `(user, externalId)` replaces it; like every decision it resolves its id
  through the supersession map, so a `SUPERSEDE` carries the marker forward. A reflow
  moves the row's hash but never the marker's effectiveness.

### 9.9 The derivation, specified

§9.1 defines the function, §9.8 fixes precedence and §15 fixes the guarantees; this is
the text an implementer can build without a follow-up question. `derive()` is a pure
function of `(facts, decisions, config, asOf)`, and its **only** output is the
materialisation in §7.2 — no table is written that cannot be reproduced by running this
again.

#### A. The pipeline

Twelve stages, run in order; each may read only earlier stages. P1–P4 are log-only and
are the whole of `trex verify`'s rebuild.

| # | Stage | Produces |
|---|---|---|
| P1 | **Replay.** Apply every line in `n` order to the mirror (§6.3): `fact` by `n`, `decision` by `n`. No interpretation. | `fact`, `decision` |
| P2 | **Chain roots.** The supersession map's *roots* — every id that is not the `to` of a `SUPERSEDE` — computed before the map is final so a decision can resolve an id early. | `chain_root[]` |
| P3 | **Registry.** Load `accounts.yaml` (`currency`, `balanceSource`, `settlementWindowDays`), `users.yaml`, `refdata.yaml`, `categories.yaml`, `transfers.yaml`; compute `configRevision`. A load failure is a startup error, not a derivation result. | `registry`, `config` |
| P4 | **Effective decisions.** §9.8: apply `REVOKE`s in `n` order, then resolve every id through the supersession map. | `effective[]`, `ineffective[]` |
| P5 | **Supersession.** Build `supersession` from the effective `SUPERSEDE`/`RETIRE` set; reject cycles and retired targets into `ineffective[]`. | `supersession`, `fact_resolved` |
| P6 | **Current fact per chain.** For each chain root, the latest `posted` observation by `n`; chains whose root is retired or absent are excluded. | `txn_current` |
| P7 | **Transfer shape and pairing.** §9.9.C. | leg states, `transfer` |
| P8 | **Pending settlement and staleness.** §9.9.D. | `pending`, `AMBIGUOUS_SETTLEMENT` |
| P9 | **Category.** §9.9.E. | `category_current`, `pin_current` |
| P10 | **Commitments and occurrences.** §6.11: fold the commitment curation, detect candidates and match declared commitments over the current facts. A sibling of category (§9.9.E): it reads neither its output nor writes into it, so their order is incidental. | `commitment`, `commitment_rule`, `commitment_occurrence`, `commitment_note` |
| P11 | **Review items.** §9.9.F — derived causes, minus effective `DISMISS`es. | `review_item` |
| P12 | **Projection units and state hashes.** §9.9.G. | projectable units, `stateHash` inputs |

#### B. Ordering, and the one ambiguity

Processing is in `n` order throughout; two structures are order-sensitive and are
resolved by construction, not by hop order:

- **Supersession chains.** A chain is walked in `(n, from_id)` order, always toward the
  current fact. `fact_resolved(from) = to` where `to` is the last non-retired id reachable
  from `from`; a walk that revisits a node, or lands on a retired id, marks every decision
  in the cycle ineffective.
- **Pairing.** The matcher (§9.9.C) consumes legs in `(date, n)` order and emits at most
  one contra per leg. Existing automatic matches are not re-derived on a later run when
  nothing moved: the match is a pure function of the leg pool, and the pool is
  deterministic in `n` order.

#### C. Transfer shape and pairing

Evaluation order, first match wins:

1. **Effective decisions** for a leg — the latest effective `PAIR`, `UNPAIR` or
   `MARK_EXTERNAL` naming it, resolved through supersession. `MARK_EXTERNAL` and
   `UNPAIR` take the leg out of the pool; `PAIR` holds the pair only while it is the
   latest effective decision for **both** legs.
2. **Shape (the pre-filter).** With no effective decision, a leg is *transfer-shaped*
   when `clean(rawDescription)` matches a pattern declared **for its own account** in
   `transfers.yaml` (case-insensitive), or it shares a non-null `receipt` with a leg in
   another account that is a **plausible counterpart** — opposite sign, equal `|amount|`,
   the same `currency`, and dates within `windowDays`. Receipt numbers are not globally
   unique (the same ING receipt appears on rows years and accounts apart), and a collision
   must not shape two unrelated rows. Patterns are account-scoped: each institution writes
   its own vocabulary (`Osko`, `Fast Transfer`, `Transfer to xx\d+`, `Bill Payment
   Received`), and a leg is judged only by the lists of the account it sits in. Patterns
   are ordered and the **first match wins** — the account's own entries first, then
   `default` — and the match decides both shape and rail. That ordering separates "Osko to
   self" (a transfer) from "Osko to anyone else" (an expense), and the same holds for
   BPAY: paying our own BankWest card is a transfer, a BPAY payment to any other biller is
   an expense. A shaping pattern always names a self counterpart — own name, own account
   number, own card; a bare rail (`Osko Payment`, `BPAY`, `PayID`) is rail-only. A pattern
   may also name a `clearing:` account (§6.10): a matching leg pairs directly with that
   account — no contra fact, no window, no ambiguity — because the counterparty is
   declared rather than looked up in the pool. Sign and account do not determine shape —
   they qualify a candidate. Only shaped legs enter the pool; the
   pre-filter is what keeps ordinary rows out of it, whatever the amount/date coincidence.
3. **Pool and ladder.** The matcher runs `(date, n)` order over the pool of shaped,
   undecided legs and takes the first tier that fires:
   - **T1 — receipt.** Both legs share the same non-null `receipt`, are in different
     accounts, have opposite signs, equal `|amount|`, the same `currency` and dates
     within `windowDays` → `confidence: EXACT`, `transfer_id` = `TRF-<receipt>`. A
     receipt with no such counterpart pairs nothing and shapes nothing.
   - **T2 — same-day unique.** `|amount|` equal, opposite signs, different accounts, same
     `currency`, same `date`, and the candidate is unique *and* has no other same-day
     suitor → `confidence: HIGH`, `transfer_id` = `transferId(rootA, rootB)`.
   - **T3 — windowed unique.** As T2 but the dates differ by no more than
     `transfers.yaml windowDays`, with exactly one candidate in the window → `confidence:
     HIGH`, `transfer_id` = `transferId(rootA, rootB)`. The window widens the date; the
     matcher never compares text across accounts.
   - **No match.** A transfer-shaped leg with no candidate → `HELD`. A leg that is not
     transfer-shaped and is not matched → `EXTERNAL`.
   - **More than one candidate** at the winning tier → the leg is `HELD` **and** an
     `AMBIGUOUS_TRANSFER` item opens naming every candidate (same-day ones highlighted).
     HELD is what it is — on hold waiting for a contra — and the item is the separate
     statement that there is more than one; no pair is emitted either way. Resolved by
     `PAIR` or `MARK_EXTERNAL`, or the candidates resolve themselves as they are decided.
4. **Attribution (rails).** Every pattern declares a rail **method** — `OSKO`, `PAYID`,
   `BPAY` or `BANK_TRANSFER`. Method and **direction** are shown together and stored
   apart: direction is `IN` for a credit and `OUT` for a debit, and it is never declared —
   it is the sign, so a rail can never contradict the amount. A rail is derived per leg,
   `EXTERNAL` legs included, so the Blotter can read a payment received as `PAYID · IN`
   or sent as `OSKO · OUT` even when no contra exists; a pattern may be **rail-only**
   (`shape: false`) to tag rows without placing them in the pool. A matched pair records
   the **payer leg's method**, falling back to the payee leg's method and then
   `BANK_TRANSFER`; a pair's direction is structural — from the negative leg to the
   positive one — so nothing extra is stored on the transfer.
5. **Collapse.** A pair emits one `transfer` row; its legs are `MATCHED` and are never
   projected (§11). A pair emitted from a decision carries `origin: decision` and the
   decision's `n`; a pair from the matcher carries `origin: derived`.

   The config is the vocabulary plus the ladder, and nothing else:

   ```yaml
   # transfers.yaml
   windowDays: 4          # T3
   holdWindowDays: 30     # UNMATCHED_LEG
   transferPatterns:
     # Bare rails: tags only, never potted. A pattern that names a self
     # counterpart (own name, own account, own card) shapes instead.
     default:
       - { match: 'Osko Payment', rail: OSKO,  shape: false }
       - { match: 'BPAY',         rail: BPAY,  shape: false }
       - { match: 'PayID',        rail: PAYID, shape: false }
     ing-orange:                     # the other ING accounts repeat this self block
       - { match: 'Internal Transfer', rail: BANK_TRANSFER }
       - { match: 'To my account',     rail: BANK_TRANSFER }
       - { match: 'From my account',   rail: BANK_TRANSFER }
       - { match: '^Rohan Machado - Osko Payment to', rail: OSKO }
       # Paying our own BankWest card is a transfer; other BPAY billers are expenses.
       - { match: 'Bankwest Auto Pay',          rail: BPAY }
       - { match: 'BANKWEST CREDIT CARD.*BPAY', rail: BPAY }
     cba-smartaccess:
       - { match: 'Fast Transfer',       rail: BANK_TRANSFER }
       - { match: 'Transfer from xx\d+', rail: BANK_TRANSFER }
     cba-netsaver:
       - { match: 'Transfer to xx\d+', rail: BANK_TRANSFER }
     bw-credit-card:
       - { match: 'Bill Payment Received', rail: BPAY }
   ```

   An account's effective list is its own entries followed by `default`, first match wins;
   an account with no entries still shapes by the default rails and by receipts. Saving a
   change runs the same preview contract as a rule edit: it shows the legs the change
   would pot and the pairs it would make, before anything moves.

   > **Parity with v1.** v1 paired on amount, sign, account, currency and date alone, and
   > that is what the pool does — with two differences that make it safe rather than
   > lucky: the pre-filter keeps ordinary rows out of the pool entirely, and a tie goes to
   > `AMBIGUOUS_TRANSFER` for a human, never to an arbitrary pick. The stem tier (`T2/T3`
   > requiring equal `transferStem`) is retired; `MerchantStem.transferStem` leaves the
   > matching path. Receipts get the same discipline: equal amount and the date window are
   > part of the tier, because receipt numbers collide across accounts and years (measured
   > in the loaded history: a 1,498-day payroll/card pair). Every genuine receipt pair
   > there is same-day and equal-amount, so the guard costs nothing. Equivalences and
   > divergences with v1 stay recorded in `docs/V2-PARITY.md`.

#### D. Pending settlement and staleness

A `pending` fact is a **claim on a future posted row**, and settlement is resolved before
pairing (a pending row never enters the pool; §9.7).

- **Candidate.** A posted row on the same account whose `date` is within
  `[pending.date, pending.date + settlementWindowDays]`, whose sign is opposite, whose
  `|amount|` is equal (or within `transfers.yaml amountTolerance`, default 0), whose
  `currency` matches, and whose `merchantStem` is equal to the pending row's.
- **Exactly one candidate** → `settled_by` is that row; the pending observation becomes
  `SETTLED`.
- **Zero candidates** and `asOf < pending.date + settlementWindowDays`, where the
  account's posted frontier has not passed the pending date → `OPEN`.
- **Zero candidates** and the frontier has passed it (`> settlementWindowDays` since the
  account's newest posted date) → `STALE`, and a `STALE_PENDING` item opens.
- **Two or more candidates** → `AMBIGUOUS_SETTLEMENT`, every candidate named; the row
  stays `OPEN` and a `SETTLE` can close it. Derivation never picks between them.

The frontier is per account: the maximum `date` of that account's posted facts. It is a
property of the log, so this is reproducible and needs no clock beyond `asOf`.

#### E. Category

For a current fact `f`, with `leg` = its pairing state from P6:

1. `leg` is `MATCHED` (structural transfer) → `TRANSFER`, `origin: STRUCTURAL`; never a
   pin, never a rule.
2. The latest effective `PIN`/`UNPIN` naming the id wins: a `PIN` → its `category`,
   `origin: PIN`, `rule_id` null; an `UNPIN` falls through.
3. First rule in `categories.yaml` file order whose `when` tree matches
   `clean(rawDescription)`, amount direction, account and `leg` → `origin: RULE`,
   `rule_id` recorded; a firing rule with an undeclared or retired category name is a
   load error, never a silent `UNCATEGORIZED`.
4. Otherwise `UNCATEGORIZED`, `origin: NONE`.

A `PIN` naming a structural transfer leg is refused at the hub (a pin that can never win
is a lie, §9.3) and, if it reaches the log, is ineffective.

#### F. Review items

Each kind is a predicate over the current state; an item is open when its predicate holds
and no effective `DISMISS` for that `(kind, subject)` post-dates the newest fact for the
subject. A subject is usually an id in the chain (so a re-parse re-opens, §9.8); a
`BALANCE_BREAK` subject is an account and ages on the account's newest fact; a
`SUSPECTED_RECURRING` subject is a grouping stem and ages on the facts of that series; a
`DORMANT_COMMITMENT`/`COMMITMENT_ARREARS` subject is a commitment id and ages on the facts the
commitment matched.

| Kind | Predicate |
|---|---|
| `POTENTIAL_DUP` | Two current facts on one account, same `date`, same `sign`, matching `merchantStem`, absolute amount within `transfers.yaml dupTolerance` (default 0), neither retired nor paired. |
| `RESTATEMENT` | A current fact whose `(account, date, amount)` matches another current fact's, with the text similarity threshold (§8.4) satisfied and different ids. |
| `AMBIGUOUS_TRANSFER` | From P7: more than one candidate at the winning tier. |
| `AMBIGUOUS_SETTLEMENT` | From P8: more than one settlement candidate. |
| `UNMATCHED_LEG` | A shaped leg that has been `HELD` past `transfers.yaml holdWindowDays` (default 30) measured on `asOf`. HELD itself never ages; only the *item* does. |
| `STALE_PENDING` | From P8. |
| `INEFFECTIVE_DECISION` | From P4/P5: a decision naming an unresolvable id, or a supersession cycle. |
| `SUSPECTED_RECURRING` | From P10: a detected candidate that is neither suppressed nor ended (§6.11); subject the grouping stem, detail the cadence, span, occurrence count and amounts. |
| `DORMANT_COMMITMENT` | From P10: a tracked, non-retired, regular commitment whose last satisfied occurrence is more than one cadence plus tolerance behind the frontier of the accounts it matched on (or its rules name). An `irregular` commitment is never dormant; an ended one is never dormant either and keeps only its arrears. |
| `COMMITMENT_ARREARS` | From P10: a commitment with a backlog — `missed` or `partial` occurrences (§6.11); subject the commitment id, detail the count and the expected total still short. |

An item's `state_hash` is the hash of its `detail` payload, so the item survives a
rebuild identically and the "changed since reviewed" check has something stable to
compare (§9.5).

#### G. Projection units and state hashes

- **Unit.** A `transfer` row is one unit; a current posted fact whose pairing state is
  `EXTERNAL` is one unit; an `ATTESTATION` is never a unit (§11). The unit id, kind and
  category come from P7/P9.
- **`stateHash(row)`.** Canonical, ordered serialisation of one current transaction:
  `externalId`, `unit_kind`, `accountRef`, `date`, `amount`, `currency`, `category`,
  `origin`, pairing state, pending state, `retired`, `ineffective`. Excluded: any age or
  stale badge, any display field, `n` ordering noise, `configRevision`, `deriveVersion`,
  `hashVersion`. The hash is of content only (`hashVersion` stamps the algorithm; §9.5).
  The projection unit keeps its own content hash (§11); the marker hashes the row, not the
  projection.
- **Recomputing a row** is exactly `derive()` restricted to that row, which is why
  `USER_ACK` invalidation is a comparison and not a second derivation.

#### H. Complexity and the incremental contract

A full derive is `O((f + d) log(f + d))` for P1–P4 (a `sort` plus linear folds) and
`O(k log k)` within an account for P6–P7, where `k` is the size of the matching pool. An incremental refresh may reuse any
stage's output whenever its inputs are unchanged; the only supported contract is that
**a full re-derive at the same `asOf` produces the same tables**, and §15 asserts it.
There is no partial-consistency promise: a rebuild replaces level 2 wholesale.

---

## 10. The blotter

One page, four modes, dense table first. Not a dashboard (Firefly owns the
pretty reports); the blotter is for deciding things.

### 10.1 Modes

| Mode | Question it answers | Contents |
|---|---|---|
| **Blotter** (default) | "What happened, and is it true?" | Every current transaction, filters, inline category, transfer pairing, projection status |
| **Review** | "What needs a decision?" | Derived review items, ranked, batch actions — duplicates, restatements, ambiguous matches, stale pending |
| **Expected** | "What is committed, and what is behind?" | The window's occurrences with green ticks / red crosses, committed totals by direction, the catch-up backlog, the commitment registry and its lint |
| **Eyeball** | "Have I read what I meant to?" | The period's rows with read/unread, anomalies, balance ribbon, per-user `USER_ACK` |
| **Rules** | "Why is this categorised like that?" | Rule editor, blast radius, lint, fixtures |
| **Accounts** | "Which periods are imported, and where are the holes?" | Per-account earliest/latest, last ingest, and a facts-derived weekly coverage strip |

Everything else is a filter on one of these, and every mode cross-links to the others.

### 10.2 Blotter specifics

- **Columns:** date, account (and to-account), amount, balance, description, category
  (with rule provenance on hover), state and review badges, transfer link, Firefly state,
  `n`, external id. Default set compact; the rest toggleable and remembered.
- **Filters** (SQL-backed, so cheap to grow): account, state, category (including
  `UNCATEGORIZED`), type, date range, amount range, text `q`, `has:review`,
  `has:drift`, `source:`, `tag:`.
- **Sort/paging** stable and server-side; save filter+sort as a named view.
- **Inline actions:** pin a category (a `PIN` decision), pair two rows (decision),
  mark external (decision), annotate (a `NOTE`, one row or a selection), open the detail drawer.
- **Balance ribbon:** per account, the running balance chain drawn from facts, gaps
  highlighted exactly where the tripwire would fail. This is the fastest way to eyeball
  "does this account look right".
- **Projection status per row:** planned / posted / drifted / withheld, so Firefly is
  never a mystery.
- **Pending lane:** provisional observations (`observation: pending`), greyed, with age
  and amount — excluded from footer totals and the balance ribbon, linked to the posted
  row that settles them, and carrying a `STALE_PENDING` badge once they should have
  settled. Money in flight is visible, not counted.

### 10.3 Eyeball mode (the routine, at your cadence)

The Blotter, filtered to one period and sorted for reading, at whatever cadence you keep.
Unread rows are emphasised and read rows fade, so the remaining work is the bright part of
the page. The weekly habit finishes in minutes; the yearly backlog is a long list of bright
rows, and nobody else waits on it.

1. **Open items** — the shared review queue filtered to the period. Any user may
   resolve any item, and the row shows who did.
2. **Anomalies** — concrete SQL checks, each linking to the row. Every check is a query
   with an explicit `asOf`; nothing time-relative is stored (§9.1):
   - balance chain break (statement accounts);
   - new merchant stem never seen before;
   - amount above the 99th percentile for its merchant;
   - duplicate-looking pair (same stem and amount within N days);
   - expected recurring charge missing this cycle;
   - transfer-shaped row unmatched past the window;
   - a pending observation whose statement period has arrived without settling;
   - `UNCATEGORIZED` rows;
   - account silent longer than its usual cadence (feeds).
3. **The rows** — the transactions, grouped by day, with totals and closing balance;
   keyboard to next/previous day; `pin`, **read/unread**, and annotate (a `NOTE`) inline.
4. **Read** — each row carries an `Ack` (or, once read, an `Unack`) for the current user.
   Acking writes one `USER_ACK(user, externalId, …)` for that row (the full tuple in §6.2)
   and fades it; `Unack` writes the `USER_UNACK`. A later reflow that moves the row flags
   it — for that user alone — as *changed since read*. Other users' markers are untouched.
   The period is only the window you are looking at; there is nothing to close.

The routine is then: open Eyeball, fix what is red, read the rows. The system remembers —
per person, per row, at their own pace.

### 10.4 Rules mode

Keep v1's best feature — proposal with blast radius, computed placement, validate-before
-swap, no clipboard. Add the feedback loop:

- **Lint:** shadowed rules, rules that never fire, orphan pins (id gone), pins a rule now
  covers (offer `UNPIN`), pins accumulating (a repeated pin is a rule asking to be
  written), regexes with catastrophic backtracking risk.
- **Fixtures:** `categories.tests.yaml` — `description → expected category`, run on load
  and in CI. A golden set for the ruleset, exactly as statements have golden files.
- **Suggestions:** the uncategorised worklist grouped by merchant stem, with a proposed
  regex and its test against history; pins clustered into a proposed rule.
- **Coverage:** share of rows and amount by origin (`RULE`/`PIN`/`NONE`), and trend over
  time — is the ruleset converging or accumulating pins?

### 10.5 Accounts mode

The import ledger: every account, its earliest and latest transaction, the file and time of
its last ingest, and a strip of buckets (ISO weeks, Monday–Sunday, by default) over a chosen
window. A bucket with rows is filled; a bucket with none inside the account's range is a
**hole**; outside that range it is unanswered. Holes are derived from the current rows alone,
because the log records no statement periods: a hole says "check me" — a statement may have
been imported and simply had no activity — and never asserts "not imported". A bucket
cross-links to the Blotter for that account and period. Marking a hole with a person's
conclusion (received, no activity) would be a decision, not a property of this view.

**Chain health.** The same mode (or its own view, once it earns one) carries the balance
check of §6.9: per account, `reconciled` / open, with forks shown as conflicts — the two
sides, the balance value they contest, and a per-side preview of resolving one (or
keeping both). It is the operator surface for roles: a fork appears the moment the head
moves, is explained by an account profile or a `MARK_NOOP`, and clears itself when the
chain closes. Nothing here is stored: it is a view over the derived check.

### 10.6 Expected mode

The forward view: what is committed and what is behind. A window (today, this week, this
month) lists the materialised occurrences — date, commitment, direction, amount — with the
verdicts rendered honestly: a green tick for **occurred**, which carries the fact; `settled`
marked distinctly, because a person concluded it and there is no fact to show; `due`;
`partial`; and a red cross for **missed**. The window's committed total splits out / in, with
income in green, and the matched fact rides on the row.

Below the list, the **Catch up** panel shows every occurrence in arrears across
commitments, oldest first, with a running total and a per-row **Settle** (a
`SETTLE_OCCURRENCE`, the off-journal conclusion) or **Assign a payment**, which hands the
operator to the Blotter to pin the fact that paid it. The **registry** carries every
commitment — candidate, active, dormant, ended — with its origin, faces, current and
previous price, last and next date, arrears and note thread, plus the curation actions:
retire, re-declare, note, settle. A **lint** panel lists each commitment's rules and warns
when the same match regex sits on more than one commitment; a never-fired count is a
matches-derived figure, computed later.

On the Blotter and the Eyeball, a matched row shows the commitment chip (a derived join,
display only — §10.13) and the row action **Assign to commitment** (`PIN_COMMITMENT`, or
`UNPIN_COMMITMENT` when a chip is already there) — the commitment analogue of inline
categorisation. The Expected tab is part of the regular review routine (operator,
2026-10-08): it is where a dormant commitment, an arrear and a red occurrence get their
decision. The Eyeball anomaly `RECURRING_MISSING` can be re-sourced from missed occurrences
as a follow-up (§10.12).

---

## 11. Firefly bridge — projection with convergence

Firefly stays a one-way projection. What changes is that the egress can **plan, apply
and verify**, and drift is a report rather than a surprise. The v1 egress already
learned most of these lessons the hard way (SPEC §5.8, DECISIONS V9); this section is
their v2 home.

**It is a batch, not a daemon.** There is no polling and no service — an invocation is
the gate. Half-tuned categories must not reach Firefly while you are mid-edit, and a
background process would project a rule you wrote thirty seconds ago and were about to
fix. `--plan` and `--verify` are timer-safe; `--apply` runs when you say so. In v2 the pass
is dispatched from the hub's Jobs view through `trex runner` (§5.5) — still a batch, started
on instruction, never a daemon.

**Not a log mirror.** A mirror copies journal lines; this projects **resolved units**,
and the unit is where the expensive mistake lives:

| | mirrors (`archive`) | Firefly |
|---|---|---|
| unit | one journal line | one resolved transaction |
| TRANSFER legs | copied, for audit | **skipped** — the TRANSFER replaces them |
| HELD / REVIEW | copied | **withheld** until resolved |
| ATTESTATION | copied | **never posted** — no money moved |
| PENDING observations | copied | **excluded** — not transactions yet |
| categories | absent (no category in the log) | attached, refreshed when rules move |

Posting a TRANSFER and its legs double-counts every internal movement, silently — a
wrong total rather than an error, and the most expensive mistake available in an egress.
The exclusions are asserted against real-shaped fixtures, not left to reasoning (§15).

**What gets projected is a view over the derivation.** In v1 the egress reasoned about
`legIds` to know what a leg was; in v2 the hub publishes the projectable units —
resolved current transactions, TRANSFER rows, their categories and origins, the
`configRevision` that produced them — and the egress consumes them. The unit rule lives
once, on the derived side (§7), and the egress never reconstructs trex's classification.

**Unit identity.** For a posted EXTERNAL transaction the unit id is the resolved
`externalId`; for a TRANSFER it is the `transfer_id`, minted with v1's rule —
`TRF-<receipt>` for an exact receipt match, else `transferId(root(legA), root(legB))`
over the legs' **chain roots** — so a re-parse that supersedes a leg does not change the
unit's identity. The content hash moves, the group is updated, and Firefly never sees a
duplicate (§11.5).

### 11.1 Modes

| Mode | Effect |
|---|---|
| `--plan` | Compare derived units against Firefly; print the diff, including orphans and replacements. Writes nothing. |
| `--apply` | Execute the diff (create, retag, update), recording projection state as each write lands. |
| `--verify` | Rebuild the projection state from Firefly, then plan with an empty-diff expectation; non-zero exit otherwise. Timer-safe. |

Flags: `--hub-url`, `--firefly-url`, `--accounts firefly.yaml`, `--retries`,
`--retry-base-ms`, `--retry-max-ms`, `--seed-categories`, `--create-missing-accounts`,
`--print-accounts`, `--remove-orphans` (§11.5). The API token comes from
`FIREFLY_TOKEN` in the environment only — a flag lands in `ps` and in shell history.

### 11.2 Unit selection and shape

Five exclusions, each with the failure it prevents: legs (double-count), HELD/REVIEW
(undecided), ATTESTATION (a `$0` transaction that would repeat forever), PENDING (not a
transaction yet), retired facts. Everything else posts.

**The type comes from the Firefly accounts, not from trex's classification.** Firefly
6.7.3 refuses a `transfer` that crosses the asset/liability line — 21 of the 30
transfers in the real journal — so the two mapped account kinds decide:

| from → to | Firefly type |
|---|---|
| asset → asset, liability → liability | `transfer` |
| asset → liability (paying a card or a loan) | `withdrawal` |
| liability → asset (a card refund, drawing a loan) | `deposit` |

**Counterparties are merchant stems** — `Merchant.stem`, the same class the worklist
groups by, so Firefly's auto-created expense accounts and trex's merchant list cannot
drift. A spend posts as a withdrawal from the account to `destination_name = stem`; an
income as a deposit from `source_name = stem`. One shop is one account: 1703
withdrawals collapse to 442 counterparties. Transfers carry `source_id`/`destination_id`
and no names — a name would let Firefly guess.

**Every posting carries what a rebuild needs, plus our dedup:**

| field | value |
|---|---|
| `external_id` | the unit id; it participates in Firefly's duplicate hash (measured), so a collision can only ever name this same row |
| `internal_reference` | the unit's own `accountRef` |
| `tags` | `["trex", "trex-category:<CATEGORY>"]` — one prefix, decided once, here |
| `notes` | `trex n=<n> rules=<configRevision>` + the raw description |
| `category_name` | always sent, `UNCATEGORIZED` included — a missing tag is indistinguishable from an egress that failed |
| `error_if_duplicate_hash` | `true` — the rejection names the existing group, which is how a lost projection state recovers |
| `apply_rules` | `false` — trex is the single classifier; Firefly's rules must not fight the category |

Amounts are exact two-decimal strings from cents — never floats, never
locale-formatted — and `currency_code` is the stamped currency.

### 11.3 Accounts, config, opening balances

`firefly.yaml` maps each `accountRef` to a Firefly account **name and type**
(`asset|liability`), keyed by name and never by Firefly's numeric id — a rebuilt
instance changes every id and no name, and that is precisely when hand-editing config is
least welcome. The type is load-bearing (the matrix above) and the name is the lookup
key: a rename in Firefly is a hard stop at startup, naming the unmapped ref and the
unclaimed accounts, never a silent post into a guessed account. Currency is checked too:
Firefly converts silently on a mismatch and the ledger simply stops reconciling, with
nothing to show for it.

**Preflight before the first write.** Startup reconciles the configured refs against the
instance; then the whole unit list is checked for refs the file has never heard of,
naming every unmapped account at once. The journal moves underneath the config — ingest
a statement for a new account and the next pass meets it — and the failure this prevents
is the one that aborts at transaction 900 with 899 already posted and nobody able to say
where it stopped.

**Opening balances.** Firefly's accounts only reconcile against the bank if each starts
from what it held before trex saw anything. The opening is derived **backwards** — the
latest balance minus every amount since — because a statement does not record intra-day
order, and anchoring forward instead was a measured $13,661 coin toss on this journal.
The forward figure is still computed for one reason: the disagreement is exactly the
value of transactions the bank's running balance knows about and the journal does not
(a partial export), so it is reported as a confidence gap, never hidden. A declared
account opens on its latest `ATTESTATION` and reports no gap. Account creation is opt-in
(`--create-missing-accounts`), so a mistyped name cannot quietly create an eleventh
account and post a year into it; a liability is seeded negative with
`liability_type: debt` and `liability_direction: credit`, because seeding one positive
puts the account out by exactly twice the figure.

**Categories are seeded, not discovered.** `--seed-categories` creates the declared
categories in Firefly, each carrying its rule comment as the category's notes, so "why
is this GROCERIES?" is answerable there too. Postings auto-create a bare category first,
so the pass also fills notes on categories that have none — and only then: anything you
typed is yours.

### 11.4 Re-tagging

**Read-modify-write, per split.** A `PUT` takes the complete transactions array, so a
body built from scratch collapses a group you split by hand and destroys the work
silently. The egress reads the group, returns every split and the `group_title`, and
changes only what it owns:

- the tag is always updated to what trex last said;
- `category_name` moves only while it still equals the tag we last wrote — if the
  category differs, you changed it, and the human wins;
- when the name does move, `category_id` is cleared from the body: Firefly resolves the
  id before the name, so echoing a stale id updates the tag while leaving the category
  put — the failure that looks exactly like success (found in the first live run);
- `apply_rules` stays `false` here too.

The rule from v1 is preserved and generalised: **read back only what we wrote**; never
import an opinion. A correction made in Firefly is a symptom, not a workflow — the fix
belongs in trex, where every consumer sees it.

### 11.5 Drift taxonomy, including de-projection

| Case | Detection | Action |
|---|---|---|
| Never projected | No group for `unit_id` | Create |
| Category changed (a rule or a pin) | Tag ≠ current, and the category still equals the tag we last wrote | Retag (§11.4) |
| Human changed the category | Category ≠ what we last wrote | **Leave.** Report as human-owned; the tag still moves |
| Content changed (supersede, restatement) | `state_hash` differs | Update the group; identity is chain-rooted and survives |
| Unit no longer projectable (`UNPAIR`, `REVOKE`, `RETIRE`) | Group exists, no unit | Report an **orphan** — never delete automatically; `--remove-orphans` is the explicit instruction |
| Unit replaced (a transfer split back to legs, a re-pair) | Orphan + new units | Report both sides together so the double-count is visible; apply never resolves it silently |
| Deleted in Firefly | Group gone | Recreate if the unit still exists; otherwise report |
| Orphan group with a trex tag | No matching unit | Report only — `--remove-orphans` on instruction |
| Splits / budgets / manual entries | Not ours | Never touched |

v1 could say "nothing is ever deleted, because decisions are final" and mean it: a
projected unit could not stop being one. v2 must not carry that sentence — `UNPAIR`,
`REVOKE`, `RETIRE` and a supersede that changes identity can withdraw a unit after it
was posted. The policy is the same as everywhere else: nothing is deleted automatically;
the plan says what is now orphaned and what replaced it, and a human runs
`--remove-orphans` when they agree. Reporting the pair matters, because until the orphan
is removed, the replacement looks exactly like a double-count — which it is, and the
plan should say so.

### 11.6 Projection state and recovery

`projection_state` in the index records `(unit_id, unit_kind, group_id, category,
config_revision, derive_version, state_hash, verified_at)`. It is an **accelerator, not
a record**:
every column is recoverable from Firefly — the group id from `external_id`, the last
projected category from our `trex-category:` tag, the journal `n` from the notes.
Deleting it costs requests, never a fact. Three consequences:

- **rebuild is a first-class path, not an emergency.** `--verify` rebuilds the state
  from Firefly before it plans, so the recovery path runs on a timer rather than waiting
  for its first accident;
- **the resume point is derived, not stored**: the high-water `n` is the maximum read
  back from Firefly's notes, so a rebuilt state knows where to resume with nothing
  saved;
- **a revision change widens the pass.** If `configRevision` moved since the last run,
  any row can have moved, so everything is re-planned rather than only what is new —
  otherwise a retune never reaches Firefly.

The egress needs one feed from the hub: `/api/units?sinceN=…` returns the units, their
categories and origins, `asOfN` and `configRevision`, so a no-change run is cheap and
the egress reads through the hub API only — never the index, never the sequencer.

### 11.7 Failure handling

**Retry the transient, stop on everything else.** A dropped connection, a 5xx or a 429
is retried with exponential backoff and jitter, honouring `Retry-After`; a **4xx is
never retried** — Firefly is saying the request is wrong, and repeating it changes
nothing but the clock. When the attempts are spent, or Firefly refuses outright, the
pass **stops where it happened**, names the transaction, its account, its date and
Firefly's message, writes to stderr, and exits non-zero so a scheduled run cannot report
success.

Counting failures and carrying on was the wrong default: the refusal means either the
projection is wrong or the instance is not what startup reconciled against, and both are
true of every row still to come — so continuing multiplies one legible error into noise
and leaves a half-projected Firefly whose state nobody has stated. Stopping is cheap
because projection state records each write as it lands: a rerun resumes rather than
repeats. Long passes report progress every 50 units, with the rate measured over the
last window — a resumed run skips its prefix in microseconds, and a whole-pass average
would report a rate true of nothing.

### 11.8 Scope

Firefly is the only projection v2 specifies. hledger is **parked** — it will be rebuilt
later — and nothing here depends on it: the seam stays behind one interface, so a second
target can return without moving anything else. No v2 decision waits on hledger.

Transaction notes (`NOTE`, §6.2) are **not projected** for now: they are private annotations
and a note may name a person. If projected later, they map to the Firefly transaction note,
one-way like the category tag.

Commitments (§6.11) are **not projected** to Firefly in v1: Firefly's unit is a transaction,
and a commitment is an expectation — projecting one would post money that never moved. The
parking is deliberate, not a silent drop.

---

## 12. Ingest: sources, feeds, pending

### 12.1 Adapter contract

```java
interface SourceAdapter {
    String sourceType();                       // "bw-csv", "cba-pdf", "cdr-feed", …
    Stream<RawRecord> read(SourceRef ref);     // evidence + raw fields
    ParsedRecord parse(RawRecord raw);         // → FactDraft, or a reported skip/reject
}
```

- `read` is where bytes and evidence live; `parse` is pure and versioned.
- The whole-file validation of v1 stays: parse everything, report every bad row,
  send nothing until clean.
- The day-atomic batching rule stays: `occ` is `(account, day)`-scoped, never split a
  day across calls.

### 12.2 Feeds

A feed is the same contract with a cursor instead of a file:

- `source_cursor` stores the provider cursor; ingest is idempotent by evidence hash;
- feed rows are stored as evidence (the raw provider payload) exactly like a CSV row;
- the same row arriving from a feed and a statement must mint one id — the cross-source
  test is the acceptance criterion;
- feed-only fields (counterparty, foreign amount) become explicit, typed additions to
  the fact shape when the first feed adapter lands; provenance, never identity, never a
  generic blob (§19);
- feed lag/absence is an Eyeball-mode anomaly (account silent).

### 12.3 Pending rows

v1 skips pending authorisations and reports them, for good reasons: the journal is
append-only, so an ingested pending row could never be removed. v2 keeps the concern and
drops the compromise — a pending row is recorded as a **provisional fact**
(`observation: pending`) and is never lost:

- it is appended like any other observation (dedup, `n`, fsync, recovery — all the
  same), so it survives restarts and index rebuilds;
- `derive()` excludes it from current totals, the balance chain and projection, so it
  cannot double-count the spend;
- when the settled row arrives — different text, therefore a different `externalId` —
  derivation matches it on account, amount, the `transfers.yaml` date window and
  deterministic merchant-stem similarity (§8.4) and records `settled_by`; the pending
  lane shows "settled by …" and closes. If more than one posted row is plausible, the
  match is ambiguous: an `AMBIGUOUS_SETTLEMENT` item shows the candidates, and the user
  closes it with `SETTLE` (§6.2) — derivation never guesses between them;
- if the account's posted-fact frontier has passed its date by more than the account's
  `settlementWindowDays` (default 7, `accounts.yaml`) and it still has not settled, it
  becomes a `STALE_PENDING` review item — reported, explainable, `DISMISS`-able (or
  `SETTLE`-able late), never deleted;
- the pending lane shows age, amount and account, so money in flight is part of the
  weekly eyeball rather than a row the parser skipped.

That is strictly better than v1's skip: the observation is kept (evidence), the
transaction is not counted (accuracy), and the review queue owns the difference.

### 12.4 Manual cash

Keep v1's shape exactly: `ref` minted client-side as the receipt, identity by natural
key, purchases signed, attestations `balance`-only, refused on `statement` accounts.
In v2 the attestation discriminator is explicit in `derive` (§6.1), so the ambiguity
disappears.

A manual entry is a **fact**, not a decision, because it is an observation about the
world — "I spent $40 on vegetables" is the same kind of statement as the bank's row,
just authored by a person rather than published by a bank (`sourceType: manual`,
`provenance: AUTHORED`, evidence `null`). The MAN- `receipt` gives it identity by
natural key; a correction is a new fact plus `SUPERSEDE` or `RETIRE`, never an edit
(§8.2). The fact stays person-less like any other row; if attribution matters on a row,
a `NOTE` decision carries it — decisions are where people are named (§6.6).

### 12.5 The staging inbox

A statement may arrive through the browser instead of the filesystem. `trex runner`'s
staging area accepts an upload, lists it with a source type and account, and ingests it on
the press of a button — the same adapter contract and the same batch as a CLI run, with the
staged file as the input. Staging is a place a file waits, not a new source: the evidence
store keeps the bytes and the log keeps the facts, so a staged copy is transient and may be
cleared once its evidence id appears on facts. Evidence is never pruned — it is what makes a
parser fix and a re-parse safe.

### 12.6 Ingest events and the source archive

An ingest is **self-documenting on the stream**: the ingest client appends an `ingest`
`start` event, then its facts, then an `ingest` `complete` event with counts and status
(§6.7). Facts sit strictly between the two markers, so a batch's `n` range *is* the markers
— no separate offset is stored. A file that fails validation still emits the pair
(`status: bad_rows`, zero counts): the *attempt* is the audit. A crash between them leaves a
dangling `start`, which is the truth, and reads as an incomplete batch.

The **source file** is also archived: gzipped under a dated, human name to
`archive/sources/<Y>/<M>/<D>/<HHMMSS>-<name>.gz`. The **ingest client** owns this — so a CLI
ingest and a UI ingest behave alike, and the runner only supplies the archive location and
the original name. It is per-attempt and not deduped: the arrival log is the point, and
statements are small. Evidence remains the machine copy; the source archive is the human one.

The ingest history the Hub reads pairs each batch with its account's **frontier** — the newest
transaction date already processed for that account (`MAX(txn_current.date)`, clamped to today) —
a plain read, not stored state. It is the lower bound for requesting the next statement file by
date range (`V2-INGEST-FRONTIER-PLAN.md`).

---

## 13. Decisions: append-only, never final

The one philosophical change worth being explicit about:

- v1: decisions are final; the UI prevents mistakes with double confirmation.
- v2: decisions are **append-only and revocable** — a later decision can undo an earlier
  one, both stay in the log, and every derived state is attributable to the decision
  chain that produced it.

Why: reflow changes the calculus. A mispaired transfer used to be a rare, permanently
expensive error; now the matcher proposes pairs continuously, so "I was wrong, undo
that" must be an ordinary, auditable operation. Finality without undo does not make
decisions safer; it makes people avoid using the tool.

The mechanism is deliberately small (§6.2):

- **family inverses** for the everyday cases — `UNPAIR`, `MARK_EXTERNAL`, `UNPIN`,
  `USER_UNACK`, a re-`PIN`, a later `USER_ACK`;
- **`REVOKE`** as the general undo — it names the `n` of the decision it undoes and is
  the only way back from `SUPERSEDE`, `RETIRE` and `DISMISS`. A later `REVOKE` can revoke
  the `REVOKE`, because the latest answer wins;
- no deletion, ever. The audit property is preserved: "who decided what, when, and what
  replaced it" is the log.

Keep the double confirmation for destructive-feeling actions: it is cheaper than a
`REVOKE`, and now a mistake is not permanent either way.

---

## 14. Operations — the living part

The system is a habit, not a program. Make the habit cheap:

| Rhythm | Command | Expected result |
|---|---|---|
| Per statement / feed tick | `trex ingest …` | Facts appended; duplicates no-op; egress plan ready |
| After ingest | `trex hub` → Review | Review queue drained (or deliberately deferred) |
| On your own cadence (weekly is typical) | Eyeball mode → fix red → read the rows | Rows read for **you**; anomalies explained; categories improving |
| Before a rule edit | `trex reflow --preview` | Diff reviewed, then save; read rows flag automatically if they moved |
| Timer (plan) | `trex egress firefly --plan` / `--verify`; `--apply` on instruction | Firefly convergence known; nothing half-tuned projected |
| On demand (Jobs view) | `trex runner`: `ingest`, `egress firefly --plan` / `--verify` / `--apply`; upload to the staging inbox | The same batches, started and watched from one page; `--apply` confirmed against a plan |
| Promotion (dev → prod) | `trex stream` export on dev, ingest on the host | The stream lands line for line; `n`, `atMs`, decisions and evidence ids preserved; the copied prefix keeps its original `env` |
| Nightly | `trex egress archive` + backup | Log + evidence on a second disk |
| Weekly | `trex runner` → journal snapshot + `prune-archive` | A dated gzip copy of the log; old snapshots pruned explicitly |
| Monthly | `trex verify` | Framing, reconciliation, index equivalence (rebuild into a scratch file), evidence hashes, egress plan all green |

### 14.1 Promotion is replay, not a copy

A dev log and a prod log are both legitimate, but they are not interchangeable files: every
line carries the `env` and `source` of the process that wrote it. The log is nevertheless
**portable as a stream**, and the stream is the unit of promotion: `trex stream export`
writes the journal as-is — facts, decisions and ingest events, envelope included — and
`trex stream ingest` appends it line for line to another sequencer:

```sh
trex stream export --journal journal/trex.jsonl --out trex-stream.jsonl.gz   # on dev
trex stream ingest --file trex-stream.jsonl.gz --sequencer-url http://…      # on prod
```

Because nothing is re-derived, **ordering is inherent**: fact/decision interleaving, `occ`,
re-observations, `REVOKE` targets, `DISMISS` scope, `atMs`, `externalId` and evidence ids all
arrive exactly as written. No watermark, no remapping, no re-minting — the target's history
is the source's history.

Constraints, stated once:

- **A fresh target or an exact continuation.** Ingest requires the stream to start at
  `head + 1` (or 1 on an empty log); `--since n` exports a suffix for resume or replication.
  It is a seed and a replica, never a merge: two unrelated histories do not combine.
- **Evidence travels with it.** The facts name evidence ids; the evidence store is copied
  alongside and `trex verify` checks the hashes, so re-parse and audit stay possible. A
  referenced id that is missing is refused, not ignored.
- **The config must match.** The manifest carries the source's `configRevision` and
  `deriveVersion`; ingest refuses before writing anything if the target's differ.
- **The stream is re-validated as it lands:** `n` contiguity, a known kind at a supported
  `v`, a well-formed envelope, a known `accountRef`, decision cross-references resolvable in
  the prefix. A bad line stops the ingest and names itself.
- **`env` and `source` are preserved verbatim.** They state where the line was *written*,
  which stays true of the copied prefix; the cutover is visible where the envelope changes
  from the dev environment to the target's. No marker line is appended, because it would
  break `n`-contiguous continuation — the import is recorded in the runner's run history.

Ingest is resumable: re-running skips the prefix already present and continues at
`head + 1`. When a stream cannot apply — a target that already holds its own unrelated
history — the fallback is a decision-level export/replay carrying each decision's
`factsBefore` watermark, so conclusions are merged without merging histories.

**The stream is plain JSONL, and it may be transformed.** Files are gzipped on export and
accepted gzipped or plain on ingest, so ordinary tools compose:

```sh
zcat trex-stream.jsonl.gz | jq -c '.env = "Prod1   "' > prod-stream.jsonl
```

A transform is safe when it edits fields per line — typically re-stamping `env` for the
target, or redacting comment text — and keeps `n` contiguous, preserves line order and
leaves every identity field (`externalId`, `accountRef`, `date`, `amount`, `balance`,
`receipt`, `occ`, `evidenceId`) untouched; a rewritten `source` must name a process declared
in `sources.yaml`. Ingest re-validates structure but cannot detect a semantic edit, so the
untransformed export stays the file of record. `trex stream ingest --env Prod1` re-stamps
the environment as a convenience; the default preserves the source's.

**Deployment is one image.** The rhythms above are commands of the same artifact; only
the sequencer mounts the journal read-write, and every other role gets it `:ro`. Compose
and systemd differ only in the `command:`/`ExecStart=` line. Three roles run always: the
sequencer, the hub, and `trex runner` — the last loopback-only, proxied by the hub for the
Jobs view and the staging inbox.

**Backups:** the only irreplaceable things are the log, the evidence store and the config
(git). Back those up; the index, projection state and review queues are rebuildable.
That is a much smaller backup story than v1's, and a much easier restore test.

**Disaster recovery drill:** stop `trex-hub`, wipe `index/`, run
`trex index --rebuild && trex verify`, start the hub again, compare the row hashes.
This is the cold path — a re-snapshot; a restart is the warm path, resuming from the
persisted offset. Run the cold one as a scheduled test, not an emergency.

**Status strip** (always visible in trex-hub, also printed by `trex verify`):
`n`, index lag (lines behind the log head), facts, open review by kind, duplicates,
unmatched transfer-shaped rows, open and stale pending, `UNCATEGORIZED` count, Firefly
drift count, the current user's read state (rows unread / changed since read),
config revision + derive version + hash version, last reflow, last backup,
reconciliation state per account.

---

## 15. Testing and guarantees

v1's tests are good; v2 adds invariants that only exist once derivation is separated:

1. **`derive` is pure.** Same inputs (including `asOf`), same output; no ambient clock,
   no env, no I/O.
2. **`derive ∘ derive = derive`.** Reflow twice, no diff.
3. **Rebuild equivalence.** `index --rebuild` at the same `asOf` produces identical
   derived tables (hashes, not file bytes) to incremental materialization.
4. **Decisions win.** A `PAIR` survives any rule change; an `UNPAIR` is never
   re-matched; a `DISMISS` stays dismissed; a `PIN` survives any rule change and an
   `UNPIN` returns the row to the rules. Every id they name is chain-resolved first
   (§9.8).
5. **Migration preserves identity.** The set of `externalId`s after importing a v1
   journal equals the set before; full v1 journal bytes map to v2 facts+decisions with
   no id changes.
6. **Reflow is honest.** A preview diff equals the actual result of applying it.
7. **Per-user ACK invalidation.** Moving a read row marks it *changed since read* for the
   user who read it — and only for them — while every other row keeps its marker.
8. **Egress convergence.** After `--apply`, `--plan` is empty; a hand edit in Firefly
   is reported, never overwritten; a de-projected unit becomes a reported orphan and is
   never deleted automatically (§11.5).
9. **Evidence.** Re-parsing stored evidence with the same parser reproduces the same
   facts; the identity cross-source test holds for every pair of sources.
10. **Reconciliation** (v1 test 6) runs over derived state, unchanged, including the
    `DECLARED` case. With roles (§6.9), the chain runs over `transaction` rows only: a
    `noop` row removes its edges and amount and nothing else; the exclusions are named in
    the result; a profile rule and a decision agree, with the decision winning in both
    directions; and a rebuild reproduces every role exactly.
11. **Pending is never lost and never counted.** A pending fact survives a restart and a
    full index rebuild; it is absent from current totals, the balance chain and the
    Firefly projection; when exactly one posted counterpart arrives, `settled_by` is
    derived and the lane closes; an ambiguous match becomes `AMBIGUOUS_SETTLEMENT` and
    is closed by `SETTLE`; when it does not settle, it becomes `STALE_PENDING` and stays
    until `DISMISS`ed. The status strip count matches the lane at every step.
12. **One artifact, every role.** The shaded jar answers `--help` for every subcommand
    and runs every role from the same file; the image runs every compose service from
    the one tag; no role needs a different artifact, and no config is baked in.
13. **Pins are events.** A `PIN` overrides a matching rule and survives a later rule
    change; a later `PIN` supersedes an earlier one for the same id; `UNPIN` returns the
    row to rule evaluation; `TRANSFER`, `UNCATEGORIZED` and undeclared names are refused;
    pinning a structural transfer is refused; pins carry through a `SUPERSEDE` because
    every decision resolves through the supersession map, not as a special case; an
    orphaned pin is a lint item, never silently dropped.
14. **Revocation is total.** Every action in §6.7 can be undone by a later decision — a
    family inverse or `REVOKE` — and the derived state returns to what it was before;
    revoking a `REVOKE` restores the original. No action needs new log grammar to be
    undoable.
15. **Retirement counts for nothing.** A retired fact is absent from `txn_current`,
    totals, the balance chain and projections, and present in the log and the blotter;
    `REVOKE` brings it back.
16. **Supersession never orphans a decision.** After a re-parse supersedes a leg, its
    `PAIR`, `PIN` and `DISMISS` decisions still apply via `fact_resolved`; a chain that
    would close a cycle, or point at a retired fact, is ineffective and surfaced, not
    silently dropped.
17. **Time is an input, not a side effect.** Rebuilding at a later `asOf` changes only
    stale/age statuses; every `stateHash` and every review verdict is identical. The
    same rebuild at the same `asOf` is identical, table for table.
18. **The Firefly unit set is exact.** Legs are never projected; an ATTESTATION is never
    projected — it would post a `$0` transaction, forever; HELD/REVIEW, PENDING and
    retired facts are withheld; the unit count is transfers plus posted EXTERNAL
    transactions, asserted on a real-shaped fixture.
19. **The Firefly type follows the accounts, not the classification.** All four
    asset/liability combinations map as §11.2 says; a re-tag clears the stale
    `category_id`; splits and `group_title` survive read-modify-write; a hand-edited
    category is preserved while the tag moves; an unmapped or unresolved account is
    refused, never guessed.
20. **Fail-fast and resume.** A transient failure is retried and a 4xx is never retried;
    an exhausted retry or a refusal stops the pass naming the transaction, its account
    and date; an unmapped account stops the pass with nothing written, naming every
    unmapped ref; a projection state rebuilt from Firefly resumes at the right `n` and
    does not re-post.
21. **Openings are honest.** Backward derivation is order-independent (a forward anchor
    is shown to depend on intra-day order); a forward/backward gap is reported as "the
    journal is short", never posted as zeros; a liability is seeded negative; category
    seeding fills empty notes only and never overwrites typed ones.
22. **Commitments are curated by decisions and disposable in every other part.** The nine
    §6.11 actions are latest-effective-wins — per id, per candidate key, per fact, per
    `(commitment, dueDate)`, per (commitment, fact) — and revocable; a decision naming an unknown target is
    ineffective and surfaced. Candidates, rules, occurrences, prices, arrears and the
    dormancy question come from `(facts, decisions, config, asOf)`, and the five tables join
    rebuild equivalence: `trex verify` covers them (`IndexerTest`), and a rebuild reproduces
    the same rows. `occurred` (a fact) is never conflated with `settled` (a conclusion).
23. **Commitment detection and matching are deterministic and core-fields-only.** At the same
    inputs and `asOf` the candidates, rules, occurrences and arrears are identical
    (`CommitmentsTest`, `CommitmentMatchTest`, the `DeriveTest` commitment cases); a
    commitment matched on a transfer leg does not move when `transfers.yaml` changes, because
    a rule and the domain it runs over read only `rawDescription`, `account`, `amount`/sign
    and `date` — never `leg`, `pairing`, `category`, `role` or `synthetic`. An `irregular`
    commitment records each matching fact at its own date without ever predicting, going
    dormant or accumulating arrears.

Everything else from v1 §7 carries over: golden files per source, day-atomic batching,
gzip transparency, torn-tail recovery, materialize bit-identity, listener separation.

---

## 16. Migration from v1 — retired

There is **no migration**. v1 is archived in the sibling `../TrexV1` (reference only) and a v2
journal starts at day 0; `RELEASE.md` records §16 as retired. The original mapping table is kept
in `docs/archive/V2-PROPOSAL-2026-10-08.md` should the question ever return.

## 17. Roadmap

Each phase is independently shippable; if you stop after any of them, the system is
still better than before.

| Phase | Content | Exit criterion | Size |
|---|---|---|---|
| **P0 — Index** | `trex-index` reads the v1 journal into a v1-shaped mirror (the log, row for row) and derives today's views in SQL; the blotter's `/api/snapshot` and `/api/ledger` query SQLite instead of the in-memory fold. The §7.2 fact/decision schema is the P1 target, not P0's. Also collapse the build to one shaded jar and one image (roles by command). No log format change. | Same pages and answers; stop hub, `rm index && rebuild` reproduces them; daily use unaffected; every role runs from the one artifact | M |
| **P1 — Log v2** | Fact/decision lines, sequencer writer, migration, `SUPERSEDE`/`RETIRE`/`REVOKE`, reflow **preview** | Migration verification green; preview on a real history produces a believable diff | M–L |
| **P2 — Automatic re-derive** | `derive()` versioned, state hashes, per-user `USER_ACK` invalidation; a saved rule change recomputes immediately, and the egress converges | Reflow twice = no diff; moving a read row flags it for its user | M |
| **P3 — Workbook** | Lint, fixtures, suggestions, coverage, redundant-pin cleanup; rule editing against the SQL index | The uncategorised share trends down and pins stop accumulating | M |
| **P4 — Convergence** | Firefly plan/apply/verify, drift taxonomy, projection state in the index | `--verify` green on a timer; a hand edit is reported, not clobbered | M |
| **P5 — Evidence & feeds** | Evidence store, re-parse workflow, feed adapters, provisional pending observations | A parser fix flows through as a reviewed supersede; a feed and a statement agree on ids; nothing pending is lost | L |

The envelope and the `ingest` kind (§6) are a **MAJOR** log-format change. Per `RELEASE.md`
there is no migration: the dev journal is transformed (§12.6) and the host journal is
re-ingested. Forward compatibility is now a hard requirement — every reader skips an unknown
kind rather than refusing the line.

P0 is deliberately first: it is additive, it de-risks the fold by proving the derivation
in SQL, and it delivers the blotter improvement before any format decision is made.

---

## 18. If you never rewrite: the minimum-change path

Everything above can be approximated inside v1, in this order:

1. Add a derived SQLite index as an *additional* consumer (no format change), and move
   the blotter's queries to it. Keep the in-memory fold for SSE/resolution lists until
   parity is proven.
2. Add `trex reflow --preview` that runs the current rules over the journal and reports
   what they *would* do. It cannot change history in v1, but it makes rule tuning
   evidence-based.
3. Add category lint and `categories.tests.yaml`; add pin suggestions from the worklist.
4. Add `trex egress firefly --plan/--verify`; treat drift as a report.
5. Add Eyeball mode and per-user `USER_ACK` markers (store them in a small sidecar; they
   are derived-ish state, not journal facts, until the log split lands).
6. Stop there. If the preview is enough and reflow is not missed, v1 plus an index is
   the whole system.

The only thing this path cannot buy is true reflow — and that is the one that needs the
log change. So the honest decision point is: **do I ever want to tune a rule and have
history change?** If yes, plan for P1. If no, P0 + P3 + P4 is a complete, cheaper answer.

---

## 19. Anti-goals

State them so future you does not drift:

- **No database as system of record.** The log stays the truth; the DB stays disposable.
- **No per-role artifacts.** One jar, one image; the role is a command plus config, and
  the writer/reader split remains processes, not packages.
- **No ORM, no Spring, no broker, no cloud.** No *general* scheduler framework — but
  `trex runner` is a deliberately bounded job runner: ordered steps, manual and interval
  triggers, sync/async, artifacts; no DAG, no conditions, no retries, no durable resume
  (§5.5).
- **No ML categorisation.** Rules are explainable, testable and free; revisit only if
  the ruleset demonstrably plateaus.
- **No generic mutable blob** on facts or decisions (you rejected this once already;
  keep rejecting it).
- **No rewriting `externalId`.** Ever.
- **No mutable decision state and no deletion.** Undo is an appended decision (`REVOKE`
  or a family inverse); a line is never rewritten or removed.
- **No wall-clock inside a hash.** Time enters as the explicit `asOf`, and only into
  statuses; `stateHash` is content only (§9.5).
- **No silent divergence between two sources.** Conflicts become review items.
- **No dashboard.** The blotter is for decisions; charts belong elsewhere.
- **No "smart" automatic repair of Firefly.** Plan, report, apply on instruction.

---

## 20. Answers to the open questions — historical

Every question this document opened has been answered, and the answers are folded into the
sections above (the archived original at `docs/archive/V2-PROPOSAL-2026-10-08.md` lists them).
The live record is the code, `V2-SPEC.md` and `CHANGELOG.md`.

## 21. The golden crown checklist — historical

This was the "next 5% that must not be lost" checklist; every item is now built and tracked by
`V2-IMPLEMENTATION-PLAN.md` and the `V2-*-PLAN.md` files. The original list is in
`docs/archive/V2-PROPOSAL-2026-10-08.md`.
