# trex implementation instructions

## Authority

`V2-PROPOSAL.md` is the authoritative specification for the system being built.
`V2-IMPLEMENTATION-PLAN.md` is the build order, the acceptance tests and the fixtures.
`AGENTS.md` (this file) carries the invariants that never move.
The v1 tree and its documents (`SPEC.md`, `DECISIONS.md`, `V1-CLAUDE.md`,
`SPEC-REVIEW.md`) are archived in the sibling project `../TrexV1` — **reference only**:
read them if useful, do not treat them as the target, and do not migrate from them.

Do not invent or silently change architecture, invariants, identity rules, log
semantics, state semantics, or API contracts.

If implementation appears to conflict with the proposal:
1. stop
2. explain the conflict
3. propose the smallest resolution
4. do not silently choose one

This is a private, single-operator project; nothing here is a public contract. A breaking
change to the API, the config or the line format is a refactor. Only `externalId` and the
log's history are permanent.

## Invariants (never bend these to make a test pass)

- `derive()` is pure: same inputs, same output. `asOf` is an explicit input. No ambient
  clock, no I/O, no unordered iteration.
- The log holds **facts** (what a source said), **decisions** (what a person concluded),
  and **ingest events** (what an ingest did). Nothing else. The writer never interprets —
  no matching, no category, no derived review flags. It does keep an identity/observation
  index (to assign `occ` and suppress identical re-observations) and emits a
  `DUPLICATE`/`FLAGGED` row result; that is bookkeeping, not semantics.
- Decisions win over derivation. Ids resolve through the supersession map before a
  decision is applied.
- Nothing is deleted. Undo is an appended `REVOKE` or a family inverse.
- Every derived table is disposable: `trex index --rebuild` reproduces it.
- One writer, one atomic append, one fsync per batch. The log is the only truth.
- No category is ever a property of a transaction.
- No rewriting `externalId`.

## Engineering

- Java 25 at `~/Tools/JDK/jdk-25.0.4.1+1/`
- Maven multi-module project as laid out in `V2-IMPLEMENTATION-PLAN.md` §2
- JDK-only unless the proposal explicitly permits a dependency
- No frameworks unless specified; no Lombok, no Spring, no Kafka
- No database in `trex-core`; keep it pure and deterministic
- The v1 tree lives in `../TrexV1`: reference only, do not import its classes, do not edit it

## Development style

- Build in the stage order of `V2-IMPLEMENTATION-PLAN.md` §3.
- Every stage must compile and have its acceptance tests green before moving on.
- Do not implement future stages early.
- Do not redesign working parts for hypothetical requirements.
- Prefer simple code over abstractions.
- Comments carry the *why* — the measured facts belong in the code that depends on them.

## Git

- Work in small commits.
- Keep the tree buildable after each commit.
- Never rewrite published history.
- Never commit the private dev journal, `run/`, or the index.
