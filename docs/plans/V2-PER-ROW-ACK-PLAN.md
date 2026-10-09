# V2-PER-ROW-ACK-PLAN.md

> **Archived 2026-10-09 — built (before the PR workflow).** The outcome lives in `V2-SPEC.md` (the specification); this
> file is the record of why. Its references to `V2-PROPOSAL.md` as authoritative are history.

> **Personal project, single operator, private.** The proposal is the specification; this
> file is the build order for one change to it. Where this file and `V2-PROPOSAL.md`
> disagree, the proposal wins — and the proposal has already been edited for this change.

**Status:** implemented; build and tests green; local stack redeployed from scratch.
**Authority:** `V2-PROPOSAL.md` §6.2, §6.6, §6.7, §6.8, §7.2, §9.4, §9.5, §9.8, §9.9.G,
§10.1, §10.3, §13, §14, §15.7, §17; `AGENTS.md`.
**Supersedes:** the period-marker model (`USER_ACK` per `(user, period)`, the
`throughN`/period `stateHash`, and the period-keyed `user_ack` table).

---

## 1. The change, in one paragraph

`USER_ACK` stops being a per-period attestation and becomes a **per-transaction read
marker**: one decision per row per user, carrying that row's content hash. `USER_UNACK` is
its family inverse. A **period is only a bucketing view** — the Eyeball groups and filters
rows by day/week/month; there is no period state and nothing to clear. The Eyeball's
read/unread fade and its per-row **Ack**/**Unack** are the whole UI surface; the top
"Mark read" control goes away.

**Why (the recorded rationale).** Reading is a deliberate, per-row ceremony — the small
friction is the feature. One line per row is therefore the point, not a cost: a single
`USER_ACK` naming fifty ids would quietly turn the ceremony back into a bulk gesture. The
second benefit falls out for free: invalidation becomes per row, so a reflow that moves one
row no longer un-reads its neighbours.

## 2. Target model

### 2.1 Events (`V2-PROPOSAL.md` §6.2, §6.7)

| Action | Payload | Meaning |
|---|---|---|
| `USER_ACK` | `externalId`, `stateHash`, `configRevision`, `deriveVersion`, `hashVersion`, `comment?` | "I have read this row; its derived content was X." |
| `USER_UNACK` | `externalId`, `comment?` | Release that row's marker for this user. |

Both carry `actor`, `user`, `at`, `n` like every decision. Neither carries a period or
`throughN` — the period is not part of the fact.

### 2.2 The row hash

`stateHash` is now a **row** hash, not a period hash. Same canonical serialisation and the
same exclusions as §9.9.G: `externalId`, kind, `accountRef`, `date`, `amount`, `currency`,
`category`, `origin`, pairing/leg state, `transferId`, `retired`, `ineffective`; excluded:
ages, display fields, `n` noise, and the three revision strings. `hashVersion` stamps it
(bump `statehash/1` → `statehash/2`: the algorithm's meaning changed).

### 2.3 Effectiveness and invalidation (§9.8, §9.4)

- The latest effective decision naming `(user, externalId)` wins; `USER_UNACK` releases,
  a later `USER_ACK` re-reads, `REVOKE` works as the general undo.
- Like every decision, the id resolves through the supersession map, so a `SUPERSEDE`
  carries the marker to the current row.
- After a reflow, each marker's stored hash is compared to the recomputed hash of that row
  (only when `hashVersion` matches). Unchanged → read; changed → **changed since read**,
  while every other row keeps its marker.

### 2.4 Periods are only a view

A period (day/week/month/quarter/year) is a window over rows, not a review unit. The
Eyeball tabs pick the grain and the window, and the rows are the content. No period row is
stored and there is nothing to clear. An unread count for the window is a plain query over
the read set — a count, not a state. `cadence` in `users.yaml` only phrases a nudge.

### 2.5 API (§6.6)

- `GET /api/acks` → `[{user, externalId, stateHash, stale, ackedAt}]`.
- `POST /api/acks` → `{user, externalId, action: "ACK" | "UNACK", comment?}` for one row.
- `/api/acks/diff` (the per-period moved list) is retired; the per-row `stale` flag
  supersedes it.
- The Eyeball rows gain no new endpoint: the UI already fetches `/api/acks`, so it maps
  `externalId → marker` and renders read/unread from that.

### 2.6 Index (§7.2)

```sql
CREATE TABLE user_ack (
  user_id TEXT NOT NULL, external_id TEXT NOT NULL,
  state_hash TEXT NOT NULL,
  config_revision TEXT NOT NULL, derive_version TEXT NOT NULL,
  hash_version TEXT NOT NULL,
  acked_at TEXT NOT NULL,
  PRIMARY KEY (user_id, external_id)
);
```

`txn_current` gains a derived `state_hash` (the row hash), so staleness is a join:
`user_ack.state_hash <> txn_current.state_hash`.

## 3. Invariant check

- **Writer never interprets.** The hub posts one `USER_ACK`/`USER_UNACK` per row; the
  sequencer only validates structure/references. No expansion of a period into rows in the
  writer.
- **`derive()` is pure.** The read set is a fold over decisions; the row hashes are content
  hashes; `asOf` is explicit.
- **Nothing is deleted.** `USER_UNACK` is an appended inverse; `REVOKE` still works.
- **Every derived table is disposable.** `user_ack` is derived from the log and rebuilt by
  `trex index --rebuild`.
- **No category on a transaction, no `externalId` rewrite.** Untouched.
- **Breaking change, deliberately.** The line format changes; the proposal is amended in
  the same change (already done).

## 4. Blast radius

| Module | File | Change |
|---|---|---|
| core | `Action.java` | add `USER_UNACK` |
| core | `Decision.java` | `UserAck` payload → `(n, externalId, …, stateHash, comment, actor, user, at)`; add `UserUnack` |
| core | `derive/UserAckRow.java` | `period`/`throughN` → `externalId` |
| core | `derive/StateHash.java` | `forPeriod` → `forRow` (row content); remove the period entry point |
| core | `derive/Derive.java` | `userAcks()` keyed by `(user, externalId)`, applying `USER_UNACK`; emit `state_hash` on `txn_current` (P6) |
| core | `config/DeriveConfig.java` | `DERIVE_VERSION` `derive/2`→`derive/3`; `HASH_VERSION` `statehash/1`→`statehash/2` |
| core | `derive/Period.java` | **stays** — the Eyeball's period grouping still uses it |
| log | `LogCodec.java` | encode/decode `UserAck`; add `UserUnack` |
| sequencer | `api/DecisionDraft.java` | `period`, `throughN` → `externalId` |
| sequencer | `Sequencer.java` | `USER_ACK` requires `externalId` that exists; add `USER_UNACK` |
| index | `resources/…/schema.sql` | `user_ack` DDL + `txn_current.state_hash` (§2.6) |
| index | `Sql.java` | `INSERT_USER_ACK`; `DERIVED_TABLES` unchanged |
| index | `Indexer.java` | bind `external_id` instead of `period`/`through_n`; bind `txn_current.state_hash` |
| hub | `api/AckRequest.java` | `{user, externalId, action, comment}` |
| hub | `api/AckJson.java` | `{user, externalId, stateHash, stale, ackedAt}` |
| hub | `api/AckDiff.java` | remove (with its route) |
| hub | `HubApi.java` | `acks()`, `postAck()`; drop `ackDiff()` |
| hub | `HubService.java` | `acks()`, `postAck()`, precheck (`USER_ACK`/`USER_UNACK`); drop `ackDiff()` |
| hub | `HubSql.java` | `USER_ACK_SELECT` per row, joined to `txn_current.state_hash` |
| hub | `HubQueries.java` | `userAcks()` binds `external_id`; staleness joins `txn_current.state_hash` |
| hub | `HubHttpApi.java` | `/api/acks` POST/GET; drop `/api/acks/diff` |
| hub web | `js/eyeball.js` | per-row Ack/Unack; fade from the read map; drop the top control and period marker |
| hub web | `js/api.js` | `postAck` payload; drop `ackDiff` |
| docs | `V2-SPEC.md` | as-built update (only once the code lands) |
| docs | `CHANGELOG.md` | the change, with rationale |

Tests to update: `core/DeriveTest`, `log/LogCodecTest`, `sequencer/SequencerTest`,
`index/IndexerTest`, `hub/HubAckTest`, `hub/HubDecisionPathTest`, `hub/HubApiTest`.
(The 148 workbook fixtures are category rules and are unaffected.)

## 5. Stages

Each stage compiles and passes its tests before the next. `derive/3` + `statehash/2` land
in stage 1; everything downstream is then rebuilt against them.

### Stage 1 — Core model

Deliverables: `Action.USER_UNACK`; `Decision` records; `UserAckRow`; `StateHash.forRow`; a
derived `state_hash` on `txn_current` in P6; `Derive.userAcks()` per row with `USER_UNACK`
applied; version bumps.

Acceptance:
- ack a row → it is read for that user; another user is unaffected;
- `USER_UNACK` clears it; a later `USER_ACK` re-reads; `REVOKE` of either restores;
- a `SUPERSEDE` carries the marker to the current row;
- changing the row's category (a rule edit) changes `forRow` but not effectiveness.

### Stage 2 — Log codec

Deliverables: encode/decode the new `USER_ACK`; decode `USER_UNACK`; round-trip test.

Acceptance: encode→parse→encode is stable; a `USER_UNACK` line parses; the golden log
round-trips. **No back-compat for the old period payload** — pre-flight (stage 8) confirms
the live log has no old `USER_ACK` lines.

### Stage 3 — Sequencer

Deliverables: `DecisionDraft.externalId`; `Sequencer` validation (known `externalId`, known
`user`, required hash tuple for ACK; unknown id → rejected).

Acceptance: `USER_ACK` of an unknown/retired id is rejected; `USER_UNACK` of a known id is
appended; the existing "writer never interprets" tests stay green.

### Stage 4 — Index

Deliverables: `user_ack` schema and `txn_current.state_hash`, insert bindings, rebuild.

Acceptance: after `index --rebuild`, `user_ack` is identical to incremental materialisation;
one row per `(user, external_id)`; `user_ack.state_hash` joins to `txn_current.state_hash`
for staleness; the rebuild-key test covers it.

### Stage 5 — Hub API

Deliverables: per-row `acks()`/`postAck()`; staleness per row; precheck; drop `ackDiff` and
its route.

Acceptance:
- `POST /api/acks {ACK}` then `GET /api/acks` shows the row read, `stale=false`;
- a reflow that moves that row flips only that row to `stale=true`;
- `{UNACK}` removes it;
- unknown user / unknown id → `422`; another user's marker is untouched.

### Stage 6 — Eyeball UI

Deliverables: `eyeball.js` maps `externalId → marker`; reads `row.read`/`row.stale`; a
per-row button (`Ack`, or `Unack` once read) beside the existing Categorize; an optional
unread count for the window; the top "Mark read" and the period `stateChip` are removed;
`api.js` payload updated.

Acceptance (manual, operator is the visual check): bright unread rows, faded read rows, one
click fades a single row, `Unack` restores it, `changed since read` shows on a moved row,
and the period tabs still bucket the rows correctly.

### Stage 7 — Egress check

Deliverables: none expected. Confirm `trex-v2-egress` only touches the **projection** unit
hash (`HubClient.ProjectionUnit.unitHash`, `FireflyEgress`), never the read marker.
Acceptance: egress tests unchanged and green.

### Stage 8 — Pre-flight on the live log

Deliverables: prove the format change is free.

Acceptance:
- `grep -c '"action":"USER_ACK"' <journal>` is **0** on the host (the seeded history has no
  ACKs — the operator has not read anything yet). If it is not 0, stop: a one-time
  conversion decision is needed before deploying.
- `trex verify` green; all nine accounts reconcile.

### Stage 9 — Docs

Deliverables: `V2-SPEC.md` ACK sections rewritten to the as-built; `CHANGELOG.md` entry with
the rationale; `docs/v2-lifecycles.html` regenerated (the proposal + the HTML already carry
the new diagram).

### Stage 10 — Rollout

Deliverables: local rebuild → host deploy → verification.

Acceptance (host `10.10.10.142`):
- `DOCKER_CONTEXT=trex deploy/bin/trex-v2-docker.sh build` then `docker compose up -d`;
- `trex index --rebuild` completes; `trex verify` green; accounts reconcile;
- the Eyeball's read/unread behaves as in stage 6; a rule edit still flags a moved row.

## 6. Decisions taken

1. **Where the row hash lives.** A derived `state_hash` column on `txn_current`, computed in
   `derive()` (P6), so staleness is a pure SQL join in `user_ack` and the hash lives in one
   pure place. (Rejected: the hub computing it per read.)
2. **`AckDiff` + `/api/acks/diff`.** Removed; the per-row `stale` flag replaces the
   per-period moved list.
3. **Precheck strictness.** `USER_ACK` requires a *current* row (you can only read what you
   can see); `USER_UNACK` only needs a known id. A marker whose row is later
   retired/superseded-away is surfaced as stale/orphan, never silently dropped — the same
   posture as an orphaned `PIN`.
4. **Blotter scope.** Read/unread stays Eyeball-only for now; the Blotter is untouched.
5. **Re-ack semantics.** A `USER_ACK` on an already-read (or stale) row replaces the marker
   with the current hash — latest wins, no special case.

## 7. Rollback

The change is additive in the log's *history* sense (nothing is deleted), but it is a line
grammar change: an older binary cannot parse a new `USER_ACK` line. Rollback therefore is
"redeploy the previous image", not "downgrade in place". There is no data migration because
the live log has no `USER_ACK` lines yet (stage 8 confirms this).
