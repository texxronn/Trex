# V2-COMMITMENT-EXCLUSIONS-PLAN.md

> **Personal project, single operator, private.** `V2-PROPOSAL.md` is the specification; this file
> is the build order for one change to it. Where this file and the proposal disagree, the proposal
> wins — until the §5 amendment lands.
>
> **Status:** Stage 1 (the flag) implemented (`derive/11`); Stage 2 (the decisions) next.
> **Authority:** `V2-PROPOSAL.md` §6.2 (the action set), §6.11 (commitments); `AGENTS.md`.
> **Decision (operator, 2026-10-09):** transient one-offs are **flagged** by detection (a deviation
> check), never silently dropped; a person **excludes** a specific fact from a specific commitment
> with a decision (`EXCLUDE_COMMITMENT`, undone by `INCLUDE_COMMITMENT` or `REVOKE`).

---

## 1. The change, in one paragraph

A commitment's series is regular money, but a stem can carry one-off movements — an NRMA claim, a
payroll bonus, a UBS distribution. Detection learns to **flag** them: an interior occurrence whose
magnitude is at least double (or at most half) its predecessor's and whose successor returns to the
predecessor's level within 10% is a transient one-off, counted separately on the row. Detection
does **not** remove it — the person decides whether it matters. When it does, the person excludes
that fact from that commitment with `EXCLUDE_COMMITMENT` (family inverse `INCLUDE_COMMITMENT`);
an excluded fact is never claimed by the commitment (rule or pin), so it shapes neither the
occurrences nor the cost, it remains a fact and remains visible (marked) in the menu, and the
ledger stops chipping it.

## 2. Why: the measured cases

| Series | Regular | One-off | Damage as built |
|---|---|---|---|
| NRMA LTD | 33 × ~$10.49–11.65 | 2025-10-03 **−$190.40** (16.7×) | an occurrence, two broken gaps (regularity 0.94), two spike "steps" — the registry read *current from −$190.40* |
| JPM ADMIN SERV A PAYROLL | 11 × ~$13,3xx | 2026-01-29 **+$37,100** (2.7×) | counted in count/span/cost; a phantom step |
| UBS AG | 29 × ~$0.99–1.08M | 2020-02-20 **+$2.12M**, 2021-02-25 **+$3.13M** | both in the series and the step timeline |

The operator's rule: flag, don't guess; exclude by decision.

## 3. Detection flag (Stage A)

- **Definition.** In `Commitments.detect`, after same-day collapse and refund netting, an interior
  occurrence `i` is a **one-off** when
  `|a_i| >= 2 × |a_{i-1}|` or `|a_i| <= |a_{i-1}| / 2`, **and** `|a_{i+1}|` is within 10% of
  `|a_{i-1}|`. (Measured: the three cases above are 16.7×, 2.7×, 2.1×/3.1×, and all return within
  2%.) A lasting change stays a step; a transient one is flagged.
- **Flagging only.** Occurrences, gaps, regularity, steps and cost are unchanged; the candidate row
  gains `outliers` (an int, 0 for declared rows) and the series line shows `+n one-off(s)`.
- The flag is a deviation check, not a decision: nothing is excluded without a person.
- `deriveVersion` bumps to `derive/11`.

## 4. The decisions (Stage B)

- **Wire.** Two actions join §6.2:
  - `EXCLUDE_COMMITMENT` — `{commitmentId, externalIds[], comment?}`: these facts are not part of
    this commitment.
  - `INCLUDE_COMMITMENT` — `{commitmentId, externalIds[]}`: family inverse; removes the exclusion.
- **Validation.** The sequencer requires a declared commitment for `EXCLUDE_COMMITMENT` (like
  `PIN_COMMITMENT`); fact ids are required and unresolved ids are an ineffective decision, visible.
  The hub prechecks the same shape.
- **Fold.** Effective decisions in `n` order; ids resolve through the supersession map. Exclusions
  are a latest-wins set of `(commitmentId, factId)` pairs; `INCLUDE` removes a pair.
