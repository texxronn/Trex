# V2 Firefly egress — specification, plan and review

**Date:** 2026-10-09 · **Status:** working companion, for review · **R1–R8 locked 2026-10-09; resolutions corrected by the second pass V1–V7 (§3.1)**
**Authority:** `V2-SPEC.md` stays authoritative. §1 restates, field by field, the rule set
`V2-FIREFLY-EGRESS-PLAN.md` Stage 1 proposes to land as `V2-SPEC.md` §11.1, marked with what is on
`master` today and what each stage adds. §2 is the plan as proposed. §3 is the plan review of
2026-10-09 and the gaps it leaves open.

---

## 1. Specification: the Firefly egress

### 1.1 What it is

`trex egress firefly` projects trex's **resolved units** into Firefly III, one way, as a batch CLI
(started from the Jobs runner or the CLI — never a daemon). Firefly is a *view*: trex's derivation
is the truth, Firefly receives what trex concluded, and nothing of yours in Firefly is overwritten
outside the fields trex owns (§1.4).

| Mode | What it does |
|---|---|
| `--plan` | Read the hub's units and the state, print the diff (create / retag / update / check / orphan / re-key), write nothing. |
| `--apply` | Execute the diff, recording projection state as each write lands, so a rerun resumes. |
| `--verify` | Rebuild projection state from Firefly (fingerprints included), then plan; non-empty means exit 1. Timer-safe. |
| `--validate` | Read-only contract check: Firefly's inventory (untagged included) against the hub units and the recorded state. Violations — something **Firefly changed** — exit 1: `MISSING`, `UNTAGGED`, `TAMPERED`, `DRIFT`. Normal states are informational: `BEHIND` (trex moved; the next apply updates it), `ORPHAN`, `HAND_SPLIT`. Writes nothing. Timer-safe. |

Other flags: `--remove-orphans` (deletion is never automatic), `--seed-categories`,
`--create-missing-accounts`, `--print-accounts`, `--retries/--retry-base-ms/--retry-max-ms`.
The token comes from `FIREFLY_TOKEN` only — never a flag.

### 1.2 What is projected

One **unit** = one resolved transaction or one transfer row, from the hub's `/api/units`:

- posted EXTERNAL transactions and TRANSFER rows; **[built]**
- a clearing pair is one unit, carried by its real leg — the clearing side is an account, never a
  fact; **[Stage 2]**
- never a leg (a transfer and its legs double-count), never `HELD`/`REVIEW`, `PENDING`, an
  attestation (a $0 declared-balance row) or a retired fact. **[built]**

The Firefly transaction **type comes from the two mapped account kinds**, not from trex's
classification (Firefly refuses a `transfer` across the line):

| from → to | type |
|---|---|
| asset → asset, liability → liability | `transfer` |
| asset → liability (paying a card or loan) | `withdrawal` |
| liability → asset (card refund, drawing a loan) | `deposit` |

### 1.3 The posting

Every posting carries:

| field | value |
|---|---|
| `external_id` | the unit id |
| `internal_reference` | the unit's `accountRef` |
| `tags` | `trex` and `trex-category:<CATEGORY>` |
| `category_name` | always (incl. `UNCATEGORIZED`) |
| `notes` | first line `trex n=<n> rules=<configRevision>` + ` legs=<a>,<b>` on a transfer **[legs: Stage 5]**; second line the raw description, written once |
| `error_if_duplicate_hash` | `true` — a collision 422 names the existing group, so a lost state recovers |
| `apply_rules` | `false` — trex is the single classifier; budgets are Firefly's |

Amounts are exact two-decimal strings from cents; the currency is stamped. Counterparties are
merchant stems (one shop = one Firefly account).

### 1.4 Ownership

**A group is ours iff its first split carries the `trex` tag.** An `external_id` alone is never
ownership — the Data Importer or a hand entry can carry one. **[Stage 3]**

The egress owns, and only owns:

- `external_id`, the `trex` and `trex-category:*` tags, the first notes line;
- on a **single-split** group: type, date, amount, currency, source, destination, description;
- the category **only while it still equals the tag we last wrote** — if it differs, a person
  changed it, and the human wins (the tag still moves).

