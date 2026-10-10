# The family-assistant contract

The contract a **family assistant** follows when it asks Trex small questions and records a
conclusion on a person's behalf. It is **binding-agnostic**: the same rules serve an MCP host (calling
the `trex_*` tools at `POST /mcp`) and the family bot calling the hub's API directly (`POST
/api/decisions`, `GET /api/brief`). The intent-shaped actions and the attribution rule are shared
policy; only the transport differs.

`V2-SPEC.md` is the specification and `AGENTS.md` binds. This document adds no semantics: it names
existing reads, the three actions that wrap the existing decision path, and the safety posture.
`V2-ASSISTANT-PLAN.md` is the record of why; the build is Stages A–B (per-person attribution, the
three action tools, the compact `brief`).

The assistant is **never a second writer**. Every write goes through the hub's one decision path
(`HubApi.submitDecisions` for MCP, `POST /api/decisions` for the bot) — prechecks, the staleness
`409`, and the one-writer rule are untouched.

---

## 1. Small reads to prefer

Ask the smallest question that answers the turn. Prefer the **brief** over dumping `trex_units` or a
year of `trex_ledger`: a chat turn wants a snapshot, not megabytes of JSON. Start with `trex_brief`,
then reach for a typed read only when the brief does not carry the detail.

| Question | MCP tool | Hub read |
|---|---|---|
| Where are we, and what needs attention? | `trex_brief` | `GET /api/brief` |
| What is due, and what is in arrears? | `trex_expected` | `GET /api/expected` |
| What is in the review queue? | `trex_review` | `GET /api/review` |
| What commitments exist? | `trex_commitments` | `GET /api/commitments` |
| One commitment's facts? | `trex_commitment_activity` (`id`) | `GET /api/commitments/activity?id=…` |
| Which transactions? | `trex_ledger` (always pass a sane `limit`) | `GET /api/ledger?…&limit=…` |
| The note thread on a row? | `trex_notes` (`externalId`) | `GET /api/notes?externalId=…` |
| Account coverage and the newest ingest? | `trex_accounts` | `GET /api/accounts` |
| Does everything balance? | `trex_reconcile` | `GET /api/reconcile` |
| What changed since line `n`? | `trex_since` (`n`, `user`) | `GET /api/since?n=…&user=…` |

`trex_brief` (and `GET /api/brief`) is the compact snapshot: the head (`asOfN`), open review items by
kind, the reconciliation verdict and its gaps, the current month's committed totals and arrears
count, and the newest ingest. It is a **view** — rebuildable, storing nothing, computing no new
semantics. Every number in it comes from a read above.

`trex_ledger` is the page read: it takes `limit` (clamped `1..1000`), `offset` and the usual filters,
and returns the matching rows **and** the matching total. Pass a `limit`; reading the whole ledger is
never the right first move.

An MCP host can also reach any read above through the allow-listed passthrough `trex_get`
(`{"path":"/api/brief", …}`). Prefer the typed tool; `trex_get` exists for paths that have no typed
tool.

---

## 2. The three actions

Three intent-shaped actions cover almost every write the assistant needs. Each builds **one** draft
for the existing decision path; the semantics are exactly the UI's (`decisions.js`). Every action
requires `asOfN` — the log line the caller's view was built at — so a stale write is the hub's `409`,
never a silent mis-post.

### 2.1 Categorise — `PIN` / `trex_categorize`

Assign a category to one or more transactions. This is a **pin**, not a property of the transaction.

`POST /api/decisions` body:

```json
{
  "asOfN": 1857,
  "decisions": [
    {
      "action": "PIN",
      "actor": "user",
      "user": "ron",
      "externalIds": ["ing-salary:2026-09-30:acc:1"],
      "category": "GROCERIES"
    }
  ]
}
```

MCP: `trex_categorize { "externalIds": ["…"], "category": "GROCERIES", "asOfN": 1857, "actingUser": "ron" }`

### 2.2 Note — `NOTE` / `trex_note`

Attach a note to one transaction.

`POST /api/decisions` body:

```json
{
  "asOfN": 1857,
  "decisions": [
    {
      "action": "NOTE",
      "actor": "user",
      "user": "ron",
      "externalId": "bw-csv:2026-09-14:txn:42",
      "text": "reimbursed by work"
    }
  ]
}
```

MCP: `trex_note { "externalId": "bw-csv:2026-09-14:txn:42", "text": "reimbursed by work", "asOfN": 1857, "actingUser": "ron" }`

### 2.3 Mark paid — `SETTLE_OCCURRENCE` / `trex_mark_paid`

Mark one commitment's occurrences paid, by due date. The UI calls this **Mark paid** ("paid by
hand").

`POST /api/decisions` body:

```json
{
  "asOfN": 1857,
  "decisions": [
    {
      "action": "SETTLE_OCCURRENCE",
      "actor": "user",
      "user": "ron",
      "commitmentId": "netflix",
      "dueDates": ["2026-10-01"]
    }
  ]
}
```

MCP: `trex_mark_paid { "commitmentId": "netflix", "dueDates": ["2026-10-01"], "asOfN": 1857, "actingUser": "ron" }`

### Anything else — `trex_submit_decisions`

For any action the three tools do not cover (`MARK_EXTERNAL`, `PAIR`, `DISMISS`,
`DECLARE_COMMITMENT`, `RETIRE_COMMITMENT`, …) there is the low-level MCP tool
`trex_submit_decisions`: `{ "decisions": [ { "action": "…", … } ], "asOfN": 1857, "actingUser": "ron", "allOrNone": false }`.
It carries the flat `DecisionDraft` shape and reaches the same path. On the API side there is no
separate call — every action is a draft in the same `POST /api/decisions` body.

---

## 3. Attribution (A2)

**Every write names an `actingUser`** — a **declared active user** (`ron`, `mel`, read from
`refdata().users()`) or `agent`. The server rejects anything else.

- **MCP:** pass `actingUser` on the tool call; an omitted value defaults to the configured `agent`
  user. `McpAttribution` resolves it against the declared active users fresh on each call, then
  stamps every accepted draft `user = actingUser` and forces `actor = "user"`. A draft that names a
  different `user` is rejected outright, so an agent can never silently post as a person.
- **API:** the draft's `user` field is the attribution; the sequencer rejects an unknown user
  (`unknown user '…'`) and a user decision with no user at all.
- **The bot maps the chat identity to the id** (`Ron` → `ron`); **the server records what it is
  told.** Attribution is not authorization — the id is stamped into the decision forever and is never
  reused. There is no auth on the API: the hub trusts the caller to say who is acting.

An MCP host that cannot know the chat identity should **omit** `actingUser` (the write is recorded
under `agent`), never guess a person. A blank `actingUser` is rejected, never defaulted.

---

## 4. Safety

- **Read-only by default.** MCP writes exist only when the hub runs with `TREX_MCP_ALLOW_WRITES=1`;
  without it the write tools are unknown (`-32602`). The bot's default posture is reads only.
- **Identify the person before a write.** Confirm who is asking, map it to a declared user id, and
  pass that as `actingUser`/`user`. When in doubt, do not write — ask.
- **Never expose egress.** `egress-firefly` is not a tool and is never surfaced to the assistant;
  job orchestration stays in the Jobs UI.
- **`/api` and `/mcp` have no authentication of their own.** They inherit the hub's listener
  (loopback by default; LAN behind the perimeter in production). Anyone who can reach the hub can
  read and write, so keep it loopback-only or behind the perimeter whenever writes are enabled.
- **A `409` means re-read.** The view moved past `asOfN`; re-read the brief (or the relevant read),
  rebuild the draft with the fresh `asOfN`, and try again. Never retry a stale write blind.

---

## 5. A worked example

> **Ron says: "mark the Netflix bill paid."**

1. **Read the brief first.** `trex_brief` (or `GET /api/brief`) → the head `asOfN` and the month's
   arrears. This is the `asOfN` the write will carry.
2. **Find the commitment and the occurrence.** `trex_commitments` → locate the `netflix`
   commitment; `trex_commitment_activity { "id": "netflix" }` → the occurrence due `2026-10-01`.
   `trex_ledger` (with a `limit`) → confirm the matching transaction if the person is unsure
   whether it has cleared.
3. **Record the conclusion as Ron.** `POST /api/decisions` (or MCP `trex_mark_paid`):

```json
{
  "asOfN": 1857,
  "decisions": [
    {
      "action": "SETTLE_OCCURRENCE",
      "actor": "user",
      "user": "ron",
      "commitmentId": "netflix",
      "dueDates": ["2026-10-01"]
    }
  ]
}
```

MCP equivalent: `trex_mark_paid { "commitmentId": "netflix", "dueDates": ["2026-10-01"], "asOfN": 1857, "actingUser": "ron" }`.
The write is stamped `user = "ron"`, `actor = "user"`, and lands as one appended `SETTLE_OCCURRENCE`
decision. If the hub answers `409`, someone else moved the log: re-read the brief and rebuild the
draft with the new `asOfN`.
