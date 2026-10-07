# V2-IMPLEMENTATION-PLAN.md

> **Personal project, single operator, private.** The requirements here are
> self-imposed. Decisions that are taste rather than correctness may be changed freely;
> only the invariants in `AGENTS.md` and `V2-PROPOSAL.md` are not to be bent.

**How to build `V2-PROPOSAL.md`.** This is the build order, the module layout, the
acceptance tests, the working rules and the fixtures. It is written to be executed by
an agent (human or LLM) with no further design decisions: where a decision is still
open it is listed in §12 — and §12 distinguishes what must be asked before guessing
from what a sensible default may settle.

Read in this order: `AGENTS.md` → `V2-PROPOSAL.md` (the spec) → this file. Where this
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

The v2 modules are `trex-v2-*`. v1 is archived in the sibling project **`../TrexV1`** and is
never imported (AGENTS.md); its documents travel with it. The active tree:

```
Trex/
  pom.xml                     # reactor: the trex-v2-* modules
  AGENTS.md                   # instructions for agents (the active file)
  V2-PROPOSAL.md              # the specification
  V2-IMPLEMENTATION-PLAN.md   # this file
  trex-v2-core/ trex-v2-log/ trex-v2-index/ trex-v2-sequencer/ trex-v2-hub/
  trex-v2-egress/ trex-v2-ingest/ trex-v2-runner/ trex-v2-dist/
  deploy/
    config/                   # the tuned rules (§1.2) — commit, never rewrite by hand
    v2/                       # the v2 compose set, dev.sh and deployment config (§1.3)
  docs/                       # V2-PARITY.md, RELEASE.md, lifecycles
```

**Rules.**

- The rebuilt modules are the `trex-v2-*` tree. A stage replaces behaviour inside them; the
  module boundary is the code boundary, and `trex-v2-dist` shades them into one jar.
- `trex-v2-core` is pure: no I/O, no clock, no Jackson annotations leaking into decision
  logic. It contains the model, identity, `derive()` and the categoriser — nothing else.
- v1 (`../TrexV1`) is read-only reference: read it, never import it, never edit it from here.

### 1.1 What v1 is for