Everything else — other tags, budget, bill, piggy bank, splits, the rest of the notes — is returned
untouched. **A hand-split group's content is never rewritten** (its total, if the bank moved, is
reported). Every write is a read-modify-write of the whole group: return every split and
`group_title`; when a field moves by name, drop its id — Firefly resolves the id first, and echoing
a stale id is the failure that looks like success.

### 1.5 Convergence and state

`projection_state` is an accelerator, rebuildable from Firefly, recording per unit the group id, the
category last written, and a **content fingerprint** `fp1:<sha256>` over
`type|date|amount|currency|source|destination|description` (the amount exact, never rounded; the date reduced to its local day on both sides), computable both from the posting and from
Firefly's read-back. Accounts compare by resolved id when ours, by name otherwise. **[Stage 4]**

- **Plan:** a unit is *unchanged* when its category and fingerprint match the state; otherwise it
  plans a retag (category only), an update (content moved), or a check (state written before
  fingerprints — one read, no write when Firefly already matches).
- **Apply:** `converge` reads the group, writes only what moved, records the fingerprint.
- **Verify:** rebuilds state from Firefly (fingerprints included) and plans; non-empty exits 1.
  A hand-split group records `fp1:hand-split` and is never rewritten.
- A category move clears a stale `category_id`; a content move on a single split drops the ids of
  sides moved by name.
- A hand-split group's total mismatch is reported by `--validate` on every run (read-only);
  `--verify` stays quiet by design (D2).

### 1.6 Identity moves

`V2-SPEC.md` §4 mints a transfer id over its legs' **current** ids, and a `SUPERSEDE` re-mints the
superseded row — so identity must be followed, not assumed:

- a superseded unit **of the same kind** is **re-keyed in place**: same Firefly group, new
  `external_id`, new content, no delete, no double count. The old id is found through the hub's
  supersession map (EXTERNAL) or the legs in the notes (TRANSFER). **[Stage 5]**
- a unit that **changed kind** (an EXTERNAL row became a transfer leg, or a transfer unpaired) is
  reported as a pair — orphan → successors — never re-keyed across kinds. **[Stage 5, D5]**
- nothing is deleted without `--remove-orphans`; deleting a group that is already gone (404) is not
  an error. **[Stage 3]**
- an orphan whose group was deleted in Firefly is **gone** (dropped from the state, never an abort);
  an orphan whose group lost the `trex` tag is **never re-keyed into** — its successor is created and
  the group is left as yours. **[Stage 5, V4/V5]**
- a group the plan no longer knows is an orphan; it is reported, never deleted automatically.
  **[built]**

### 1.7 Accounts and configuration

`firefly.yaml` maps each account ref to a Firefly **name and type** (`asset|liability`), keyed by
name (a rebuilt instance changes every id).

- Startup stops on an unresolved name; **type and currency disagreements stop too** — the type
  decides the transaction type, and Firefly converts silently on a currency mismatch. **[Stage 3]**
- The whole unit list is checked for unmapped refs before the first write. **[built]**
- Creation is opt-in (`--create-missing-accounts`): the opening is the backward figure, a clearing
  account's computed opening, a liability seeded negative. **[built]** The clearing accounts and
  Cash (Ron) are mapped under D1/D7. **[Stage 2]**
- `--seed-categories` creates declared categories and fills empty notes only: anything you typed is
  yours. **[built]**

### 1.8 Failure and cost

- Retry 5xx, 429 and dropped connections with backoff and jitter, honouring `Retry-After`; a **4xx
  stops the pass**, naming the unit and Firefly's message; exit non-zero so a scheduled run cannot
  report success. **[built]**
- State is recorded as each write lands; a rerun resumes. A rule change re-plans changed categories
  only; a first full apply is slow (measured ~0.6 creates/s past a thousand) and safe to interrupt.
  **[built]**

---

## 2. The plan

### 2.1 Goal

Make `trex egress firefly` converge Firefly to the derivation again: every unit lands, every later
change to a unit reaches Firefly, nothing of yours in Firefly is overwritten, and the lessons are
written into the spec rather than left in commit messages.

### 2.2 Findings

