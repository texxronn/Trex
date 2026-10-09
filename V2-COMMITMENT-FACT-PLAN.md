# V2-COMMITMENT-FACT-PLAN.md

> **Personal project, single operator, private.** `V2-PROPOSAL.md` is the specification; this file
> is the build order for one change to it. Where this file and the proposal disagree, the proposal
> wins — until the §4 amendment lands.
>
> **Status:** proposed — Stage 0 (this plan). Build follows once this PR is merged.
> **Authority:** `V2-PROPOSAL.md` §6.11 (commitments), §7.1/§7.2 (the derived read model),
> §10.2 (the Expected mode); `V2-COMMITMENTS-PLAN.md` §2.2/§2.5/§2.7;
> `V2-MANUAL-ARREARS-PLAN.md` §3.1; `AGENTS.md`.
> **Decision (operator, 2026-10-09):** a fact → commitment association is a **derived reverse map**
> (`commitment_fact`), not a hub-owned cache; it is **unbounded** over history, and the materialised
> twelve-month window governs only which bindings are displayed as occurrences.

---

## 1. The change, in one paragraph

The Hub reverse-maps a transaction to its commitment through
`commitment_occurrence.matched_external_id`, which is occurrence-first and therefore lossy: an
occurrence records **one** fact id while a window **sums** every fact that lands in it, and the
occurrence set is bounded to the last twelve months, so old rule-matched transactions and sibling
facts in a summed window get no chip. Replace that reverse lookup with a derived table
`commitment_fact(external_id, commitment_id, matched_by)` — one row per claimed fact, keyed by the
fact, produced by the same claim pass the matcher uses and written by the indexer. It is disposable
(`trex index --rebuild`), it is the single source for the Blotter/Eyeball chip **and** the Expected
activity menu — the commitment popup's transaction list and its price timeseries are both rendered
from the one endpoint (`GET /api/commitments/activity`), so the chip, the table and the chart agree,
pins included. The twelve-month window keeps governing only the occurrences the Expected view
materialises, not the association.

## 2. Why: the measured failure

**Sibling facts are invisible.** `CommitmentOccurrence` holds a single `matched_external_id`; the
matcher's `attach` keeps the first fact's id and adds the rest only to the amount. On the live
journal, three `interest-credit` occurrences are summed but expose one fact each:

| due | window | occurrence amount | linked fact | sibling fact (no chip) |
|---|---|---|---|---|
| 2026-03-31 | 03-24..04-07 | 1121 | 1119 `Bonus Interest Credit` | 2¢ `Interest Credit` |
| 2026-05-31 | 05-24..06-07 | 2196 | 2192 `Bonus Interest Credit` | 4¢ `Interest Credit` |
| 2026-08-31 | 08-24..09-07 | 823 | 822 `Bonus Interest Credit` | 1¢ `Interest Credit` |

**Old facts are invisible.** Occurrences materialise from `asOf − 12 months` (`PAST_MONTHS`). A
regular commitment's older rule matches are never occurrences. `amazon-web-services` has 27
claimable facts and 11 linked: oldest claimable `2024-08-05`, oldest linked `2025-11-03` — the ~16
charges before the window show a blank chip.

