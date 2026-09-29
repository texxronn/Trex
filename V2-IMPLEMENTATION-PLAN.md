# V2-IMPLEMENTATION-PLAN.md

**How to build `V2-PROPOSAL.md`.** This document is the build order, the module
layout, the acceptance tests and the working rules. It is written to be executed by
an agent (human or LLM) with no further design decisions: where a decision is still
open, it is listed in §12 and must be asked, not invented.

Read in this order: `CLAUDE.md` → `V2-PROPOSAL.md` (the spec) → this file.
Where this file and the proposal disagree, the proposal wins; where the proposal and
`SPEC.md` disagree about v1 behaviour, `SPEC.md` wins for v1 code and the migration.

---

## 0. Ground rules

1. **The proposal is the specification.** Do not invent architecture, invariants,
   identity rules, log semantics or workflow. If something is missing, stop and ask
   (§12) — do not choose silently.
2. **Never grow the v2 log's write path.** `trex-sequencer` (v2) appends facts and
   decisions. If a change puts a *conclusion* (matching, state, category, duplicate
   flags) into the writer, it is wrong by construction.
3. **`derive()` is pure.** Same inputs → same output. No I/O, no ambient clock, no
   env, no randomness, no iteration over unordered maps. `asOf` is a parameter.
4. **Every derived table is disposable.** No build step may create state that a
   `trex index --rebuild` cannot reproduce (§4 rebuild test).
5. **v1 stays runnable until cut-over.** Do not modify v1 modules. If v1 must change,
   stop and ask.
6. **Test before moving on.** Every stage ends with compiling code and green tests.
   A stage is not done because it compiles; it is done when its acceptance tests
   pass (§6).
7. **Never weaken an invariant to make a test pass.** If a test cannot pass without
   bending a rule, the rule or the test is wrong — stop and ask.
8. **No new dependencies** beyond what `SPEC.md` §1 permits and what the proposal
   names (`sqlite-jdbc`, Jackson, picocli, SLF4J). No ORM, no Spring, no broker.
9. **Small commits**, tree buildable after each, no rewriting published history.
10. **Comment the *why*, not the *what*.** The measured facts in `DECISIONS.md` and
    the proposal (the 21-of-30 transfer rejections, the stale `category_id`, the
    preflight that saved 899 posts, the $13,661 opening coin toss) belong in the code
    that depends on them — they are the reason the code is shaped that way.

---

## 1. Repository layout

Same repository, same Maven reactor. A new `v2/` subtree holds the new modules; v1
modules are untouched and stay buildable as the rollback path.

```
Trex/
  pom.xml                     # reactor: v1 modules + v2/* modules
  SPEC.md  DECISIONS.md  V2-PROPOSAL.md  V2-IMPLEMENTATION-PLAN.md
  CLAUDE.md
  trex-core/                  # v1 — untouched
  trex-journal/               # v1 — untouched
  trex-sequencer/             # v1 — untouched
  trex-ingest/                # v1 — untouched
  trex-web/  trex-ws/         # v1 — untouched
  trex-egress/                # v1 — untouched
  v2/
    trex-core/                # modules in §2
    trex-log/
    trex-index/
    trex-sequencer/
    trex-hub/
    trex-dist/
  deploy/                     # v1 deploy, then v2 deploy added beside it
  docs/
    v2-lifecycles.html
```

**Rules for the split.**

- v2 modules never depend on v1 modules, and v1 modules never depend on v2, except
  the one allowed direction: `v2/trex-index` may read a v1 journal *file* (bytes, not
  classes). Do not import v1 classes into v2.
- Package root for v2 is `trex.v2.*` (e.g. `trex.v2.core.Fact`). This prevents a
  shading or classpath accident from silently binding v2 code to v1 types.
- `trex-v1` code that is genuinely shared (nothing today, by design) is duplicated
  deliberately and temporarily rather than extracted — extraction is what the
  proposal rejects (§5.2).

---

## 2. Modules