| Id | Sev | Finding | Stage |
|---|---|---|---|
| F1 | High | Clearing transfers are dropped by the hub; `firefly.yaml` maps none of the seven clearing accounts. Silent. | 2 |
| F2 | High | A content change (amount, date, description) is planned as an update, but `retag()` rewrites only category and tags. | 4 |
| F3 | Medium | `--verify` rebuilds state with a blank `stateHash`; a blank hash skips the content check, hiding F2 forever. | 4 |
| F4 | Medium | A re-tag replaces the whole tag list, deleting tags you added in Firefly. | 3 |
| F5 | Medium | Rebuild treats any group with an `external_id` as ours; `--remove-orphans` could delete a foreign one. | 3 |
| F6 | Medium | Startup never compares account type or currency with Firefly. | 3 |
| F7 | Low | A `Duplicate` create records the current category without reading the group. | 4 |
| F8 | Low | Deleting an orphan that is already gone (404) throws. | 3 |
| F9 | Low | A replaced unit (orphan + successor) is not reported as a pair. | 5 |
| F10 | Low | A content-only move is counted as both a retag and an update. | 4 |
| F11 | Spec | A supersede re-mints ids: Firefly gets an orphan plus a create (a double count) until a human removes the orphan. | 5 |

### 2.3 Stages

| Stage | What | Findings | Gates |
|---|---|---|---|
| 0 | Live measurements against the dev Firefly (A1–A8: PUT behavior on `external_id`/content/type, id-vs-name, read-back formats, delete-404, account type). | — | blocks 4 and 5 |
| 1 | `V2-SPEC.md` §11 rewritten as rules; the egress-impact process rule in `AGENTS.md`. | docs | D6 |
| 2 | Hub publishes clearing transfers as units; `firefly.yaml` maps the clearing accounts + `cash-ron`. | F1 | D1, D7 |
| 3 | Startup type/currency checks; ownership = `trex` tag; tags preserved on re-tag; 404 delete is gone. | F4, F5, F6, F8 | D4 |
| 4 | Content fingerprint `fp1:`; `converge` replaces `retag`; verify rebuilds fingerprints; duplicate create converges; `--validate` (D9). | F2, F3, F7, F10, R2 | D2, D9, Stage 0 |
| 5 | Hub publishes transfer legs + supersession map; notes carry legs; superseded units re-key; replacements reported. | F9, F11 | D3, D5, Stage 0 A1 |
| 6 | Ops notes; archive the plan. | — | — |

Stages 2 and 3 are independent; 4 needs 3 (ownership helper); 5 needs 4 (`converge`).

### 2.4 Decisions

| Id | Question | Recommendation |
|---|---|---|
| D1 | How do clearing transfers land? | Map each clearing account in `firefly.yaml` (types below D1 in the plan), created with its computed opening. |
| D2 | Who wins on amount / date / description? | Trex, on a single-split group; a hand-split group is never rewritten. |
| D3 | What happens to a superseded unit? | Re-key the existing group in place (new `external_id`, new content). |
| D4 | Which Firefly groups are ours? | Those carrying the `trex` tag; an `external_id` alone is not ownership. |
| D5 | A unit that changed kind? | Report the pair; never re-key across kinds; `--remove-orphans` stays the instruction. |
| D6 | Add the process rule to `AGENTS.md`? | Yes: "A change to derive, identity or units states its effect on the egress in the same spec PR." |
| D7 | Map `cash-ron`? | Yes, as an asset ("Cash (Ron)"). |

**Resolved since this review:** D8/D9 and R1–R8 — the plan carries the amendments (Tasks 4.1/4.4/5.1/5.3, §3.1 A5, §11.1) — see §3; the second pass V1–V7 corrected four of those resolutions (§3.1).

### 2.5 Review focus (acceptance scenarios)

1. A hand-split group whose unit's amount later changes: the splits stay, the plan says so, and
   verify does not fail forever.
2. First run after the upgrade, with old hub hashes: every row is checked once (a GET), nothing is
   written when Firefly already matches, and the second run is quiet.
