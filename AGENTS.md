# trex implementation instructions

## Authority

`V2-PROPOSAL.md` is the authoritative specification for the system being built.
`V2-IMPLEMENTATION-PLAN.md` is the build order, the acceptance tests and the fixtures.
`AGENTS.md` (this file) carries the invariants that never move.
`docs/SPEC.md`, `docs/DECISIONS.md` and `docs/V1-CLAUDE.md` describe v1 and are
**reference only** — read them, do not treat them as the target, and do not migrate from
them.

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
- The log holds **facts** (what a source said) and **decisions** (what a person
  concluded). Nothing else. The writer never interprets — no matching, no state, no
  category, no duplicate flags in `trex-sequencer`.
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
- The v1 tree is reference material: do not modify it, do not import its classes

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