| Module | Kind | Depends on | Responsibility |
|---|---|---|---|
| `v2/trex-core` | library | nothing but the JDK + Jackson | `Fact`, `Decision`, `Config`, identity (`Ids`), `occ`, `clean`, `merchantStem`, `derive()` and its whole pipeline (§9.9), `stateHash` |
| `v2/trex-log` | library | `trex-core` | `Fact`/`Decision` JSONL framing, writer, reader, recovery, evidence store, `Json`/`Yaml` mappers, change signal |
| `v2/trex-index` | library | `trex-core`, `trex-log`, `sqlite-jdbc` | JDBC materializer: log → SQLite mirror → derived tables (§7.2), index lock, offset, rebuild |
| `v2/trex-sequencer` | service | `trex-core`, `trex-log` | The only log writer: `POST /facts`, `POST /decisions`, `GET /head`, recovery, fsync |
| `v2/trex-hub` | service | `trex-core`, `trex-log`, `trex-index` | Owns the index; blotter API + UI; rule writer; decision precheck + forward; SSE |
| `v2/trex-dist` | packaging | all | Shades everything into one `trex.jar`; picocli subcommands select the role |

`trex-dist` contains no code of its own. `v2/trex-ingest` (adapters, evidence, day
batching) is introduced in P5; until then, facts are supplied by the migration tool
and by tests. If the migration tool needs to emit facts, it does so through the same
`POST /facts` path — never by writing the log directly.

---

## 3. Stage plan

Each stage names: **Work** (what to build), **Acceptance** (the tests that must pass),
**Out of scope** (what not to build yet, even if tempting).

Stages are ordered so that each is independently shippable and nothing later is
required to make an earlier one work.

### P0 — Index and one artifact (no log change)

**Work**

1. `v2/trex-index`: a **v1-shaped** mirror. Read the v1 journal line by line
   (`SPEC.md` §2.2 `CanonicalEvent`), insert into SQLite tables that mirror the v1
   line 1:1 *including* derived fields (they are historical facts in v1, not
   derivations). Offset table, WAL, `busy_timeout`, the exactly-once
   transaction: apply lines + offset in one transaction.
2. Derived views over that mirror that reproduce what v1's in-memory fold produces:
   latest line per `external_id`, HELD set, REVIEW set, TRANSFER units.
3. `trex-ws`: add a `--index <path>` option. When present, `/api/snapshot` and
   `/api/ledger` are answered by SQL over the index instead of the in-memory fold.
   Everything else keeps using the fold. This is a parallel path, selected by config.
4. `v2/trex-index` CLI: `trex index --rebuild` — offline, takes the index lock,
   refuses while a hub holds it (§7.4).
5. `v2/trex-dist`: shade all v1 modules into one `trex.jar` with picocli subcommands
   (`sequencer`, `ws`, `ingest`, `egress archive`, `egress sqlite`, `egress firefly`,
   `egress hledger`, `index`). Roles by command; one image. No behaviour change.

**Acceptance**

- The blotter answers identically with the index on and off, on a real journal: same
  rows, same order, same totals, for `/api/snapshot` and `/api/ledger`.
- `rm -rf index/ && trex index --rebuild` reproduces the same answers; repeated
  rebuilds are identical (§4 rebuild test; §15.3 for the v1 analogue).
- Killing the indexer mid-apply and restarting resumes with no gap and no duplicate
  (the offset transaction test).
- `trex.jar --help` lists every subcommand; each role runs from the one file; the
  image runs every compose service from the one tag. No role needs a second artifact.
- v1's own tests still pass unchanged.

**Out of scope** the v2 log format, `derive()`, decisions, the hub, evidence.

---

### P1 — Log v2, sequencer, migration, preview

**Work**

1. `v2/trex-core`: `Fact`, `Decision`, `Config` records; `Ids` (identity, unchanged
   from `SPEC.md` §2.4); `occ` assignment; `clean`; `merchantStem`.