3. A foreign Firefly group carrying an `external_id`: never an orphan, never deleted.
4. A re-parse that supersedes a leg of a projected transfer: the group is re-keyed in place; no
   second transfer appears.
5. A clearing account missing from `firefly.yaml`: the pass refuses before the first write and names
   the ref.

---

## 3. Gaps after review

Method: reviewed 2026-10-09 against `V2-SPEC.md` §4/§6.3/§6.7/§11, `V2-PROPOSAL.md` §11 and the
actual sources (`HubQueries.projectionUnits`, `HubSql`, `FireflyEgress`, `FireflyClient`,
`Projection`, `AccountMap`, `HubClient`, `EgressCommand`, the fakes and the tests). F1–F11 are all
confirmed; the plan's snippets and interfaces match the real records, so most of it compiles as
written.

| Id | Sev | Gap | Where | Status |
|---|---|---|---|---|
| R1 | **High** | `legs=` never reaches transfers projected before Stage 5, so F11/F9 stay open for the existing stock. | Stage 4 Step 6 + Task 5.2 | **Closed — D8** (refeed, no migration); its cost made explicit by V7 |
| R2 | Medium | "Deleted in Firefly" (`V2-PROPOSAL.md` §11.5) is unaddressed; a 404 aborts with an `IOException`. | §11.1 rewrite | **Resolved — D9** (contract + `--validate`); **corrected by V1, V2, V4** |
| R3 | Medium | The hand-split re-key has no identity branch: `contentMoves` is false for multi-split groups, so the new `external_id` is never written (a PUT, if any, is an accident of the notes refresh). | Task 5.3 | **Resolved** — explicit identity branch + tests; the untagged-group edge closed by V5 |
| R4 | Low | `Content.cents` throws on sub-cent / 3-decimal amounts instead of reporting drift. | Task 4.1 | **Resolved, corrected by V6** — the fingerprint compares the exact amount; HALF_UP only sums hand-splits; blank → 0 |
| R5 | Low | D2 says hand-splits are "reported"; after the first run they are invisible (verify clean forever). | D2 + Task 4.3 | **Resolved** — `--validate` `HAND_SPLIT` (D9) |
| R6 | Low | The spec text promises unit + account + date on a 4xx; `converge` names unit + group only. | §1.8 / §11.1 "Failure" | **Resolved** — §11.1 failure text aligned |
| R7 | Low | Date normalization (`first 10 characters`) has no fallback if A5 shows a UTC-normalized echo. | Task 4.1 + Stage 0 | **Resolved, completed by V3** — §3.1 A5 fallback rule; both sides reduce through `Content.day` |
| R8 | Low | "Ours iff it carries the tag" vs the first-split check in the code. | §1.4 / §11.1 "Ownership" | **Resolved** — §11.1 says first split |

### R1 — closed by deployment strategy (locked 2026-10-09)

The gap (recap): notes are not in the fingerprint and are refreshed only inside `converge`, so a
transfer projected by a Stage-4-only apply never receives `legs=`; a later supersede then produces
the F11 orphan + create instead of a re-key.

**Resolution — Firefly is rebuilt, not migrated (D8).**

- All stages are built first; the kept instance is **refed from empty** with the final build
  (`--create-missing-accounts`, `--seed-categories`, then the first apply). Transfers are born with
  `legs=` written by `create`, so nothing needs backfilling.
- No apply (manual or scheduled) runs against a kept instance until all stages are merged. The
  plan's Stage 4 Step 6 live check is a throwaway/dev-instance activity only.
- The legs marker is therefore **not built**; Task 5.2 keeps only the notes refresh (`rules=` and,
  after a re-key, the new `legs=`).
- Repair if an instance ever does carry pre-Stage-5 groups: refeed it from empty with the final
  build. This assumes the old instance's manual Firefly edits are dispensable; if you ever need to
  keep them, R1 returns and the reference fix is the legs marker in the recorded state.

### R2 — resolved as an operational contract (locked 2026-10-09)

**Decision: an operational issue, not BAU auto-repair (D9).** The egress gains detection and a
named failure; it never silently copes with an edit made in Firefly.

