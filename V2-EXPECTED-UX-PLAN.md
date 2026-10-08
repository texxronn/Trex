# V2-EXPECTED-UX-PLAN.md

> **Personal project, single operator, private.** `V2-PROPOSAL.md` is the specification;
> this file is the build order for one change to it. Where this file and the proposal
> disagree, the proposal wins. This change is **presentation only**: it amends no event,
> no derivation, no API and no invariant. Proposal §10.6 keeps its composition and
> vocabulary; no proposal amendment is proposed.

**Status:** Stage 1 (the accordion) and Stage 2 (the registry curation surface) implemented;
Stage 2b parked.
**Authority:** `V2-PROPOSAL.md` §10.1 (the Expected mode), §10.6 (the composition and the
vocabulary); `V2-COMMITMENTS-PLAN.md` §2.8, §10.12 (names locked: **Commitments** = the
registry, **Expected** = the forward view); `V2-SPEC.md` (as built); `AGENTS.md`.
**Adds to the decision set:** none.

---

## 1. The change, in one paragraph

The Expected mode is one flat stack — Occurrences, Catch up, registry (42 rows on the
fixture and growing), Lint — so the registry dominates the scroll and the daily content
(what is due, what is behind) is buried under it. Make each of the four sections a
**collapsible `<details>` section** whose summary carries its live count/total, with
sensible defaults: Occurrences and Catch up open, the registry and Lint collapsed.
Same content, same order, same vocabulary as §10.6 — only the sections fold.

## 2. Current state (as built)

`web/js/expected.js` renders, top to bottom:

| Section | Content now | Size on the fixture |
|---|---|---|
| Occurrences | the selected window's occurrences, with statuses | 0 rows today (no declared commitments); a month of rows once curated |
| Catch up | the whole arrears backlog, oldest first, running total | 0 today; grows with curation |
| Registry | every commitment: faces, current/previous price, last/next, arrears, notes, actions; plus **Declare commitment** | 42 rows today, grows |
| Lint | overlapping-match warnings and each declared commitment's rules | small now; grows with rules |

The toolbar holds the window pills (Today / This week / This month) and the
`committed … out · … in` totals and stays as it is.

## 3. Decision (operator, 2026-10-08)

