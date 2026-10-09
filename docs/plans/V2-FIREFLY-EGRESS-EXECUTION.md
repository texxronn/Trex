# V2-FIREFLY-EGRESS-EXECUTION.md

**Status:** archived (2026-10-09) — Stage 6 has landed, so this is the dispatch record, not a live
brief. Companion to `V2-FIREFLY-EGRESS-PLAN.md` (the build
order — the *what*) and `docs/reviews/V2-FIREFLY-EGRESS-REVIEW.md` (the review — R1–R8, V1–V7).
This file is the dispatch layer: who runs what, in what order, and how the work is accepted.
`V2-SPEC.md` remains the specification; `AGENTS.md` binds every worker. It archives alongside the
plan, now that Stage 6 has landed.

## 1. Start here (new session)

1. Read, in order: `AGENTS.md`; `V2-SPEC.md` §4, §6.3, §6.7, §11; this file;
   `V2-FIREFLY-EGRESS-PLAN.md` (your stage's tasks only);
   `docs/reviews/V2-FIREFLY-EGRESS-REVIEW.md` (the R/V rows that touch your stage).
2. Take the next dispatchable task in §3. Do not skip ahead. Code stages wait only on Stage 0's
   answers (A1–A8 in the plan §3.2) and the operator's D1 types.
3. Evidence or it did not happen (§2.3). Never bend an invariant, never weaken a test, never
   merge a PR.
4. If the tree is dirty with this file or the plan's execution notes, commit them on the stage
   branch first (one small commit) or in their own PR; never build on a dirty tree.

**Kickoff prompt to paste into the new session:**

```text
Execute V2-FIREFLY-EGRESS-EXECUTION.md. Read AGENTS.md, V2-SPEC.md §4/§6.3/§6.7/§11,
V2-FIREFLY-EGRESS-PLAN.md and docs/reviews/V2-FIREFLY-EGRESS-REVIEW.md first.
Start at Stage <N>. Work only the next dispatchable task; follow its steps verbatim
(failing test -> implement -> module tests -> mvn -q test -> commit). Return the evidence
block from §2.3. Do not merge; open the stage PR when its tasks are green.
```

## 2. Worker protocol

### 2.1 What every worker is given

A brief is self-contained — subagents start with fresh context, so never say "as discussed":
- the stage branch (already named in the plan, §3 of this file);
- the exact task heading(s) in `V2-FIREFLY-EGRESS-PLAN.md` (paste them into the brief);
- the acceptance tests by name (the plan's Step 1 blocks);
- the review rows that constrain the task (R/V ids);
- the return format (§2.3).

### 2.2 Steps (enforced)

1. Write the failing tests exactly as the task specifies; run them; capture the failure output.
2. Implement per the task's Files/Interfaces; run the module tests; then `mvn -q test` from the
   root (the plan's Global Constraints carry the `JAVA_HOME`).
3. Commit per the task's commit step. Small commits; the tree is buildable after each.
4. Update §11.1 exactly where the task says (drop that task's word from the not-yet-built
   sentence).
5. Stop and report (the `AGENTS.md` rule) if the code and the plan disagree — propose the smallest
   resolution; never choose silently.

### 2.3 Return format

```text
TASK <stage.task> — <green | blocked | deviation>
commands:
  <command> -> <observed result, e.g. "Tests run: 12, Failures: 0">
files:
  <path> (added|modified)
tests:
  <the acceptance test names that now pass>
deviation:
  <none | what, why, the smallest resolution proposed>
next:
  <the next task in §3>
```

## 3. Dispatch map

| Stage | Tasks, in order | Parallel shape | Branch |
|---|---|---|---|
| 0 | 0.1 | operator only — no subagent | n/a |
| 1 | 1.1 | one worker | `docs/firefly-egress-spec` |
| 2 | 2.1, 2.2 | 2.1 ∥ 2.2 (disjoint modules); serial is fine | `fix/firefly-clearing-units` |
| 3 | 3.1 ∥ 3.3; 3.2 → 3.4 | 3.1 and 3.3 disjoint; 3.2 and 3.4 share `FireflyClient.java` | `fix/firefly-egress-guards` |
| 4 | 4.1 ∥ 4.2; 4.3 → 4.4 | 4.1 and 4.2 disjoint; 4.3 then 4.4 | `fix/firefly-egress-convergence` |
| 5 | 5.1 → 5.2 → 5.3 | serial (5.1's `HubUnit` unblocks compilation) | `fix/firefly-egress-rekey` |
| 6 | 6.1 | one worker, after everything | `docs/firefly-egress-archive` |

### Stage 0 — measurements (operator only)

Run plan Task 0.1 (the spike against the dev Firefly; the token stays in your shell). Record A1–A8
in the plan §3.2. A1 decides D3 (re-key vs report), A2 decides Stage 4's convergence, A4 decides
`applyContent`, A5 decides the date handling. **Nothing in Stages 4–5 is dispatchable until §3.2 is
filled.**

### Stage 1 — the spec and the process rule (one worker)

Task 1.1 exactly. Acceptance: the §11.1 block lands (including the Firefly-side-edits paragraph and
the Cost sentence), the not-yet-built sentence is appended, the `AGENTS.md` line is added. Then the
stage PR `docs/firefly-egress-spec`; the operator merges.

### Stage 2 — clearing transfers (one worker; two if wanted)

Tasks 2.1 (hub) then 2.2 (config + egress test + §11.1). They are disjoint — two workers in
separate worktrees, or one worker serially. Acceptance:
`aClearingTransferIsOneUnitBetweenTheRealAccountAndTheClearingAccount`,
`anUnmappedClearingAccountStopsBeforeAnyWrite`; the existing
`theUnitSetIsTransfersPlusPostedExternalTransactionsOnly` stays green (if it now throws, stop and
report — an existing fixture had a dangling unit). Operator after the merge: add the `firefly.yaml`
block; on the dev stack `--create-missing-accounts --plan`, review, `--apply`.

### Stage 3 — guards (two workers max)

3.1 (`AccountChecks`, dist) and 3.3 (`Projection.tags`) are disjoint → parallel. 3.2 (`isOurs`) and
3.4 (delete-404) both edit `FireflyClient.java` → one after the other, or one worker does both.
Acceptance: `agreementIsSilent`, `aWrongTypeAndAWrongCurrencyAreBothNamed`,
`aForeignGroupWithAnExternalIdIsNeverOurs`, `tagsKeepYoursAndReplaceOurs`,
`aRetagKeepsTagsYouAddedInFirefly`, `deletingAGroupThatIsAlreadyGoneIsNotAnError`; drop
"ownership" from the not-yet-built sentence.

### Stage 4 — convergence (gate: Stage 0 A2/A4/A5 recorded)

4.1 (`Content`, `Posting.split`, `AccountMap.ids`) and 4.2 (the fake's echo) are disjoint →
parallel; then 4.3 (`converge`, plan loop, state, named stop) and 4.4 (`--validate`). Acceptance:
the Content tests (`whatWePostAndWhatFireflyEchoesHaveTheSameFingerprint`,
`anOwnLiabilityOnTheFarSideComparesById`, `amountsCompareExactlySoASubCentEditIsDrift`,
`handSplitSumsRoundInsteadOfThrowing`, `aNoonPostingAndItsEchoAreTheSameDay`,
`onlyFp1HashesAreVerified`); the `FireflyEgressTest` additions (`aRestatedAmountReachesFirefly`,
`verifyFindsContentDriftThatTheStateDoesNotKnowAbout`,
`aHandSplitGroupIsNeverRewrittenAndVerifiesClean`, `oldStateHashesAreCheckedOnceWithoutWrites`,
`aDuplicateCreateConvergesTheExistingGroup`, and the deleted-group named-`Refused` test); and
ValidateTest's violation classes plus the quiet cases (V1/V2). D8 applies: the Step 6 live check is
the throwaway dev instance only.

### Stage 5 — re-key (gate: Stage 0 A1)

Strictly serial: 5.1 (hub legs + resolved map, egress mirror, fakes), 5.2 (notes legs + refresh),
5.3 (`Successors` + `run()` + the R3 identity branch + the V4/V5 paths). Acceptance:
`theUnitsCarryTheSupersessionMapAndTransferLegs`, `notesReplaceOnlyOurFirstLine`,
`legsAreReadFromTheFirstNotesLine`, all of `SuccessorsTest`,
`aSupersededTransferLegRekeysTheGroup`, `anUnpairIsReportedAsAReplacementNotRekeyed`,
`anOrphanDeletedInFireflyIsGoneNotAnAbort`,
`aSupersededUnitWhoseGroupLostItsTagIsCreatedNotStuck`,
`aSupersededHandSplitTransferRekeysItsIdentity`. Then remove the not-yet-built sentence entirely.

### Stage 6 — operations and archive (one worker)

Task 6.1: `docs/DEPLOYMENTS.md` (the refeed doctrine and its cost, the D9 forcing function, the
`--validate` schedule), the `docs/V2-PARITY.md` delta, the CHANGELOG, and moving the plan plus this
file to `docs/plans/`. The operator reviews the DEPLOYMENTS text.

## 4. Reviewer gate (after every stage, before the PR)

Dispatch an `architect` subagent (read-only, never edits) with:

```text
Review the diff of <branch> against master against V2-FIREFLY-EGRESS-PLAN.md §<stage tasks>,
docs/reviews/V2-FIREFLY-EGRESS-REVIEW.md (the R/V rows for this stage) and the AGENTS.md
invariants. Check: every acceptance test exists with the plan's name and asserts what the plan
says; no invariant bent; interfaces match the plan's declarations; the §11.1 amendment matches
the code; no new dependency; no run/ data touched. Return findings by severity with file:line,
or "clean".
```

Fix findings before opening the PR; re-run `mvn -q test`. The worker opens the PR; the operator
merges on GitHub and updates local `master` by fetch.

## 5. Operator-only actions

- Stage 0 spike and the §3.2 records.
- The clearing-account types (D1) before Stage 2's config block lands.
- Dev-stack account creation (Task 2.2 Step 3) and the Stage 4 Step 6 live check (throwaway only).
- The first apply on the refed keeper (D8) after Stage 5.
- Merge every PR; local `master` by fetch, never by advancing the tip.

## 6. Parallelism rules

- One worktree, one writer. Parallel tasks get separate worktrees and merge into the stage branch
  before the stage PR, or simply run serially — every stage is sized for one session.
- Shared files are the constraint: `FireflyClient.java` (3.2, 3.4, 4.3, 5.2), `FireflyEgress.java`
  (3.3, 4.3, 5.2, 5.3), `Projection.java` (3.3, 4.1, 5.2). Never two writers on one file.
- Never two workers on one branch at once.

## 7. Definition of done (per stage)

- the acceptance tests exist by name and pass; `mvn -q test` is green from the root;
- commits per the plan; the task checkboxes ticked in the plan;
- the §11.1 amendment in the same PR; the reviewer gate is clean;
- the PR is opened with the plan's commit-message style; a worker never merges.

## 8. Environment

This project's sessions provide `subagent` with `general` (implementation), `architect` (read-only
review) and `explore` (lookups). The `superpowers:*` skills named in the plan's header are not
installed here; this file is their replacement — one brief per task, one reviewer gate per stage.
A fresh subagent has no memory of prior sessions: paste the brief, never point at "the previous
conversation".
