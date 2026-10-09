# V2-ANNOTATIONS-PLAN.md

> **Archived 2026-10-09 — built (before the PR workflow).** The outcome lives in `V2-SPEC.md` (the specification); this
> file is the record of why. Its references to `V2-PROPOSAL.md` as authoritative are history.

> **Personal project, single operator, private.** `V2-PROPOSAL.md` is the specification;
> this file is the build order for one change to it. Where this file and the proposal
> disagree, the proposal wins. The proposal **already defines** `NOTE`, `DISMISS.comment`
> and `USER_ACK.comment`; the amendments in §7 are proposed and not yet applied.

**Status:** implemented; build and tests green; local stack redeployed; the §7 amendments applied.
**Authority:** `V2-PROPOSAL.md` §6.2, §6.3, §6.6, §6.7, §9.4, §9.8, §9.9.F, §10.1, §10.2,
§10.3, §11, §14, §15; `AGENTS.md`.
**Adds to the decision set:** none. All three events already exist and round-trip.

---

## 1. The change, in one paragraph

A person can leave a **note** on a transaction (or, by fan-out, on every member of a
review cluster), and can attach a **comment** to a `DISMISS` and to a `USER_ACK`. Every note
and comment is a **decision**, submitted as a command to the hub and journaled by the
**sequencer** — the only writer — with the usual atomic append. Notes are append-only prose:
they accumulate as a thread, are never edited, are removed only by `REVOKE`, and are **never
identity and never logic**. The hub only *reads them back*; it never writes.

## 2. Target model

### 2.1 Events (already in `V2-PROPOSAL.md` §6.2)

| Action | Payload | Role here |
|---|---|---|
| `NOTE` | `externalId`, `text` | the annotation itself |
| `DISMISS` | `item`, `externalIds`, `comment?` | silencing a review item, with a reason |
| `USER_ACK` | `user`, `externalId`, `stateHash`, `configRevision`, `deriveVersion`, `hashVersion`, `comment?` | a read marker, with an optional reason |

No new action is introduced. This change is a **read model plus UI**, not a grammar change.

### 2.2 The write path (command → event)

The note is a command; the journal line is the event. One path, no shortcut:

```
UI (Annotate / Dismiss+reason / Ack+reason)
  → hub   POST /api/decisions          command; precheck (ids exist, text non-blank, size bound)
  → sequencer POST /decisions (batch)  validate references; one atomic append + fsync
  → journal  (NOTE | DISMISS | USER_ACK with its comment)
  → hub watcher re-derives; the note appears in the row's history
```

The hub is not a writer (`AGENTS.md`: one writer, one log). Nothing is written to the
journal except through the sequencer.

### 2.3 Fan-out for a group (the decision taken)

Annotating a cluster of *k* members posts **one batch of *k* `NOTE` drafts** with the same
text (`allOrNone`). The sequencer appends the batch atomically — one fsync — the same batch
primitive ingest already uses, so a group note is one append and N events, one per row.
Per-row history stays self-contained and each note is revocable on its own; a "note on the
group" is a UI gesture, not a new event kind.

### 2.4 Note semantics

- **Accumulate, don't override.** Notes are a thread, newest first. This is the deliberate
  difference from `PIN`, which is a *classification* and therefore latest-wins. `REVOKE`
  removes a note; nothing is edited in place.
- **Ids resolve through the supersession map** (§9.4): a `SUPERSEDE` carries its notes to
  the current row, exactly as pins and acks do.
- **Never identity, never logic.** A note never enters `derive`'s category, transfer or
  identity paths. It is display (and, later, a projection).
- **Comments are decisions, not fields.** A `DISMISS`/`USER_ACK` reason lives on its own
  line; there is no `comment` column anywhere on a fact.

### 2.5 Read model

Mirror `pin_current` (a disposable projection of effective decisions):

```sql
CREATE TABLE note_current (
  decision_n  INTEGER PRIMARY KEY,
  external_id TEXT NOT NULL,     -- resolved to the current id (§9.4)
  text        TEXT NOT NULL,
  user_id     TEXT,
  at          TEXT NOT NULL
);
```

