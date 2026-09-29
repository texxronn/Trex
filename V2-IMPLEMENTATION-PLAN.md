# V2-IMPLEMENTATION-PLAN.md

**How to build `V2-PROPOSAL.md`.** This is the build order, the module layout, the
acceptance tests, the working rules and the fixtures. It is written to be executed by
an agent (human or LLM) with no further design decisions: where a decision is still
open it is listed in §12 and must be asked, not invented.

Read in this order: `CLAUDE.md` → `V2-PROPOSAL.md` (the spec) → this file. Where this
file and the proposal disagree, the proposal wins.

---

## 0. Ground rules

1. **The proposal is the specification.** Do not invent architecture, invariants,
   identity rules, log semantics or workflow. If something is missing, stop and ask
   (§12) — do not choose silently.
2. **Never put a conclusion in the writer.** `trex-sequencer` appends facts and
   decisions. Matching, state, categories and duplicate flags are derived. If a change
   makes the writer interpret, it is wrong by construction.
3. **`derive()` is pure.** Same inputs → same output. No I/O, no ambient clock, no
   env, no randomness, no iteration over unordered maps. `asOf` is a parameter.
4. **Every derived table is disposable.** No build step may create state that
   `trex index --rebuild` cannot reproduce (§4 rebuild test).
5. **v1 is reference material, not a dependency.** The v1 tree stays on disk and
   builds, but the new system never calls it, never mirrors its journal, and never
   migrates it. Nothing in a v2 module imports a v1 class.
6. **Test before moving on.** Every stage ends with compiling code and green tests. A
   stage is done when its acceptance tests (§3) pass, not when it compiles.
7. **Never weaken an invariant to make a test pass.** If a test cannot pass without
   bending a rule, stop and ask — the rule or the test is wrong.
8. **No new dependencies** beyond `SPEC.md` §1 provides and the proposal names
   (`sqlite-jdbc`, Jackson, picocli, SLF4J). No ORM, no Spring, no broker.
9. **Small commits**, tree buildable after each, no rewriting published history.
10. **The *why* goes in the code.** The measured facts — the 21-of-30 transfer
    rejections, the stale `category_id`, the preflight that saved 899 posts, the
    $13,661 opening coin toss — belong in the code that depends on them.

---

## 1. Repository layout

Same repository, same Maven reactor. The new system is built **in place**: the module
names in §2 are the existing names, rewritten. v1's tree is retained as the reference
implementation until the operator says otherwise (§11.5).

```
Trex/
  pom.xml                     # reactor
  SPEC.md  DECISIONS.md       # v1 reference — keep, do not delete
  V2-PROPOSAL.md              # the specification
  V2-IMPLEMENTATION-PLAN.md   # this file
  CLAUDE.md
  trex-core/ trex-journal/ trex-sequencer/ trex-ingest/ trex-ws/ trex-web/
  trex-egress/                # v1 — reference only; not called by the new system
  deploy/
    config/                   # the tuned rules (§1.2) — commit, never rewrite by hand
    dev/                      # dev harness; journal is operator-supplied (§1.3)
  docs/
    v2-lifecycles.html
```

**Rules.**

- The rebuilt modules keep their names and are replaced one stage at a time. Until a
  module is rebuilt it is v1; after, it is v2. A commit that rebuilds a module says so.
- The rebuilt `trex-core` is pure: no I/O, no clock, no Jackson annotations leaking
  into decision logic. `trex-core` still contains the model, identity, `derive()` and
  the categoriser — nothing else.
- **No `v2/` subtree.** The tree is the target tree; only the code inside it changes.

### 1.1 What v1 is for