2. `v2/trex-log`: the v2 line kinds (§6.1, §6.2); framing reader/writer with the same
   torn-tail recovery and offset semantics as v1; `Json`/`Yaml` mappers configured
   exactly as v1 (`ORDER_MAP_ENTRIES_BY_KEYS` on, nulls written).
3. `v2/trex-sequencer`: the writer. `POST /facts` with `allOrNone`, dedup by whole
   observation (§6.5), `Appended | Duplicate | Flagged | Rejected`; `POST /decisions`
   with every action in §6.2, reference-and-structure validation only (§6.8);
   `GET /head`. One batch = one atomic append + one fsync. Single-writer `FileLock`
   on the journal.
4. `v2/trex-core`: `derive()` **preview only** — the full §9.9 pipeline producing
   tables, with no writes anywhere. This is the sandbox.
5. `trex reflow --preview`: run the candidate config against the journal copy and
   print the §9.3 diff shape (transfers, review, categories, invalidated periods,
   egress impact). Nothing is saved.
6. Migration tool: read a v1 journal, write v2 facts + decisions by the §16 table,
   through `POST /facts` and `POST /decisions` (or an offline batch that produces
   the identical log bytes — pick one and document it). Idempotent: run twice, no
   change.
7. `v2/trex-index`: mirror the **v2** log (level 1: `fact`, `decision`) and derive
   level 2 by calling `derive()`. Replace P0's v1-shaped mirror behind the same API.

**Acceptance**

- §16 verification green on a real v1 journal: same set of `externalId`s, same latest
  state per id, same HELD/REVIEW sets, reconciliation identical, categories identical
  under the same `configRevision`, Firefly `--plan` empty for a sampled window.
- §15.5: full v1 journal bytes map to v2 facts+decisions with no id changes.
- `POST /facts` re-delivery is a no-op (no new line, no fsync); the §6.5 dedup table
  is a test, row for row.
- A `Flagged` fact is appended exactly once, and its review item appears only after
  `derive()` runs — there is no `POTENTIAL_DUP` line in the log.
- The sequencer rejects structural faults (unknown id, unknown user, undeclared
  category, malformed payload, `REVOKE` of a missing `n`) and accepts semantically
  wrong but well-formed decisions, recording them.
- Migration is idempotent and byte-stable.
- `reflow --preview` produces a diff on a real history that a human reads as
  believable, and writes nothing (journal hash unchanged).

**Out of scope** automatic re-derive on file change, `USER_ACK`, the hub's rule
writer, egress work.

---

### P2 — Automatic re-derive, state hashes, per-user ACK

**Work**

1. `v2/trex-hub`: the hub owns the index (§7.4). Watch the journal and the config
   files; on change, apply and re-derive. One writer connection; read pool; the index
   lock; `busy_timeout`, WAL, relaxed `synchronous`.
2. `deriveVersion`, `configRevision`, `hashVersion`; `stateHash` per §9.9.G;
   `USER_ACK` payload and validity rules (§9.4).
3. Per-user ACK invalidation: compare stored hash to recomputed for the period;
   "changed since reviewed" names the ids that moved.
4. Reflow becomes real: a saved config change recomputes immediately, no apply step
   (§9.3).
5. `trex verify`: framing, reconciliation over derived state, index equivalence
   (rebuild into a scratch file and compare table hashes), evidence hashes, egress
   plan.

**Acceptance**

- §15.1: `derive()` purity — an input fixture set produces identical output across
  runs, and the pipeline never reads a clock.
- §15.2: `derive ∘ derive = derive` — reflow twice, no diff.
- §15.3: rebuild equivalence — offline rebuild hashes equal incremental materialization.
- §15.7: moving a reviewed period flips it red for **only** the user who acked it and
  names the moved rows.
- §15.4: decisions win across a rule change, ids chain-resolved.
- §15.17: rebuilding at a later `asOf` changes only stale/age statuses; every
  `stateHash` is identical.