Effective = `NOTE` decisions minus those named by a `REVOKE`. A row's notes are a query by
`external_id`, newest first. For `DISMISS` reasons there is no projection needed in the
first cut: read the effective `DISMISS` decisions directly (`decision.payload` via
`json_extract`) for a "dismissed" view; promote to a derived table only if it gets hot.

### 2.6 API

- **Write:** unchanged. `POST /api/decisions` already forwards `NOTE` and carries `comment`
  on `DISMISS`/`USER_ACK` (`DecisionDraft`, `HubService` precheck, `Sequencer`).
- **Read:**
  - `LedgerRow` gains `hasNote` and `latestNote` (or `noteCount`) so the Blotter/Eyeball can
    badge a row.
  - `GET /api/notes?externalId=…` → the thread `[{text, user, at, n}]`.
  - `GET /api/dismissals` → effective `DISMISS` decisions `[{item, externalIds, comment,
    user, at}]`, for a "dismissed" toggle in Review (a dismissed item otherwise vanishes).
- **Eyeball:** the per-row `Ack` already posts `AckRequest.comment`; pass the typed reason.

### 2.7 Index

Add `note_current` to `Sql.DERIVED_TABLES` (dropped and rebuilt) and `ALL_TABLES` (counts,
verify), with an insert projection in `Indexer` from `derive().notes()`, and include it in
the derived fingerprint. No `default` table is touched.

## 3. Invariant check

- **Writer never interprets.** Fan-out is N drafts the hub already had; the sequencer only
  validates structure and references. No expansion happens in the writer.
- **`derive()` is pure.** Notes are a fold over decisions; `asOf` explicit.
- **Nothing is deleted.** A note is removed by an appended `REVOKE`; no line is rewritten.
- **Every derived table is disposable.** `note_current` is rebuilt by `trex index --rebuild`.
- **One writer, one atomic append, one fsync per batch.** The group note is one batch.
- **No category on a transaction; no `externalId` rewrite; comments are decisions.** Untouched.

## 4. Blast radius

| Module | File | Change |
|---|---|---|
| core | `derive/NoteRow.java` | new: `{externalId, text, decisionN, userId, at}` |
| core | `derive/Derive.java` | `notes()`: effective `NOTE`s, `REVOKE` applied, ids via `chain_resolved` |
| core | `derive/Derivation.java` | carry `List<NoteRow> notes` |
| core | `config/DeriveConfig.java` | **no version bump expected** (no existing output changes) |
| sequencer | `Sequencer.java` | `NOTE` requires a *known current* `externalId` (precheck parity) |
| index | `resources/…/schema.sql` | `note_current` DDL (§2.5) |
| index | `Sql.java` | `note_current` in `DERIVED_TABLES`/`ALL_TABLES`; `INSERT_NOTE` |
| index | `Indexer.java` | project `notes()`; bind; fingerprint |
| hub | `api/LedgerRow.java` | `hasNote`, `latestNote` |
| hub | `api/NoteJson.java` | new read shape |
| hub | `HubSql.java` | `NOTES_SELECT`, `DISMISSALS_SELECT` |
| hub | `HubQueries.java` | `notes(externalId)`, `dismissals()`, notes folded into the ledger query |
| hub | `HubApi.java` / `HubHttpApi.java` | `GET /api/notes`, `GET /api/dismissals` |
| hub | `HubService.java` | precheck: `NOTE` target known, text non-blank and bounded |
| hub web | `js/decisions.js` | add `note(ctx, id, text)` |
| hub web | `js/review.js` | Dismiss dialog with an optional reason; Annotate on the cluster (fan-out) |
| hub web | `js/blotter.js` | per-row Annotate; note badge; history in the drawer |
| hub web | `js/eyeball.js` | per-row Annotate; Ack carries a reason; show notes |
| docs | `V2-SPEC.md`, `CHANGELOG.md` | as-built, once landed |
| docs | `V2-PROPOSAL.md` | the amendments in §7, on approval |

Tests: `core/DeriveTest`, `index/IndexerTest`, `hub/HubDecisionPathTest`, `hub/HubReviewTest`,
`hub/EyeballTest`, `hub/HubApiTest`. (`log/LogCodecTest` already covers `Decision.Note`.)