1. **The contract** (the Firefly section of `docs/DEPLOYMENTS.md`, with a pointer paragraph in
   §11.1): allowed are your extra tags, budgets, bills, notes below our first line and hand-splits;
   violations are deleting a tagged transaction, removing the `trex` tag, editing `external_id`,
   the first notes line (`n=`/`rules=`/`legs=`) or a single-split group's content, or running
   another importer over a tagged row. Each violation names its sanctioned remedies (restore the
   tag, revert, fix the source in trex, retire the unit, or recreate via the recovery path).
2. **`--validate`** — a read-only mode: it lists Firefly's inventory (untagged groups included),
   compares it against the hub units and the recorded state, classifies (`MISSING`, `UNTAGGED`,
   `TAMPERED`, `DRIFT`, `ORPHAN`, `HAND_SPLIT`), prints, and exits 1 on a violation. It writes
   nothing — not Firefly, not `projection_state` — so the "was known" evidence survives repeated
   runs. Timer-safe; this is the scheduled detector (schedule it, not `--verify`). Its `HAND_SPLIT`
   report is also where R5's hand-split sum check belongs.
3. **Named failure on encounter**: a planned read that finds its group missing stops the pass with
   `Refused` naming unit and group and pointing at `--validate` — never a raw `IOException`. Apply
   does not auto-recreate.
4. **The recovery is explicit**: `--verify` rebuilds the state from Firefly (the deleted unit
   becomes a create; exit 1), then `--apply` recreates it. If the deletion was deliberate, retire
   the unit in trex first, then `--remove-orphans`. Proposal §11.5's automatic "recreate if the
   unit still exists" is replaced by this explicit path — a delta recorded in §11.1 and
   `docs/V2-PARITY.md`.

### R3 — resolved: the identity branch is explicit (locked 2026-10-09)

The defect: `converge` handles a re-key through `contentMoves`, which is `single && (rekey || …)`.
A hand-split group is never `single`, so the new `external_id` was never written — whether a PUT
happened (the Task 5.2 notes refresh) or not was incidental. State claimed the new unit, Firefly
kept the old id, and `--verify` asked for the same re-key forever (or apply reported a re-key it
never made).

**Resolution (plan Task 5.3):** an explicit branch after the split loop, before the early return,
run on every split:

```java
if (rekey && !single) {
    for (Map<String, Object> map : splitsOut) {
        map.put("external_id", unit.unitId());
    }
    changed = true;               // the identity move must reach Firefly
}
```

The fingerprint stays `HAND_SPLIT`; the notes refresh carries the new `legs=`. Tests:
`aSupersededHandSplitTransferRekeysItsIdentity` (one group, every split's id is the new one, amounts
and descriptions untouched, verify empty) and a second plan → apply → verify pass that stays empty.

### R4–R8 — resolved (locked 2026-10-09)

- **R4 — never throws (corrected by V6).** As first resolved, `cents` rounded HALF_UP and the
  text claimed a sub-cent remainder "shows as drift" — false below half a cent (`10.004` rounds to
  `10.00` and matches). The fingerprint now carries the exact amount (`Content.exact`:
  `"10.000000000000"` = `"10.00"`, `"10.004"` ≠ `"10.00"`); HALF_UP survives only for summing a
  hand-split group. Blank is 0. Tests: `amountsCompareExactlySoASubCentEditIsDrift`,
  `handSplitSumsRoundInsteadOfThrowing`.
- **R5 — the hand-split report lives in `--validate`.** Its `HAND_SPLIT` finding reports a total
  mismatch on every run, read-only; `--verify` stays quiet by design (D2). D2 now says "reported by
  `--validate` instead".
- **R6 — the §11.1 failure sentence matches the code:** a 4xx names the unit and Firefly's message;
  a create also names the account and date.
- **R7 — the date fallback is a Stage 0 decision point.** A5 records the echo; if its first ten
  characters differ from the date sent, `Content.observed` compares
  `OffsetDateTime.parse(echo).toLocalDate()` and the posting uses `<date>T12:00:00` so the local day
  cannot shift (Task 4.1 + §3.1 A5).
- **R8 — "ours" is the first split's tag.** §11.1 and D4 say "its first split carries the `trex`
  tag", matching `allTransactions` and `converge`.