v1 is the executable record of behaviour the proposal describes but does not spell
out: `clean`, the matcher tiers, the categoriser evaluator, the follower's
exactly-once offset, the framing/recovery path. When rebuilding one of those, read the
v1 implementation, restate the rule in the proposal (or in the module's javadoc), then
implement it. Do not port v1 code mechanically — the proposal is the contract, and the
data shapes have changed.

### 1.2 The rules are already tuned — do not rewrite them

`deploy/config/*.yaml` is the **categorisation and matching contract**: 25 declared
categories, ordered rules with comments citing measurements, `transfers.yaml` with the
transfer allowlist and `windowDays: 4`, and `pins.yaml`. The rebuild consumes these
files as-is. If a rebuild cannot reproduce their effect, the rebuild is wrong — not the
file.

The v2 config gains what the proposal adds and nothing else:

- `accounts.yaml` — add `settlementWindowDays` per account (§9.9.D; default 7).
- `transfers.yaml` — add `dupTolerance` (default 0), `amountTolerance` (default 0),
  `holdWindowDays` (default 30).
- `users.yaml` — **new**, non-empty, with at least one user (`§6.6`); absence is a
  startup error.
- `refdata.yaml` — the declared category names; `categories.yaml` keeps the rules.
  During the transition both may live in one file if the loader accepts it, but the
  proposal's split is the target.

### 1.3 The dev fixture — operator-supplied, never committed

The real journal is private and **is not in the repository**. Everything
fixture-dependent must work without it.

```
deploy/dev/
  journal/            # git-ignored. Operator places the private journal here.
  config/             # the tuned rules (symlink or copy of deploy/config)
  seed.sh             # import the journal into the running system and index it
  reset.sh            # wipe run state and re-seed
  samples/            # committed synthetic fixtures: CSVs, golden files, hand-built journals
```

- **Location contract.** `TREX_DEV_FIXTURE` (a directory or a file) overrides
  `deploy/dev/journal/`. `seed.sh` reads it and does the one-shot import (§3, P0).
- **Tests.** Fixture-dependent tests carry `@Tag("fixture")` and are skipped with a
  printed reason when no fixture is present. `mvn verify` is green on a fresh clone.
  Committed synthetic fixtures cover the logic; the private journal covers "does the
  real history behave".
- **Never commit the journal, and never copy it into `target/`** where it can leak into
  an artifact. `.gitignore` covers `deploy/dev/journal/`, `run/journal/`, `index/`.

---

## 2. Modules

The target tree (same names as v1; each is rebuilt in the stage marked).

| Module | Kind | Depends on | Responsibility | Rebuilt in |
|---|---|---|---|---|
| `trex-core` | library | JDK + Jackson | `Fact`, `Decision`, `Config`, identity (`Ids`), `occ`, `clean`, `merchantStem`, `derive()` and its whole pipeline (§9.9), `stateHash` | P0 |
| `trex-log` | library | `trex-core` | framing/writer/reader, recovery, evidence store, `Json`/`Yaml` mappers, change signal | P0 |
| `trex-index` | library | `trex-core`, `trex-log`, `sqlite-jdbc` | JDBC materializer: log → mirror → derived tables (§7.2), index lock, offset, rebuild | P0 |
| `trex-sequencer` | service | `trex-core`, `trex-log` | the only writer: `POST /facts`, `POST /decisions`, `GET /head`, recovery, fsync | P0 |
| `trex-hub` | service | `trex-core`, `trex-log`, `trex-index` | owns the index; blotter API + UI; rule writer; decision precheck + forward; SSE | P1 |
| `trex-ingest` | CLI | `trex-core`, `trex-log` | adapters → evidence + facts; whole-file validation; day-atomic batching | P4 |
| `trex-dist` | packaging | all | one shaded `trex.jar`; picocli subcommands select the role | P0 |
| `trex-egress` | CLI | `trex-core`, hub API | archive (mirror), firefly (converge) | P3 |

`trex-dist` contains no code of its own. Role selection is a subcommand plus config —
never a second artifact (§19).

---

## 3. Stage plan

Each stage names **Work**, **Acceptance**, **Out of scope**.

### P0 — The spine: model, log, sequencer, index, one jar

This is the first real stage. There is no migration and no format to preserve, so the
log is v2 from the first line.

**Work**

1. `trex-core`: `Fact`, `Decision`, `Config` records; `Ids` (identity unchanged from
   `SPEC.md` §2.4); `occ` assignment; `clean`; `merchantStem` (frozen once, §12.2).
2. `trex-log`: the v2 line kinds (§6.1, §6.2); framing reader/writer with v1's
   torn-tail recovery and offset semantics; mappers configured exactly as v1
   (`ORDER_MAP_ENTRIES_BY_KEYS` on, nulls written).
3. `trex-sequencer`: `POST /facts` with `allOrNone`, whole-observation dedup (§6.5),
   outcomes `Appended | Duplicate | Flagged | Rejected`; `POST /decisions` with every
   action in §6.2, reference-and-structure validation only (§6.8); `GET /head`. One
   batch = one atomic append + one fsync. Single-writer `FileLock` on the journal.
4. `trex-core`: `derive()` — the full §9.9 pipeline, pure, producing the §7.2
   materialisation. No writes; it returns tables.
5. `trex-index`: apply log lines and offset in one transaction; materialise level 2 by
   calling `derive()`; `trex index --rebuild` offline behind the index lock.
6. `trex-dist`: shade everything into one `trex.jar`; picocli subcommands
   (`sequencer`, `hub`, `index`, `ingest`, `egress archive`, `egress firefly`).
7. **One-shot importer** (`trex index --import-dev`, or `deploy/dev/seed.sh` calling
   it): read a **v1-format journal file** and emit v2 facts + decisions through the
   sequencer's API. This is a *dev tool*, not a migration path: it exists so the
   private journal can seed the new system, and it is the only v1-format reader in the
   tree. Mapping is §16's table, but the "latest line wins / drop derived fields" rule
   applies to a journal that is already the operator's history.
8. `deploy/dev/`: `seed.sh`, `reset.sh`, the git-ignored `journal/` path, and the
   committed `samples/`.

**Acceptance**

- §15.1 purity (an input fixture gives identical output across runs; nothing reads a
  clock), §15.2 `derive ∘ derive = derive`, §15.3 rebuild ≡ incremental at the same
  `asOf`.
- §15.4 decisions win; ids chain-resolved. §15.5 is **deleted** (no migration).
- §6.5's dedup table, row for row: re-delivery is a no-op (no line, no fsync); a
  `Flagged` fact is appended once and its review item appears only after `derive()`.
- The sequencer rejects structural faults (unknown id, unknown user, undeclared
  category, malformed payload, `REVOKE` of a missing `n`) and records semantically
  wrong but well-formed decisions.
- §15.12 one artifact, every role: `trex.jar --help` lists every subcommand; each role
  runs from the one file.
- **Fixture:** seeding the private journal produces a v2 log whose derived state
  matches what v1 reports for the same history (run v1's `trex-ws` beside it as a
  reading aid): same latest state per id, same HELD/REVIEW sets, same categories under
  the same rules, reconciliation identical. This is the one test that uses the private
  fixture; it is tagged and skipped when absent.
- `trex verify` green on the seeded fixture (framing, reconciliation over derived
  state, index equivalence).

**Out of scope** the hub, ingest adapters, evidence, feeds, pending, Firefly.

---

### P1 — Hub: the index owner, the blotter, automatic re-derive

**Work**

1. `trex-hub` owns the index (§7.4): watch the journal and config files; apply and
   re-derive on change. One writer connection, read pool, index lock, WAL,
   `busy_timeout`.
2. The blotter API and UI (§10): four modes, SQL-backed filters, saved views,
   inline actions, balance ribbon, pending lane (once P4 lands), status strip.
3. `deriveVersion`, `configRevision`, `hashVersion`; `stateHash` per §9.9.G;
   `USER_ACK` payload and validity (§9.4); per-user invalidation.
4. Reflow becomes real: a saved config change recomputes immediately (§9.3).
5. Rule editing through the hub with blast radius; `reflow --preview` served from the
   hub's index rather than a scratch journal.
6. SSE: snapshots and deltas, one instant of the journal per client (§7.4).

**Acceptance**

- §15.7 per-user ACK invalidation, per user, naming the moved rows.
- §15.17 time is an input: a later-`asOf` rebuild changes only stale/age statuses and
  every `stateHash` is identical.
- §15.6 preview diff ≡ applied result.
- §15.10 reconciliation over derived state, including `DECLARED`.
- §15.13 pins are events; §15.14 revocation is total; §15.15 retirement counts for
  nothing; §15.16 supersession never orphans a decision.
- On the private fixture: the weekly eyeball walk completes, and closing a period
  writes exactly one `USER_ACK` per period covered.

**Out of scope** the workbook, egress, evidence.

---

### P2 — The workbook

**Work**: rule lint (shadowed, never-firing, catastrophic regex, orphan pins,
redundant pins), `categories.tests.yaml` fixtures run on load and in CI, suggestions
from pins and uncategorised clusters, coverage by origin and trend, redundant-pin
cleanup offering `UNPIN`.

**Acceptance**: the uncategorised share and the pin count are reportable; a lint
finding names the offending rule or pin; fixtures fail the build on a regression;
editing a rule previews the diff before saving.

**Out of scope** anything that writes the log; suggestions are proposals.

---

### P3 — Firefly convergence (and archive)

**Work**: plan/apply/verify (§11.1); the drift taxonomy with de-projection (§11.5);
`projection_state` with `derive_version`; recovery by rebuild from Firefly;
`--remove-orphans`; account preflight; opening balances; category seeding; retry
taxonomy; progress output. `egress archive`: the byte mirror plus evidence copy.

**Acceptance**: §15.8 and §15.18–21 — after `--apply`, `--plan` empty; a hand edit
reported, never overwritten; a de-projected unit reported as an orphan, never deleted;
the unit set exact incl. attestation; the type matrix; splits and `group_title`
preserved; the stale `category_id` cleared; fail-fast names the transaction; openings
honest. A projection state deleted and rebuilt resumes at the right `n` without
re-posting.

**Out of scope** auto-deletion, hledger, any write back from Firefly.

---

### P4 — Ingest, evidence, feeds, pending

**Work**: `trex-ingest` adapters → evidence + facts; whole-file validation;
day-atomic batching; `--reparse` diff and its `SUPERSEDE`/`RETIRE` preview; feed
adapters with `source_cursor`; pending facts recorded (`observation: pending`),
correlated by `derive()`, with `SETTLE`; `AMBIGUOUS_SETTLEMENT` and `STALE_PENDING`.

**Acceptance**: §15.9 and §15.11; `trex ingest` keeps `--source-type`, whole-file
validation, day batching, gzip and exit codes 0/1/2/3/64. On the private fixture: a
re-parse of stored evidence reproduces the facts, and a parser fix flows through as a
reviewed supersede.

**Out of scope** anything the proposal parks (hledger, multi-currency logic, ML).

---

## 4. Coding standards for v2

- **Java 25**, records and sealed interfaces; exhaustive `switch` over sealed types.
- **Purity is a compile-time habit**: `derive` and everything it calls takes inputs as
  parameters. No `Instant.now()` anywhere in `trex-core`.
- **Every money value is `long` cents**; format at the edge, never with the default
  locale.
- **Every SQL statement** lives in a `.sql` file or a named constant with a comment
  naming its table. No concatenation of user input.
- **Every external call is timeout-bounded** and separates retryable from terminal.
- **Errors a human must act on name the thing**: id, account, date, unmapped ref,
  offending rule. No bare counts.
- **Log at the decision, not the loop.** A long pass prints progress, not every row.

---

## 5. What to test, and how

**Three classes every module needs:**

1. **Contract tests** — record shapes, log round-trip, SQL schema.
2. **Invariant tests** — one per §15 invariant, named after it
   (`deriveIsPure`, `decisionsWinOverReflow`, `rebuildEqualsIncremental`).
3. **Regression tests with the story in the javadoc** — every measured discovery: the
   leg double-count, the type matrix ("21 of 30"), the stale `category_id`, the
   preflight with zero writes, the 4xx-once rule, the opening derivation order.

**Fakes.** v1's test stubs are the template: a local JDK `HttpServer` reproducing the
behaviour that broke us (the stale-id resolution, the duplicate `422` naming a group).
Never stub only the happy path.

**Fixture policy** (§1.3): synthetic fixtures committed and always run; the private
journal behind `@Tag("fixture")`, skipped with a printed reason when absent.

**Gate list** — the proposal's invariants, mapped to stages:

| # | Invariant | Stage |
|---|---|---|
| 15.1 | `derive()` pure, `asOf` explicit | P0 |
| 15.2 | `derive ∘ derive = derive` | P0 |
| 15.3 | rebuild ≡ incremental at the same `asOf` | P0 |
| 15.4 | decisions win; ids chain-resolved | P0 |
| 15.6 | preview diff ≡ applied result | P1 |
| 15.7 | per-user ACK invalidation is per user | P1 |
| 15.8 | egress convergence; orphans reported | P3 |
| 15.9 | evidence re-parse reproduces facts | P4 |
| 15.10 | reconciliation over derived state, `DECLARED` | P1 |
| 15.11 | pending never lost, never counted | P4 |
| 15.12 | one artifact, every role | P0 |
| 15.13 | pins are events | P1 |
| 15.14 | revocation is total | P1 |
| 15.15 | retirement counts for nothing | P1 |
| 15.16 | supersession never orphans a decision | P1 |
| 15.17 | time is an input, not a side effect | P1 |
| 15.18 | Firefly unit set exact | P3 |
| 15.19 | Firefly type matrix; CAS retag; splits survive | P3 |
| 15.20 | fail-fast and resume | P3 |
| 15.21 | openings honest | P3 |

(15.5, migration preserves identity, is **retired**: there is no migration.)

---

## 6. Definition of done

A stage is done when **all** hold:

1. `mvn verify` is green from the reactor root, including the untouched v1 modules.
2. Its acceptance tests pass, named after the invariant where one exists.
3. `trex verify` is green on the seeded fixture (where the fixture is present).
4. The §4 rebuild test passes for anything that touched the index.
5. No v1 module was modified, and no v2 module imports a v1 class.
6. The commit message names the stage and the proposal sections it implements.
7. Any new measured fact is recorded in the code that depends on it and, if it changes
   a rule, in the proposal.

---

## 7. Operations and recovery

- **Back up exactly three things:** the v2 log, the evidence store, the config (git).
  The index, projection state, review queues and ACK copies are rebuilt, never backed
  up (§7.4).
- **The recovery drill** (`stop hub; rm index; trex index --rebuild; trex verify`) is a
  scheduled test from P1, not an emergency procedure.
- **Roles:** `trex sequencer` and `trex hub` are services; `egress firefly` is a timer
  for `--plan`/`--verify` and runs `--apply` on instruction; `ingest`, `index`,
  `reflow`, `verify`, `export` are on-demand. One jar, one image, role by command.
- **Disaster restore:** log + evidence + config → rebuild. No database restore exists
  because no database is truth.

---

## 8. Working with the agent

One commit-sized change with one purpose. Before writing code:

1. Read the proposal sections the stage cites. If a detail is absent, ask (§12).
2. Write the acceptance test first where the invariant is named.
3. Implement the smallest thing that passes it.
4. Run the full reactor build; do not move on with a red build.
5. Report what landed, what the tests prove, and what is deliberately not done.

**Do not**: add a dependency not in §0.8; put a state machine in the sequencer; write
a derived value into the log; add a per-role artifact; modify a v1 module; copy the
private journal anywhere; keep a decision in your head instead of in the code or the
proposal.

---

## 9. Performance and scale

Target is 10⁴–10⁵ journal lines. State the numbers, measure before optimising, record
the measurement where it is used.

- `derive()` full pass: **< 2 s at 10⁵ lines** on the build machine. If exceeded,
  measure per stage before adding incremental complexity.
- Index rebuild: **< 30 s at 10⁵ lines** (a `rm && rebuild` is the recovery).
- Blotter page: **< 100 ms** at 10⁵ rows for a filtered page.
- No stage holds the journal in memory twice; stream P1 where practical.

If a target is missed: is the query right, is the schema right, then does it need an
index. A cache is allowed only if it is rebuildable and its absence still gives the
right answer.

---

## 10. Explicitly not in scope

From the proposal's §19, restated:

- No database as system of record.
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

## 11. Retired, retained and deferred

1. **Retired:** §16 migration, the shadow week, rollback-by-v1, the v1-shaped mirror.
   There is no migration; the system starts at day 0 with the v2 log.
2. **Retired invariant:** §15.5 (migration preserves identity).
3. **Retained as reference:** the v1 tree, `SPEC.md`, `DECISIONS.md`. Read them; do not
   call them; do not delete them without the operator saying so.
4. **Deferred:** hledger (§11.8), multi-currency logic, ML categorisation.
5. **The v1 tree's fate** is an operator decision, not a stage in this plan.

---

## 12. Open decisions — ask, do not assume

1. **The dev fixture path.** Where the operator keeps the private journal, and
   whether `seed.sh` reads v1-format lines (then imports) or a v2-format export the
   operator produces. Recommended: `TREX_DEV_FIXTURE` or `deploy/dev/journal/`,
   v1-format, imported by the P0 dev tool.
2. **`merchantStem` definition** (P0): tokenisation and stripping rules, frozen before
   two implementations exist. Read v1's `trex.category.Merchant.stem` and restate it in
   the proposal.
3. **The categoriser grammar** (P0): `deploy/config/categories.yaml` uses the SPEC §5.6
   `when` tree. Implement that grammar; do not invent a new one.
4. **Index schema privacy** (P0): confirm no migration tooling for the index even in
   development — "delete and re-derive" only.
5. **`settlementWindowDays` / `windowDays` / `dupTolerance` / `amountTolerance` /
   `holdWindowDays` defaults** (P0): proposal defaults are 7 / 4 (already in config) /
   0 / 0 / 30. Confirm initial values before the config loader freezes.
6. **`users.yaml` identities** (P0): the real user ids and cadences — required for the
   sequencer to accept any decision.
7. **The importer's exact behaviour** (P0): when the private v1 journal has decisions
   that v2 models differently (state transitions, `DISMISS_DUP`, TRANSFER lines), the
   plan is §16's mapping; confirm it is acceptable to lose nothing that
   `deploy/config/pins.yaml` does not already encode.