- **Accordion, not sub-tabs.** Collapsible sections keep §10.6's composition exactly, need
  no proposal wording change, and reuse the house `<details>` idiom (review clusters, the
  Rules tree, Lint's own rule list). Sub-tabs stay a possible later migration if the
  registry outgrows a section (§8).
- **Top-level tab stays `Expected`.** It is the mode name of record and the locked pair is
  kept: Expected = the forward view, Commitments = the registry. Alternatives considered
  and rejected: *Commitments* (noun, but it is the locked registry name), *Upcoming*
  (future-only; the table shows occurred/settled too), *Recurring* (the detection lens),
  *Forecast* (reserved for the parked cash-flow feature), *Bills* (too narrow).

## 4. Design

### 4.1 Sections and headers

Each `section.mode-section` becomes a `<details class="x-section">`; its `<summary>` is
the header, with the label and the live figures (computed from the data already loaded —
no extra requests):

| Section | Header | Default |
|---|---|---|
| Occurrences | `Occurrences · <window label> · <n>` | open |
| Catch up | `Catch up · <n> behind · <money total>` (warn tint when non-empty); `Catch up · nothing in arrears` when empty | open |
| Commitments | `Commitments · <n> · <candidates> candidates`; filtered counts follow §7 Stage 2 | collapsed |
| Lint | `Lint · <n> overlaps` (or `no overlaps`) | collapsed |

- Labels: **Occurrences** stays the domain term (the table shows the window's whole
  occurrence set, not only the future); the registry section is titled **Commitments**
  (the §10.12 locked name; the built title reads "Registry" — one-word label change);
  **Lint**, never "Rules" (a top-level mode already owns that word).
- The **Declare commitment** button stays inside the Commitments body: a `<summary>` may
  not contain interactive elements.
- Multiple sections may be open at once (native `<details>`, no exclusivity script).

### 4.2 State

- The open/closed map lives in `localStorage` under `trex.expected.sections` (JSON), the
  same idiom as `trex.expected.window` and `trex.eyeball.grain`. Missing keys take the
  defaults in §4.1.
- Toggles are applied when the DOM is built and written on toggle; the page re-renders on
  every SSE delta, so the map is re-applied each render — the pattern `review.js` already
  uses for cluster expansion.

### 4.3 Deferred conveniences

- `#expected?open=catchup` via the existing `ctx.modeQuery` plumbing (the shell parses
  `#mode?query`; Eyeball/Blotter/Chains use it) — parked until the notify follow-on needs
  a deep link.
- Registry filters/search/sort and the sub-tab migration — §8.

## 5. Invariant and authority check

- `derive()` untouched; the journal, the sequencer, the index schema and every API contract
  are untouched. This is a web-tree change only.
- §10.6's composition and vocabulary are preserved ("Below the list, the Catch up panel…"):
  the sections are the same, in the same order, merely foldable. No proposal amendment.
- Names follow the §10.12 lock; no `/api/*` path or decision name changes.

## 6. Blast radius

| Area | Files |
|---|---|
| web | `trex-v2-hub/src/main/resources/trex/v2/hub/web/js/expected.js` (structure), `app.css` (`.x-section` summary styling) |
| docs | this file; a CHANGELOG line and an optional one-line `V2-SPEC.md` as-built note at implementation |
| untouched | `index.html`, `app.js`, every `trex-v2-*` Java module, `schema.sql`, `V2-PROPOSAL.md` |

No Java change, so dev is a browser reload (`/web` is mounted); no image rebuild, no
migration, `trex verify` stays green and is not part of this acceptance.

## 7. Stages

- **Stage 0 — this plan.** Land as its own docs PR (one commit).
- **Stage 1 — the accordion.** `expected.js` + `app.css` only, one PR. Acceptance §8.
- **Stage 2 — the registry becomes a curation surface** (promoted 2026-10-08, operator):
  - **Identity.** The candidate stem (`candidate_key`) joins the derived `commitment` table
    (disposable; `IndexSchema` carries the shape guard) and is exposed as `stem` in
    `CommitmentJson`: a candidate shows its stem where a declared row shows its name; the
    hash id is the hover title only.
  - **Evidence.** The row gains a series line — occurrence count, span, regularity,
    `variable` — and the price change date; kind `other` renders as `—` for candidates
    (a default, not a conclusion).
  - **Actions.** Candidates get an **Actions…** dialog: **Review** (deep-links
    `#review?kind=SUSPECTED_RECURRING`; Review learns `ctx.modeQuery`), **Confirm…** (the
    same prefill as Review, shared from `commitment.js`) and **Ignore…** (a reason required).
    The dialog lists the series' own transactions, fetched on open from
    `GET /api/commitments/facts?stem=…` — the same lens the detector grouped with, oldest
    first — so the decision is made against the evidence.
  - **Filters.** Inside the Commitments section: All | Candidates | Declared, status,
    direction, text search and sort; all rows show by default (operator, 2026-10-08); the
    header count follows the filter (`n of total` when filtered).
  - Parked in 2b: the price timeline (`steps`), cost-to-date and annualised; the sub-tab
    migration if volume demands it.

## 8. Acceptance (Stage 1)

- Default first paint fits roughly one viewport on the fixture: toolbar + two one-line
  empties + two collapsed headers; with curated data, Occurrences and Catch up are open
  and the registry/Lint are out of the way.
- Every header shows the live count/total and updates on SSE without opening the section.
- Section state survives mode switches and reloads; several sections may be open at once.
- All existing actions behave unchanged: Settle, Assign a payment, Declare, Re-declare,
  Retire, Note, and the window pills/totals.
- Keyboard: each summary is reachable and toggles with Enter/Space; summary rows meet the
  existing mobile tap-size rule.
- No API, derive or journal change; the running hub needs only a web reload.

## 9. Decisions taken (operator, 2026-10-08)

1. Accordion (collapsible sections), not sub-tabs.
2. Top-level tab name stays **Expected**.
3. Record this plan as a repo document before building.
4. The registry stage (Stage 2) covers identity, evidence, actions and filters; the price
   timeline, cost-to-date and observed descriptors stay parked (2b).
5. A candidate row offers an **Actions…** dialog with **Review / Confirm / Ignore**.
6. Ended candidates show by default; filtering is available but not the default.
7. The candidate dialog lists the series' own current transactions (fetched on open), so
   Confirm/Ignore are made against the evidence.

## 10. Rollback

Revert the Stage 1 PR. The page returns to the flat stack; no data, log or API state is
involved.
