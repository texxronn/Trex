# trex implementation instructions (v1 — archived)

> **Archived.** This was the instruction file for the v1 build. It is kept because it
> states the v1 invariants plainly and `docs/SPEC.md` + `docs/DECISIONS.md` are written
> against it. It is **not** loaded by agents: the active file is `/AGENTS.md`, and the
> active specification is `/V2-PROPOSAL.md`.

## Authority

SPEC.md is the authoritative implementation specification.

Do not invent or silently change architecture, invariants, identity rules,
journal semantics, state semantics, or API contracts.

If implementation appears to conflict with SPEC.md:
1. stop
2. explain the conflict
3. propose the smallest resolution
4. do not silently choose one

## Engineering

- Java 25 at ~/Tools/JDK/jdk-25.0.4.1+1/
- Maven multi-module project exactly as specified
- JDK-only unless SPEC.md explicitly permits a dependency
- No frameworks unless specified
- No Lombok
- No Spring
- No Kafka
- No database in trex-core
- Keep trex-core pure and deterministic

## Development style

- Build bottom-up according to SPEC.md §8.
- Every stage must compile and have tests before moving on.
- Do not implement future phases early.
- Do not redesign working parts for hypothetical requirements.
- Prefer simple code over abstractions.
- Never weaken an invariant to make a test pass.

## Git

- Work in small commits.
- Keep the tree buildable after each commit.
- Never rewrite published history.