**A third truth already exists.** The Expected activity menu (`HubQueries.activity`) re-scans
**rules** over all history, so it omits pinned facts and does not model exclusive assignment (a
fact claimed by a later declaration still lists under the earlier commitment's rules). So today the
chip (occurrence-based) and the menu (rule-based) can disagree, and neither is the canonical
binding.

Both gaps trace to one cause: there is no fact → commitment map. The occurrence is the scheduling
unit; a fact is the association unit. The Hub reverse-maps through the wrong key.

(Note: the chip is **not** rule-based — pins already surface, because the matcher sets
`matched_external_id` for `matched_by = rule` and `pin` alike. The §4 note in the handoff about "the
ledger chip for pinned rows" is really about the activity menu.)

## 3. Target model

### 3.1 The map

A new derived table, `commitment_fact`, one row per **claimed** fact:

- `external_id TEXT PRIMARY KEY` — assignment is exclusive (a fact is claimed by at most one
  declared commitment), so the fact id is a genuine key and the reverse map is 1:1.
- `commitment_id TEXT NOT NULL`.
- `matched_by TEXT NOT NULL` (`rule` or `pin`) — provenance, so the UI can say *assigned* vs
  *matched* and a future per-fact view can separate them.
- An index on `commitment_id` for the menu read.

The map is **declared commitments only**: a detected candidate is a proposal, not an association.
An excluded `(commitment, fact)` pair is never claimed, so it has no row and its chip disappears —
which is exactly the reactive exclusion behaviour already wanted. The fact itself is untouched.

### 3.2 One claim pass, unbounded

- **Assignment is one pass over all current facts** (≤ `asOf`, no NOOP), in the existing
  precedence: the pin first, else the latest declaration whose rules, sign and life (`endedAt`)
  admit it; exclusion and unknown targets fall through, as today. It yields both the map and the
  inputs to occurrence attachment, so the two can never drift.
- **The span bound leaves the claim.** The twelve-month window is a *display* bound: a fact older
  than the materialised span is still bound to its commitment (and appears in the map/activity),
  it simply has no occurrence row. `beforeSpan` is dropped from both the pin and the rule path, and
  the `afterLife` span interaction (`spanStart == null`) goes with it — a retired commitment's life
  is purely `date ≤ endedAt`, so an old retirement still binds its historical facts.
- **Attachment keeps the span gate.** A claimed fact at or after the first materialised window
  takes its containing window (or becomes `off_schedule` at its own date), exactly as today; a
  claimed fact before it produces **no** occurrence — it is bound but not shown. An `irregular`
  commitment has no span, so its map and its occurrences already agree.
- The only behavioural change: once span is removed from the claim, a fact that a later declaration
  would previously have *released* because of the span now stays with that declaration. This can
  move a handful of old occurrences between commitments; it never invents or drops money.

### 3.3 What the map drives

The read is dual-sourced by `origin` — the chicken/egg split. A **detected candidate** is a
proposal, not an association, so it has **no** `commitment_fact` rows and keeps the existing model:
its popup resolves the frozen-stem series and it carries no chip. A **declared** commitment resolves
through the map. Everything below applies to declared commitments only.

- **Blotter / Eyeball chip** (`HubSql.LEDGER_SELECT`): a `LEFT JOIN commitment_fact` on
  `external_id`, then `commitment` for the name — replacing the two correlated subqueries per row
  (also cheaper). Same `commitmentChip` rendering; every claimed fact now gets one.
- **Expected activity menu — the commitment popup** (`HubQueries.activity`, served as
  `GET /api/commitments/activity?id=`, consumed by `commitment.js` `loadActivity` →
  `activityTable` **and** `priceChart`): driven by the map for declared commitments, so the
  transaction list and the price timeseries are the exact binding (rule **and** pin) rather than a
  rule re-scan — pin-only facts now appear, and a contested fact appears only under its winner. The
  commitment's rule-matching facts that are excluded are unioned back (still marked `excluded`, so
  Include works); a stale exclusion not currently matching a rule is not shown. `matchedBy` becomes
  `rule` **or** `pin`. The candidate path (by frozen stem) is unchanged. The table and the chart
  keep their existing rendering — they read the same `ActivityJson` rows, so one read drives both,
  and chart points drop reactively when a row is excluded.
- **The parked per-fact link** (`V2-COMMITMENTS-PLAN.md` §4): `commitment_fact` *is* that table, so
  the "a fact placed only by a pin is not in the menu" gap and the ledger chip for pinned rows close
  with this work — no separate item.

### 3.4 Unchanged

Detection and candidates; the occurrence rows' meaning, their window/status/arrears semantics
(`V2-MANUAL-ARREARS-PLAN.md`); the price-step cost model; the irregular path; the log grammar, the
decision set, the writer and the sequencer; the API DTOs (the chip fields are already on
`LedgerRow`).

## 4. Supersedes / amendments

- **`V2-PROPOSAL.md` §7.1/§7.2** enumerate the derived tables; add `commitment_fact` to the list
  and to §7.2's "as built" pointer. The §7 promise ("every table here can be dropped") holds: the
  map is a projection of the claim pass and `trex index --rebuild` recovers it.
- **`V2-PROPOSAL.md` §10.2** (Expected): make explicit that the materialised occurrence window is a
  display bound; the fact → commitment association is unbounded.
- **`V2-COMMITMENTS-PLAN.md` §2.7** gains the table name; §2.2's rule convention is unchanged. No
  §2.5 semantics change beyond "span moves from claim to attach".

## 5. Invariant check

- `derive()` stays pure: the claim pass is a deterministic scan over resolved inputs and `asOf`; the
  map is a pure function of the same inputs as the occurrences.
- The map is a **derived table**: disposable, refreshed by the indexer on every append, never a
  hub-held truth, never queried back into the log.
- **No new decision types**; the log is untouched; nothing is deleted; decisions still win and ids
  still resolve through the supersession map.
- `deriveVersion` bumps to **`derive/12`** (the derived output changes: a new table and the span
  move). `HASH_VERSION` (`statehash/4`) is unchanged — the map carries no state hash (like
  `commitment_rule`); only the rare contested-occurrence move changes an existing occurrence's
  *content*, not the hashing algorithm.

## 6. Blast radius

| Area | Files |
|---|---|
| core | `CommitmentMatcher.java` (one claim pass; record every link; span gate at attach), `CommitmentFact.java` (new), `CommitmentMatch.java` (+ links), `Derive.java` (collect/expose the links), `DeriveConfig.java` (`derive/12`) |
| index | `schema.sql` (`commitment_fact` table + index), `IndexSchema` (guard/migrate), `Sql`, `Indexer` |
| hub | `HubSql.java` (`LEDGER_SELECT` join), `HubQueries.java` (`activity` reads the map), `HubService` if a precheck reads it |
| web | none structural — the chip renders as today, and the commitment popup's transaction list (`activityTable`) and price chart (`priceChart`) keep their rendering; both now read the map-backed `GET /api/commitments/activity`, and `commitment.js` activity rows already carry the `excluded` toggle |
| docs | this plan; `V2-PROPOSAL.md` §7.1/§7.2 and §10.2; `V2-SPEC.md` §7; `V2-COMMITMENTS-PLAN.md` §2.7; `CHANGELOG.md`; the handoff §4 ("`commitment_fact` links") is now built |

## 7. Stages

- **Stage 0 — this plan.** One docs PR.
- **Stage 1 — core + index.** One claim pass returning links; `commitment_fact` emitted for every
  claimed fact; span gate at attach; `derive/12`; new table + index + `IndexSchema` migration +
  `Indexer` write. Tests: a two-fact window links both; a pin-only fact links (`matched_by=pin`); a
  rule fact claimed by the latest declaration links once; an excluded fact links none; a pre-span
  fact links but yields no occurrence; a fact contested across two declarations links to the
  winner. `mvn test` green.
- **Stage 2 — hub + web.** `LEDGER_SELECT` and `activity` read the map; confirm the chip, the popup
  transaction list and the popup price chart all agree on rule- and pin-bound rows; a docs-as-built
  commit. One PR.
- **Stage 3 — prove it.** Rebuild the dev index (`derive/12`), `trex verify` green, then on the
  live journal: the three `interest-credit` ¢-siblings carry a chip, the pre-window Amazon charges
  carry a chip, and a pinned row shows one.

## 8. Acceptance

- Every fact claimed by a declared commitment has exactly one `commitment_fact` row, and only
  claimed facts have one; a rebuild reproduces the table byte-for-byte from the log.
- The Blotter/Eyeball chip shows for both rule- and pin-bound facts, including every fact of a
  summed window and facts older than the occurrence window; an excluded fact shows no chip.
- The Expected activity menu lists exactly the map's facts for a declared commitment plus its
  dimmed exclusions; it agrees with the chip on every row; candidates are unchanged.
- The commitment popup is driven by the same read: its transaction list and its price timeseries
  both show the map's facts (pins and pre-window facts included), and excluding a row drops it from
  both reactively.
- No occurrence is added or removed by the span move except where a contested pre-span fact changes
  hands; no amount is invented or dropped.
- `trex verify`: rebuild ≡ incremental; reconciliation green.

## 9. Decisions taken (operator, 2026-10-09)

1. **Unbounded association**: the fact → commitment binding spans all history; the twelve-month
   window only decides which bindings are materialised as occurrences. (Headline decision; the
   build confirms it on the live journal in Stage 3.)
2. **Derived, not hub-owned**: the map is a disposable derived table written by the indexer, read by
   the Hub — never a live structure the Hub maintains.
3. **Fact-keyed**: `external_id` is the primary key; a fact belongs to at most one commitment.
4. **Rule and pin both**: provenance is kept (`matched_by`), both count for the chip and the menu.
5. **Excluded facts are absent**: no map row, no chip; the menu re-adds them marked so Include
   works.
6. **Declared uses the map; candidates use the stem** (the chicken/egg split): a candidate has no
   map rows, so its popup keeps the frozen-stem lens and carries no chip; the moment a person
   declares, the commitment switches to the map.
7. Plan first, then build in stages.

## 10. Rollback

Revert the Stage 1/2 PRs. The map is derived, so reverting the code leaves the index either without
the table (a migration or `--rebuild` restores the prior shape) or with an inert table; the log is
never rewritten and the earlier occurrence-based chip returns with the revert.