**Out of scope** the workbook, egress convergence, evidence, feeds.

---

### P3 — The workbook

**Work**: rule lint (shadowed, never-firing, catastrophic regex, orphan pins,
redundant pins), `categories.tests.yaml` fixtures run on load and in CI, suggestions
from pins and uncategorised clusters, coverage by origin and trend, redundant-pin
cleanup offering `UNPIN`, rule editing against the index with blast radius.

**Acceptance**: the uncategorised share and the pin count are reportable; a lint
finding links to the offending rule/pin; fixtures fail the build on a regression;
editing a rule previews the diff before it is saved (§9.3).

**Out of scope** anything that writes the log; suggestions are proposals, not edits.

---

### P4 — Firefly convergence

**Work**: plan/apply/verify (§11.1); the drift taxonomy with de-projection
(§11.5); `projection_state` in the index with `derive_version`; recovery by rebuild
from Firefly; `--remove-orphans`; account preflight; opening balances; category
seeding; retry taxonomy; progress output.

**Acceptance**: §15.8 (after `--apply`, `--plan` empty; a hand edit reported, never
overwritten; a de-projected unit reported as an orphan, never deleted) and §15.18–21
(unit set exact incl. attestation; type matrix; splits and `group_title` preserved;
stale `category_id` cleared; fail-fast with the transaction named; openings honest).
A projection state deleted and rebuilt resumes at the right `n` without re-posting.

**Out of scope** auto-deletion, hledger, any write back from Firefly.

---

### P5 — Evidence, feeds, pending

**Work**: `evidence/` content-addressed store written by ingest before parsing;
`evidenceId` + `parser` on facts; the `--reparse` diff and its `SUPERSEDE`/`RETIRE`
preview; feed adapters with `source_cursor` and evidence-per-row; pending facts
recorded (`observation: pending`), correlated by `derive()`, with `SETTLE`; the
`AMBIGUOUS_SETTLEMENT` and `STALE_PENDING` flows.

**Acceptance**: §15.9 (re-parsing stored evidence with the same parser reproduces the
facts; cross-source ids agree) and §15.11 (pending is never lost and never counted;
settles or ages by the frontier rule; status strip count matches the lane).
`trex ingest` keeps `--source-type`, whole-file validation, day-atomic batching,
gzip and exit codes 0/1/2/3/64.

**Out of scope** anything the proposal parks (hledger, multi-currency logic, ML).

---

## 4. Coding standards for v2

- **Java 25**, records and sealed interfaces; exhaustive `switch` over sealed types
  so adding a case fails compilation.
- **No `null` where a sealed/`Optional` case is clearer**; facts/decisions use
  explicit nullable fields with documented meaning (the v1 precedent).
- **Purity is a compile-time habit**: `derive` and anything it calls takes its inputs
  as parameters. No static clocks, no `Instant.now()` anywhere in `trex-core`.
- **Every money value is `long` cents.** Format to decimal strings only at the edge,
  never with the default locale.
- **Every SQL statement lives in a `.sql` file** or a named constant with a comment
  naming the table it targets. No string concatenation of user input.
- **Every external call is timeout-bounded** and distinguishes retryable from
  terminal (`§11.7`).
- **Errors that a human must act on are messages that name the thing**: the id, the
  account, the date, the unmapped ref, the offending rule. No bare counts.
- **Log at the decision, not the loop.** A 1,751-row pass prints progress, not 1,751
  lines.

---

## 5. What to test, and how

**Harness.** JUnit 5. Golden files per source, as v1. Hand-built fixture journals for
`derive()`; a real-shaped journal slice for units and projection.

**Three test classes every v2 module needs:**

1. **Contract tests** — the record shapes, the log round-trip, the SQL schema.
2. **Invariant tests** — the §15 list, one test per numbered invariant, named after
   it (`deriveIsPure`, `decisionsWinOverReflow`, `rebuildEqualsIncremental`).
