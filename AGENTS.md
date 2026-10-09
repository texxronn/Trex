# trex implementation instructions

## Authority

`V2-SPEC.md` is the authoritative specification of the system (operator decision, 2026-10-09).
`V2-PROPOSAL.md` is frozen: the history of intent and the rationale — read it for *why*, never for
*what*. The `V2-*-PLAN.md` files are build records; where one differs from the spec, the spec wins.
`docs/plans/V2-IMPLEMENTATION-PLAN.md` is the original build order, the acceptance tests and the
fixtures (archived with the other built plans in `docs/plans/`).
`AGENTS.md` (this file) carries the invariants that never move.
The v1 tree and its documents (`SPEC.md`, `DECISIONS.md`, `V1-CLAUDE.md`,
`SPEC-REVIEW.md`) are archived in the sibling project `../TrexV1` — **reference only**:
read them if useful, do not treat them as the target, and do not migrate from them.

Do not invent or silently change architecture, invariants, identity rules, log
semantics, state semantics, or API contracts.

If implementation appears to conflict with the spec:
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
- Maven multi-module project as laid out in `docs/plans/V2-IMPLEMENTATION-PLAN.md` §2
- JDK-only unless the spec or the proposal explicitly permits a dependency
- No frameworks unless specified; no Lombok, no Spring, no Kafka
- No database in `trex-core`; keep it pure and deterministic
- The v1 tree lives in `../TrexV1`: reference only, do not import its classes, do not edit it

## Development style

- Build in the stage order of the current plan (`V2-*-PLAN.md`); a change to behaviour updates
  `V2-SPEC.md` in the same PR.
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
- **Never fast-forward or push directly to `master`.** Land work through a GitHub pull request and
  merge it on GitHub. The local `master` is then updated by fetching that merge, not by advancing
  the branch tip locally.