- **Matcher.** An excluded pair is never claimed: a pin that is excluded falls through to the
  rules; a rule scan skips a commitment that excludes the fact. The occurrence set, cost, current/
  previous, arrears and the ledger chip all follow — an excluded fact contributes nothing and the
  pointer stays in the log.
- **Read model.** A derived table `commitment_exclusion(commitment_id, external_id, decision_n)`
  (disposable, `PRIMARY KEY (commitment_id, external_id)`) carries the effective set so the hub's
  activity read can mark excluded rows (`excluded: true`), and the menu can offer
  **Exclude**/**Include** per transaction on declared rows.
- **UI.** The commitment menu's transaction table gains a trailing action per row: `Exclude` on a
  live row, `Include` on an excluded one (dimmed, struck through); the click posts the decision,
  re-fetches the activity and refreshes the registry. Candidate rows have no exclude action (no
  commitment exists yet).

## 5. Invariant check / amendments

- The log gains two actions and nothing else; decisions win; nothing is deleted (REVOKE or the
  family inverse); the derived tables stay disposable; `statehash` unchanged; `deriveVersion` bumps.
- Proposal §6.2 action table and §6.11 gain the pair; the plan's §2.6 curation list is extended.
- An exclusion is per (commitment, fact): it never touches the fact's identity, category, pairing
  or another commitment (a fact excluded from one commitment can still match another).

## 6. Blast radius

| Area | Files |
|---|---|
| core | `Commitments.java` (flag), `Commitment.java` (outliers), `Decision.java` (two records), `LogCodec`, `Derive.java` (fold + matcher inputs), `CommitmentMatcher.java` (exclusion-aware claim), `CommitmentExclusion.java` (new) |
| sequencer | `Action.java`, `Sequencer.java` (construction + validation) |
| index | `schema.sql` (`outliers` column, `commitment_exclusion` table), `IndexSchema` (guard), `Sql`, `Indexer` |
| hub | `HubSql`/`HubQueries` (read the set, mark activity), `CommitmentJson` (`outliers`), `ActivityJson` (`excluded`), prechecks, `api.js`/`decisions.js` |
| web | `commitment.js` (row toggle, series line), `expected.js` (series line), `review.js` maybe |
| docs | proposal §6.2/§6.11, SPEC, CHANGELOG, this plan, `V2-EXPECTED-UX-PLAN.md` |

## 7. Stages

- **Stage 0 — this plan.** One docs PR.
- **Stage 1 — the flag.** Detection marks transient one-offs; `outliers` end-to-end (schema, JSON,
  series line); tests for NRMA/JPM/UBS shapes and a lasting step (not flagged); `derive/11`.
- **Stage 2 — the decisions.** `EXCLUDE_COMMITMENT`/`INCLUDE_COMMITMENT` from wire to UI, the
  `commitment_exclusion` table, exclusion-aware matching, menu toggle; tests (excluded fact never
  attaches; pin falls through; INCLUDE restores; ineffective on retirements/unknowns).
- **Stage 3 — prove it.** Rebuild the dev index; the three candidates flag their one-offs; exclude
  NRMA's −$190.40 after declaring and the series reads clean; `trex verify` green.

## 8. Acceptance

- The three candidates show `+1 one-off` / `+2 one-offs`; their regular steps and current/previous
  are unchanged until a person excludes.
- After declaring NRMA and excluding the 2025-10-03 fact: the occurrence history is regular, the
  cost/current timeline is clean, the excluded fact is visible as excluded, the chip is gone for it,
  and `REVOKE`/`INCLUDE` restores everything.
- The last two UBS one-offs do not attach; a fact excluded from one commitment can still match
  another; unknown/retired targets are ineffective and visible.
- `trex verify`: rebuild ≡ incremental; reconciliation green.

## 9. Decisions taken (operator, 2026-10-09)

1. Auto outliers **flag only**; nothing is dropped without a decision.
2. Exclusion is a **decision pair** (`EXCLUDE_COMMITMENT` / `INCLUDE_COMMITMENT`), per
   (commitment, fact), reversible.
3. Plan first, then build in two stages.

## 10. Rollback

Revert the stage PRs. Exclusions are derived from decisions, so reverting the code leaves any
exclusion lines inert but harmless; the log itself is never rewritten.