3. **Regression tests with the story in the javadoc** — every measured discovery, so
   the next reader knows why:
   - a transfer must not double-count its legs (attestation included);
   - the type matrix (asset↔liability → withdrawal/deposit) — with the "21 of 30"
     note;
   - a stale `category_id` on re-tag updates the tag and not the category;
   - preflight stops with **zero** writes when an unmapped ref is second in the list;
   - a 4xx is posted once and stops the pass; a transient is retried;
   - backward opening derivation is order-independent; forward is not;
   - a liability seeded positive is out by twice the figure.

**Fakes.** The Firefly and hub stubs in v1's tests are the template: a local JDK
`HttpServer` that reproduces the *behaviour that broke us* (the stale-id resolution,
the duplicate `422` naming a group). Do not stub a happy path only.

**The tests that gate the freeze (from the proposal, verbatim targets):**

| # | Invariant | Stage |
|---|---|---|
| 15.1 | `derive()` pure, `asOf` explicit | P2 |
| 15.2 | `derive ∘ derive = derive` | P2 |
| 15.3 | rebuild ≡ incremental at the same `asOf` | P2 |
| 15.4 | decisions win; ids chain-resolved | P2 |
| 15.5 | migration preserves identity | P1 |
| 15.6 | preview diff ≡ applied result | P1/P2 |
| 15.7 | per-user ACK invalidation is per user | P2 |
| 15.8 | egress convergence; orphans reported | P4 |
| 15.9 | evidence re-parse reproduces facts | P5 |
| 15.10 | reconciliation over derived state, `DECLARED` case | P2 |
| 15.11 | pending never lost, never counted | P5 |
| 15.12 | one artifact, every role | P0 |
| 15.13 | pins are events | P1/P2 |
| 15.14 | revocation is total | P2 |
| 15.15 | retirement counts for nothing | P2 |
| 15.16 | supersession never orphans a decision | P2 |
| 15.17 | time is an input, not a side effect | P2 |
| 15.18 | Firefly unit set exact | P4 |
| 15.19 | Firefly type matrix; CAS retag; splits survive | P4 |
| 15.20 | fail-fast and resume | P4 |
| 15.21 | openings honest | P4 |

---

## 6. Definition of done

A stage is done when **all** of these hold:

1. It builds with `mvn verify` from the reactor root, v1 included.
2. Its acceptance tests (§3) pass, named after the invariant they guard where one
   exists.
3. `trex verify` is green on a migrated copy of the real journal (from P1 onward).
4. The §4 rebuild test passes for anything that touched the index.
5. No v1 module was modified.
6. The commit message names the stage and the proposal sections it implements.
7. Any new measured fact (a bank quirk, an API behaviour, a performance figure) is
   recorded in the code that depends on it and, if it changes a rule, in the
   proposal.

---

## 7. Data migration and rollback

- **Never touch the v1 journal.** Copy it; record head `n` and rule revisions.
- The migration tool is **idempotent** and **byte-stable**: running it twice produces
  no second change.
- Rollback is "run v1 and point it at the old journal". The v1 tree stays buildable
  and tested until §16 step 6 (cut-over) plus an agreed window.
- The index, projection state, review queues and ACK copies are **never backed up**;
  they are rebuilt. The backup set is exactly: the v2 log, the evidence store, the
  config (git).
- The disaster-recovery drill (`stop hub; rm index; trex index --rebuild; trex verify`)
  is a scheduled test from P2 onward, not an emergency procedure.

---

## 8. Working with the agent

**Each work unit is one commit-sized change with one purpose.** Before writing code:

1. Read the proposal sections the stage cites. If a needed detail is absent, ask
   (§12) rather than invent.
2. Write the acceptance test first where the invariant is named.
3. Implement the smallest thing that passes it.
4. Run the full reactor build; do not move on with a red build.
5. Report: what landed, what the tests prove, what is deliberately not done.