v1 (in `../TrexV1`) is the executable record of behaviour the proposal describes but does not
spell out: `clean`, the matcher tiers, the categoriser evaluator, the follower's exactly-once
offset, the framing/recovery path. When rebuilding one of those, read the v1 implementation,
restate the rule in the proposal (or in the module's javadoc), then implement it. Do not port
v1 code mechanically — the proposal is the contract, and the data shapes have changed.

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

The real statements and journal are private and **are not in the repository**. Everything
fixture-dependent must work without them.

The v2 dev harness is `deploy/v2/dev.sh` (`build`, `up`, `down`, `reset`, `ingest [DIR]`),
backed by `deploy/v2/compose.yml` + `compose.dev.yml`. `ingest` defaults to
`~/Downloads/Statements/Statements_CSV` and drives `deploy/v2/ingest-all.sh`.

- **The fixture contract.** `-Dtrex.statements.dir=<dir>` (or `$TREX_STATEMENTS_DIR`), else
  `~/Downloads/Statements/Statements_CSV`; an optional manifest via
  `-Dtrex.e2e.manifest=<file>` (or `$TREX_E2E_MANIFEST`).
- **Tests.** Fixture-dependent tests carry `@Tag("fixture")` and are skipped with a printed
  reason when no fixture is present, so `mvn verify` is green on a fresh clone. Committed
  synthetic fixtures (`deploy/v2/statements.yaml`, `trex-v2-*/src/test/resources`) cover the
  logic; the private statements cover "does the real history behave".
- **Never commit statements, journals or the index, and never copy them into `target/`**
  where they can leak into an artifact. `.gitignore` covers `statements/`, `*.local.yaml`,
  `*.statements.yaml`, `run/` and `index/`.

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
7. **One-shot importer** (`trex index --import-dev`, or the dev harness calling it): read a **v1-format journal file** and emit v2 facts + decisions through the
   sequencer's API. This is a *dev tool*, not a migration path: it exists so the
   private journal can seed the new system, and it is the only v1-format reader in the
   tree. Mapping is §16's table, but the "latest line wins / drop derived fields" rule
   applies to a journal that is already the operator's history.
8. `deploy/dev/`: `seed.sh`, `reset.sh`, the git-ignored `journal/` path, and the
   committed `samples/`. (Later rebuilt as `deploy/v2/dev.sh`; the v1-era harness is
   archived in `../TrexV1`.)

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

### P5 — Roles, noop, and the balance check (§6.9)

**Work**: `profiles.yaml` (account-scoped rules) and its loader; the role derivation in
`derive()` (`transaction` | `noop`); `MARK_NOOP`/`UNMARK_NOOP` decisions with precheck and
family precedence; role-aware reconciliation over `transaction` rows with named
exclusions; fork pairing and the per-side preview (hub endpoint + Chains surface);
the `BALANCE_BREAK` review kind; the Blotter's marked-`noop` rendering. No line-format
change: roles are derived, and the decision catalogue addition is spec-only until used.

**Acceptance**: on the private fixture, the four `ing-variable-rate` annual-fee rows
resolve as a fork. `MARK_NOOP` on the leaf side → reconciled at the account's true
closing balance, with four named exclusions; on the load-bearing side → a different fork
remains (the preview was honest). A profile rule and a decision agree, and the decision
wins both ways; `UNMARK_NOOP`/`REVOKE` restores the prior state; `trex index --rebuild`
reproduces every role; a `noop` row is absent from the projection and a previously
posted one is reported as an orphan, never deleted. `trex verify` stays green.

**Out of scope** the writer-side provisional flag (deferred, §6.9), the keep-both
formalism if unsettled, parse-time profile parameters, and any line-format change.

---

### P6 — Transfer patterns, the pool, and attribution (§9.9.C)

**Work**: per-account `transferPatterns` in `transfers.yaml` (ordered, first match wins:
the account's entries before `default`; each entry carrying a `rail` and an optional
`shape: false` for rail-only tags); the pre-filter applied per account; the pool ladder
(receipt → same-day unique → windowed unique → ambiguous); retirement of the stem tier
and `transferStem` from the matching path; rail derivation per leg (method from the
pattern, direction from the sign; `EXTERNAL` legs included) and the payer's method on the
derived transfer; the pattern edit preview (legs potted / pairs made); the pot surfacing
in the leg-resolution view. No log or line-format change; a derive-only reflow.

**Acceptance**: on the private fixture, the measured families resolve — Osko↔CBA (45),
BPAY↔BankWest (26), CBA internal (11) — and the ties behave: two $2,000 Oskos on
consecutive days pair same-day, the $600 case pairs BankWest with the same-day BPAY and
leaves the later Osko `HELD`. `AMBIGUOUS_TRANSFER` appears only where a tie survives
same-day preference. A reused receipt never shapes or pairs unrelated rows: the two
`Interest Charge` rows sharing receipt 901371 stay `EXTERNAL` and `INTEREST_PAID`, and
the two cross-era collisions (1,498-day UBS/COLES, 904-day WH SMITH/JPM) unpair, while
the genuine receipt pairs — all same-day, equal amount — keep pairing. The 19 PayID rows
(17 `Fast Transfer From … to PayID` receipts from people, 2 `Transfer To … PayID`
payments) carry the `PAYID` rail with `IN` / `OUT` from their signs and leave the pool as
rail-only, so they cannot open `UNMATCHED_LEG`; the Osko payer legs read `OSKO · OUT`, the
card payments `BPAY · OUT` (while a BPAY payment to a non-self biller carries the same
rail and stays `EXTERNAL`), and the CBA internals `BANK_TRANSFER · IN/OUT`. An Osko
payment to a non-self name (e.g. `GLEN MACHADO … to +61-412658030`) is `EXTERNAL` with
rail `OSKO · OUT` on the first derive — an expense, not a held leg — while a self Osko
whose contra is missing (the pre-CBA-history cases) stays `HELD` as a transfer awaiting
its other side. Held legs fall from 518 to ~330 (roughly 348 after pairing, less
the rail-only PayID rows); no unshaped row can ever pair; a pattern edit reflows without
re-ingest; `trex index --rebuild` reproduces every pairing and `rail`; `trex verify`
green; `docs/V2-PARITY.md` updated for the retired stem tier.

**Out of scope** the one-sided policy (NAB, pre-history card payments), the
pattern-discovery suggestion loop, and any change to receipts or pending settlement.

---

### P7 — Clearing accounts (§6.10)

**Work**: `balanceSource: clearing` with `closingBalance` / `closedAt`; the backwards-computed
opening in `Opening` and a `CLEARING` reconcile status; the `clearing:` pattern action and its
direct pairing (one real leg + an account side in the transfer/unit shape); egress provisioning
of the clearing Firefly account with the computed opening and a verify check; the Accounts and
reconcile rendering of the computed opening. No log or line-format change — a clearing account
holds no facts.

**Acceptance**: register `westpac-card`, `nab-fixed` and `nab-offset` with closing 0. The 86
Westpac legs, 40 fixed legs and 33 offset legs pair as clearing transfers — carrying rails and
correct direction, leaving the review queue, neither side a unit of its own — and each account's
derived balance lands on 0: Westpac computed opening −$78,092.02, NAB fixed −$31,800.00, NAB
offset −$174,200.00. Reconcile reports `CLEARING`, never `BROKEN`. The egress plan posts the
transfers and provisions the openings so Firefly's balances land on 0, and `verify` is green.
Removing a `clearing:` line and ingesting real statements re-pairs against the real legs with no
leftover synthetic side.

**Out of scope** interest vs principal splitting without statements, and any attempt to invent
facts for the missing history.

---

### P8 — Decision export and replay (§14.1)

**Work**: `trex decisions export` (a consistent journal prefix → neutral JSONL, each decision
carrying its `factsBefore` watermark, plus a manifest: facts head, counts, config revision)
and `trex decisions replay` (source-order posting gated on the watermark; skip-if-identical;
`REVOKE` `n`-remapping; fail-fast naming the offending decision; `--dry-run` foreign-key
check; incremental re-export); the `decisions` runner job (`mode = export | replay`) in the
JobCatalogue and the Jobs view; export files treated as private, since comments may quote
descriptions.

**Acceptance**: round-trip on a fixture — seed two sequencers from the same statements,
export the first's decisions, replay onto the second, and the derived state (legs, transfer
pairs, categories, `user_ack`, roles) is identical; a `REVOKE` remaps to the new target `n`;
replaying the same file again skips every decision as already-identical; a decision whose
`factsBefore` exceeds the target's fact count is refused with that decision named (and
`--force` is the explicit override); a `DISMISS` followed by a newer fact re-opens
identically on the target, proving the interleaving survived; a second export after further
review replays only the new decisions; a decision naming an unknown `externalId` fails with
that decision named and nothing after it posted; a config revision mismatch is reported from
the manifest before any post; `trex verify` is green on the target afterwards.