Implementation nits accepted into the plan: `ProjectionTest.java` joined Task 5.1's file list; the
creates rebuild snippet now uses the snapshot order; and a re-key whose group lost the `trex` tag is
intentionally left for the next plan (ownership is the tag — D4).

### What the review confirmed

- F1's root cause is exactly as stated (the clearing side is looked up as a fact and `continue`d);
- the derived tables are written in one transaction, so "a dangling unit is an error" is safe;
- the interfaces match the real records (`ProjectionUnit` 15 components, `Decision.Supersede`'s
  convenient constructor, `chain_resolved(id, current_id)`, `/api/opening`'s clearing result,
  `HubClient.currencyByAccount()`), so the snippets should compile as written;
- the tests-first structure, stage gates and counter definitions are consistent with `AGENTS.md`.

### 3.1 Second pass on the resolutions — V1–V7 (2026-10-09)

The R1–R8 resolutions were re-checked against the amended plan. All eight point the right way;
four needed correcting, mostly in `--validate`, which would otherwise have exited 1 on ordinary
days. **All seven are now folded into the plan.**

| Id | Sev | Defect in the resolution | Correction (plan location) |
|---|---|---|---|
| V1 | **High** | `TAMPERED` compared the first notes line's **values**. `rules=` goes stale on every unchanged row after any rule edit, and `n=` moves with a new observation of the same content; notes refresh only when a group converges for another reason. A timer-driven `--validate` would fail after every rule edit. | The first line is checked for **shape** only (`trex n=<digits> …`), plus `external_id` and, for a transfer, its `legs=` resolved through the supersession map. Test `aRuleEditLeavesStaleNotesButNoViolation`. (Task 4.4) |
| V2 | Medium | `DRIFT` ("content differs from expected") also fired for pending trex-side work — a restatement ingested but not yet applied. | Judge against the **recorded** fingerprint: Firefly ≠ recorded is `DRIFT` (an edit; exit 1); Firefly = recorded but expected moved is `BEHIND` (informational). Unverified or hand-split state is never `DRIFT`. Test `aTrexSideRestatementIsBehindNotDrift`. (Task 4.4) |
| V3 | Medium | The R7 fallback posts `<date>T12:00:00`, but `Content.expected` used the date string verbatim while `observed` cut it to ten characters: a perpetual update and `DRIFT` on every row. | One `Content.day` used by both sides. Test `aNoonPostingAndItsEchoAreTheSameDay`. (Task 4.1) |
| V4 | Medium | Task 5.3 read a transfer orphan's legs with `group()`, which throws on 404 — the exact case of a group deleted in Firefly. `--plan` itself would abort, contradicting D9. | `groupOrNull`; a missing orphan group is **gone** — not an orphan, dropped from the state on apply. Test `anOrphanDeletedInFireflyIsGoneNotAnAbort`. (Task 5.3) |
| V5 | Low–Med | A re-key into a group that lost its `trex` tag returned `NOT_OURS` after the successor had been removed from the creates; every later run repeated it, so the new unit **never reached Firefly**, and no `--validate` class saw it. | Ownership is checked at match time: an untagged orphan group is **foreign** — the successor is created, the group left as yours. Test `aSupersededUnitWhoseGroupLostItsTagIsCreatedNotStuck`. (Task 5.3) |
| V6 | Low | R4's "a sub-cent remainder shows as drift" was false below half a cent. | Exact amount in the fingerprint (`Content.exact`); HALF_UP only for hand-split sums. (Task 4.1) |
| V7 | Note | D8 makes refeed-from-empty the standing repair, so Firefly-side work (hand-assigned budgets, piggy-bank links, your tags, hand-splits) is disposable — and budgets were left to Firefly. | Stated in D8 and the operations notes: assign budgets with a re-runnable Firefly rule group, never by hand. The D9 forcing function (a deleted transaction blocks deltas until `--verify` → `--apply`) is also written down. (D8, Stage 6) |

Also corrected: §11.1 "Cost" now says a rule change writes only the units whose category changed
(the first wording said every unit was re-planned *and written*).

**Next:** all review items are resolved or carried into the plan; run Stage 0.