## 5. Stages

Each stage compiles and passes its tests before the next.

### Stage 1 — Core projection

Deliverables: `NoteRow`; `Derive.notes()` with `REVOKE` and supersession applied; carried on
`Derivation`.
Acceptance: two `NOTE`s on one row both appear (thread); `REVOKE` removes exactly one; a
`SUPERSEDE` carries the notes to the current id; notes never change category/transfer output.

### Stage 2 — Index

Deliverables: `note_current` DDL, projection and fingerprint.
Acceptance: after `index --rebuild`, `note_current` is identical to the incremental build;
one row per effective note; `trex verify` green.

### Stage 3 — Hub read API

Deliverables: `LedgerRow.hasNote`/`latestNote`; `GET /api/notes`; `GET /api/dismissals`;
precheck for `NOTE`.
Acceptance: `POST /api/decisions {NOTE}` then the ledger row shows the note and
`/api/notes` returns it; a dismissed item appears in `/api/dismissals` with its comment and
user; unknown id or blank text → `422`.

### Stage 4 — UI

Deliverables: Dismiss dialog with an optional reason (Review); Annotate on a row (Blotter)
and on a cluster (Review, fan-out batch); note badges and the thread in the drawer;
`decisions.note(...)`.
Acceptance (manual, operator is the visual check): one click annotates a row; annotating a
2-member cluster writes two notes in one append and both appear; dismissing with a reason
shows it in the dismissed view.

### Stage 5 — Eyeball

Deliverables: per-row Annotate; `Ack` carries a reason; notes shown on the row (and readable
after fade); (optional, parked today) restore the Open items + Anomalies sections with
Dismiss(+reason)/Annotate per entry.
Acceptance: the Acks carry reasons; notes are visible during the walk; the read/unread
behaviour is unchanged.

### Stage 6 — Docs and rollout

Deliverables: `V2-SPEC.md` as-built; `CHANGELOG.md`; proposal amendments (§7) applied on
approval; local rebuild → host deploy → `trex verify`.
Acceptance: `verify` green, accounts reconcile, and a note survives `index --rebuild`.

## 6. Decisions taken

1. **Fan-out, one `NOTE` per member id** (operator, this session). A group annotation is a
   batch gesture; per-row history stays self-contained and each note is independently
   revocable. Rejected for now: a multi-id `NOTE`/group action — a decision-set change for a
   need a batch already meets.
2. **Notes accumulate** as a thread (unlike `PIN`, which classifies and is latest-wins).
3. **A target id is required in v1.** `NOTE`'s `externalId` is optional in the model, but
   anchoring a free-standing note is undefined; defer it.
4. **Notes are trex-only in v1.** Firefly projection of note text is deferred (§8).
5. **Ids resolve through the supersession map**, matching pins and acks.
6. **Dismiss reasons surface via a "dismissed" view**, because the item itself leaves the
   queue; the reason must not become invisible.

## 7. Proposed proposal amendments (approval needed)

1. **§6.2** — make the `NOTE` semantics explicit: *one per target row; a group is a batch of
   `NOTE`s; notes accumulate and are removed by `REVOKE`, never edited.*
2. **§10.2/§10.3** — a row action **Annotate**; an optional reason on `DISMISS` and on
   `Ack`; a note badge and the thread in the row's history; a "dismissed" view.
3. **§11** — (optional, deferred) project `NOTE` text as a Firefly transaction note, one-way.

## 8. Open questions

- **Firefly**: do notes (and dismiss reasons) project as transaction notes, or stay
  trex-local? Privacy argues for local; provenance argues for projecting.
- **Free-standing notes** (`externalId` null): anchor to an account, a period, or drop.
- **Size/text policy**: a max length and whether multi-line text is allowed.
- **History surface**: the row drawer in the Blotter vs a dedicated per-row decision list —
  which shows the notes, pins, dismisses and acks together.

## 9. Rollback

Additive and forward-compatible: `NOTE`, `DISMISS.comment` and `USER_ACK.comment` already
exist in the grammar, so an older binary still parses every line this change can write.
Rollback is "redeploy the previous image"; the read model (`note_current`) is disposable and
rebuilt. No migration.