**Out of scope** replaying facts or ingest events, cross-parser migration, and scheduling
replay automatically.

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

## 12. Open decisions

**Must ask before guessing** — a wrong guess corrupts data or the log's meaning:

1. **`users.yaml` identities** (P0): the real user ids and cadences — the sequencer
   refuses a decision naming an unknown user, and the id is stamped into decisions
   forever. Do not invent one.
2. **The dev fixture path** (P0): where the private journal lives and in which format.
   `TREX_DEV_FIXTURE` or the v1 dev harness's `journal/` (archived in `../TrexV1`), v1-format, imported by the P0 dev tool
   — confirm before `seed.sh` is written, because everything acceptance-tested on real
   history depends on it.
3. **`merchantStem` definition** (P0): tokenisation and stripping rules, frozen before
   two implementations exist and before the tuned rules are validated against it. Read
   v1's `trex.category.Merchant.stem` and restate it in the proposal.
4. **The importer's treatment of v1 decisions** (P0): the private journal's state
   transitions, `DISMISS_DUP`s and TRANSFER lines map by §16's table. Confirm that
   losing nothing beyond what `deploy/config/pins.yaml` already encodes is acceptable —
   this decides what the imported history means.

**Sensible default, change freely** — taste or tuning, not correctness:

5. **The categoriser grammar** (P0): `deploy/config/categories.yaml` uses the SPEC §5.6
   `when` tree. Implement that grammar; a new one would be a rewrite of the tuned rules.
6. **Index schema privacy** (P0): the default is "delete and re-derive only" with no
   migration tooling, even in development. Cheap to hold; cheap to break later if it
   becomes annoying.
7. **`settlementWindowDays` / `windowDays` / `dupTolerance` / `amountTolerance` /
   `holdWindowDays` defaults** (P0): 7 / 4 (already in config) / 0 / 0 / 30. Start
   there and tune against the dev fixture, as `windowDays` was tuned.
8. **Module shape and cut-over details** (P0+): the in-place rebuild and the v1 tree's
   fate are operator preferences, not invariants.