**Do not**:
- add a dependency not in §0.8;
- introduce a state machine into the sequencer;
- write a derived value into the log;
- add a per-role artifact;
- "fix" a v1 module;
- keep a design decision in your head instead of in the code or the proposal.

---

## 9. Performance and scale

The proposal's target is 10⁴–10⁵ journal lines. State the numbers, measure before
optimising, and record the measurement where it is used.

- `derive()` full pass target: **< 2 s at 10⁵ lines** on the build machine. If a full
  pass exceeds it, measure the stage (P1–P11) before adding incremental complexity.
- Index rebuild target: **< 30 s at 10⁵ lines** (a `rm && rebuild` is the recovery).
- Blotter page target: **< 100 ms** at 10⁵ rows for a filtered page.
- No stage may hold the whole journal in memory twice; stream P1 where practical.

If a target is missed, the first question is "is the query right?", the second is "is
the schema right?", and only then "does this need an index or a cache?". A cache added
to meet a number is allowed only if it is rebuildable and its absence still gives the
right answer.

---

## 10. Explicitly not in scope

From the proposal's §19, restated so it is not rediscovered as a "good idea":

- No database as system of record; no SQLite truth.
- No per-role artifacts; one jar, one image, role = command.
- No ORM, Spring, broker, scheduler framework, cloud.
- No ML categorisation.
- No generic mutable blob on facts or decisions.
- No rewriting `externalId`.
- No mutable decision state and no deletion — undo is an appended `REVOKE` or a family
  inverse.
- No wall-clock inside a hash.
- No silent divergence between sources.
- No dashboard.
- No automatic repair of Firefly.
- hledger is parked; do not resurrect it as a v2 input.

---

## 11. Stage → deliverable map

| Stage | New modules | The thing you can show |
|---|---|---|
| P0 | `v2/trex-index`, `v2/trex-dist` | `trex.jar` with every role; blotter answers from SQL; `rm index && rebuild` green |
| P1 | `v2/trex-core`, `v2/trex-log`, `v2/trex-sequencer` | a v1 journal migrated to the v2 log, verified; `reflow --preview` diffing a real history |
| P2 | `v2/trex-hub` | the blotter on the v2 index; a saved rule change moving history; per-user eyeball markers; `trex verify` |
| P3 | (inside `trex-hub`) | the workbook: lint, fixtures, suggestions, coverage |
| P4 | (inside `trex-egress`/hub API) | Firefly `--plan/--apply/--verify`, drift and orphans reported |
| P5 | `v2/trex-ingest` | evidence store; a parser fix flowing through as a reviewed supersede; feeds and provisional pending |

---

## 12. Open decisions — ask, do not assume

These are the only places this plan does not fix an answer. Each must be put to the
operator before the stage that needs it.

1. **Migration write path** (P1): does the migration tool write through
   `POST /facts` + `POST /decisions`, or emit the identical log bytes offline? The
   proposal allows either; pick before implementing the tool.
2. **`merchantStem` algorithm** (P1): the exact tokenisation and stripping rules. §9.9
   names it as shared with the worklist and the egress; its definition belongs in one
   place and must be frozen before two implementations exist.
3. **`categories.yaml` predicate grammar** (P1): owned by `SPEC.md` §5.6; implement it
   as specified there rather than inventing a new one.
4. **Index schema privacy** (P2): the proposal says the schema is private and "delete
   and re-derive" is the only maintenance. Confirm that no migration tooling will be
   written even for the v2 index.
5. **Module path style** (P0): `v2/trex-core` subtree (recommended, chosen here) vs
   flat `trex-core-v2` siblings. Confirm.
6. **Cut-over window** (P1+): how long v1 stays the rollback after §16 step 6.
7. **`settlementWindowDays`, `windowDays`, `dupTolerance`, `amountTolerance`,
   `holdWindowDays`** (P1/P5): the proposal gives defaults (7, —, 0, 0, 30); confirm
   the initial values and where they live (`accounts.yaml` vs `transfers.yaml`) before
   the config loader is frozen.
