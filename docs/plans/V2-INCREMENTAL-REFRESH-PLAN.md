# V2-INCREMENTAL-REFRESH-PLAN.md

> **Status:** proposed; slice 1 (ACK/UNACK) is the first build. `V2-SPEC.md` is the
> specification; where this file and the spec disagree, the spec wins and is amended in the same
> change.

**Authority:** `V2-SPEC.md` §6.1 (the pipeline), §6.6/§6.8 (review, state hash), §7 (the read
model), §9 (the hub API); `AGENTS.md`.

**Goal:** stop running the full pure `derive()` (and the wipe/rewrite of all 18 derived tables) on
every journal append. Apply the effect of a *row-scoped* decision directly, so the row a person
just touched is correct immediately; keep the full derive for everything else, coalesced.

---

## 1. The problem, measured

Every journal append runs `IndexRefresher.refresh` → `Indexer.apply` → `deriveAndStore` →
`Derive.derive(facts, decisions, config, asOf)` plus a `DELETE`+re-`INSERT` of every derived table.
At ~6.4k facts that is **~4.5 s**. The `upToDate` short-circuit never fires in the hub because
`refresh` passes `Instant.now()`, so `as_of` always differs.

Measured on Dev1 (one ack):

| stage | cost |
|---|---|
| `POST /api/acks` → sequencer commit + fsync → 200 | **41 ms** |
| inotify → watcher wake | < 1 ms |
| the 200 ms debounce drain | 200 ms |
| `deriveAndStore` (full derive + table rewrite) | **≈ 4.3 s** |
| `publish()` → SSE `delta` → client `refreshActive()` | ~ms |

The client does `await postAck(); await load()`, but `load()` reads the index *before* it moves, so
the row only updates when the SSE `delta` arrives ~4.5 s later. This affects every row action
(pin, note, dismiss — not just ack), and grows with the journal.

## 2. The decision

For a **row-scoped** decision, compute and apply only the rows it can move, authoritatively (the
server computes it; the client never guesses). Everything else keeps the full derive.

### 2.1 Scope

| action | targeted effect | ripple | slice |
|---|---|---|---|
| `USER_ACK` | upsert one `user_ack` row `(user_id, resolved external_id, state_hash, cfg/derive/hash revisions, acked_at)` | **none** (staleness is read-time) | 1 |
| `USER_UNACK` | delete that `user_ack` row | none | 1 |
| `NOTE` | insert one `note_current` row (`decision_n` PK) at the resolved id | none (invalid id → `ineffective_decision` + `review_item`) | 2 |
| `PIN` | `pin_current` upsert; `category_current`; `txn_current.category/category_origin/rule_id/state_hash`; `unit.category/origin` | same-account compute, bounded to the id's unit | 3 |
| `UNPIN` | `category_current`, `txn_current.*`, `unit.*` revert | see §7 (the `pin_current` discrepancy) | 3 |
| `DISMISS` | delete the `review_item` rows for the decision's `(kind, subject)` keys | none (silence) | 4 |
| `REVOKE` (of DISMISS) | — | **global** → full derive | 4 |

### 2.2 What stays a full re-derive

Facts (ingest), `SUPERSEDE`/`RETIRE`, `MARK_NOOP`/`UNMARK_NOOP`, `MARK_EXTERNAL`, `PAIR`/`UNPAIR`,
`ATTACH_ACCOUNT`, every commitment action, and **any `REVOKE`**. These are "structural": their
effect reaches chains, transfers, commitments or review clusters that are only resolved globally.

### 2.3 `asOf`

`asOf` is a real input (pending aging, the four time-relative review kinds, commitment faces, the
`>asOf` cut-off). The four scoped actions are **`asOf`-independent**, so a patch leaves the stored
`as_of` untouched. A full derive still runs to advance `asOf`: coalesced after a structural append,
and on a timer (proposed: at most once per `N` seconds, and on startup). This decouples the write
clock from the time clock, which the current per-append derive conflates.

## 3. Correctness spine (load-bearing)

The patch is a second implementation of a slice of `derive()`, so it must be *provably* equal:

- **Per-scope property test.** For each scope: build a log, run a full derive at a fixed `asOf`,
  capture the derived fingerprint; then take the same log, apply the scoped action through the
  targeted path, and assert the fingerprint is **byte-identical**. This is the acceptance test for
  every slice.
- **`rebuild ≡ incremental`** (`IndexerTest.rebuildEqualsIncremental`, `VerifyCommand`) must keep
  passing unchanged.
- **Pin the patch to a consistent frontier.** A patch advances `derived_offset` to `read.end()` in
  the *same transaction* as its row writes.
- **Never patch across drift.** If `config_revision`, `derive_version` or `hash_version` differ
  from the derived meta, or the derived meta was cleared, run the full derive.
- **One writer.** `Indexer` is `synchronized` on one connection; the patch is one transaction, same
  visibility as `deriveAndStore` (WAL read pool).

## 4. Where the patch is computed and delivered

- **core** — only where the slice is not expressible from already-derived state (PIN, DISMISS):
  expose a restricted form of the relevant `Derive` stage that reuses its own helpers, so equality
  is by construction. ACK/UNACK and NOTE need **no core change**: resolve the id through the
  already-derived `chain_resolved` table.
- **index** — `Indexer.apply` classifies the newly appended lines, after `ingestMirror`:
  if every line is a row-scoped action **and** the versions/config match, run the targeted patch and
  advance `derived_offset`; otherwise fall through to `deriveAndStore`. New named `Sql` constants for
  the row-scoped `INSERT`/`DELETE` (none exist today).
- **hub** — `HubService.submitDecisions` returns the affected rows in the decision POST response
  (re-read from the index by the request's ids), so the acting client needs no second fetch;
  `postAck` inherits it. `HubEvents.Change` (or a second delta) carries the same patch for other
  clients. The patch is handed off keyed by the assigned `n`.
- **web** — each mode gains an optional `patch(rows)`; `app.js onDelta` applies the patch by
  `externalId` instead of `refreshActive()`, and `eyeball.toggleAck` / `blotter.submit` replace their
  `await load()` with applying the returned patch. Fall back to a full reload when no patch is
  present.

## 5. Build order (vertical slices, each compiling + green)

1. **ACK/UNACK** — index targeted path (no core change) + hub response + SSE patch + web apply.
   This is the ~90 % BAU path and the simplest slice; it also lands the shared infrastructure.
2. **NOTE** — one `note_current` row (+ the invalid-id `ineffective_decision`/`review_item` case).
3. **PIN/UNPIN** — the category/`state_hash`/`unit` slice (the first core helper).
4. **DISMISS** — the `review_item` delete; `REVOKE` stays a full derive.
5. **`asOf` timer** — decouple the time clock; stop deriving on every append.

Each slice is a small PR; slice 1 proves the mechanism before the rest is built.

## 6. Acceptance

- Per-scope fingerprint equality (§3) — the load-bearing test.
- `rebuild ≡ incremental` unchanged.
- Live on Dev1: an ack appears ~instantly and the ack path runs **no** full derive (confirm by
  timing the `delta` against the write).
- Config/version drift still forces a full derive.

## 7. Open discrepancy (do not silently resolve)

`Derive.pins()` folds only `Decision.Pin` and never inspects `Decision.Unpin`, so a `pin_current`
row survives a PIN→UNPIN while `category_current`/`txn_current` correctly revert. The spec says the
latest effective PIN/UNPIN wins. A byte-identical PIN patch must replicate the current code; the
honest fix is a separate tiny change (bump `deriveVersion`) before slice 3. Decide before building
slice 3.

## 8. Risks

- **Equivalence** is the whole cost; a missed column (e.g. `txn_current.state_hash`) silently
  diverges. The fingerprint test is the guard, and it must run on real-shaped logs.
- **Window staleness**: between a structural commit and its coalesced derive, a *reload* still sees
  the old index; patch-carrying deltas hide it for the patched rows only.
- **`asOf` drift** if the timer is too lazy — pending/arrears lag.
- **Two writers of the derived tables** (patch and full derive) must never interleave — one
  connection, one transaction each; a structural derive landing between a patch and its reconcile is
  consistent because it recomputes from the decision table.
