# V2-ASSISTANT-PLAN.md

**Status:** proposed (2026-10-10) — the family-assistant increment over the MCP server. Follow-on to
`docs/plans/V2-MCP-SERVER-PLAN.md`; `V2-SPEC.md` remains the specification and `AGENTS.md` binds.

**Why.** The MCP server now exposes Trex's read surface and one low-level write tool attributed to
`agent` (`docs/plans/V2-MCP-SERVER-PLAN.md`, built). A *family* assistant needs three things the v1
surface does not have: a write attributed to the **person who asked**, intent-shaped actions a model
can call reliably, and a **compact** read so a chat turn does not pull megabytes of JSON. The operator
chose (2026-10-10): build the durable policy in the hub and expose it through **both** a thin MCP
tool layer and a skill doc, with per-person attribution limited to **declared users + `agent`**.

**Goal.** A client — an MCP host, or the family bot calling the hub directly — can ask small questions,
and can record a conclusion as the right person, without ever becoming a second writer.

**Non-goals.** No new derivation; no second writer; no auth (the hub stays loopback/protected); no
proactive push (pull only — the parked `notify` path is separate); egress stays unexposed.

---

## 1. Decisions (operator, 2026-10-10)

| Id | Question | Resolution |
|---|---|---|
| **A1** | Binding? | **Both** — shared policy in the hub, a thin MCP tool layer, and a skill doc for the bot's direct API calls. |
| **A2** | Attribution? | A write may name a **declared user** (`ron`/`mel`, read from `refdata().users()`) or `agent`; the server rejects anything else. The bot maps the chat identity to the id; the server records what it is told. |
| **A3** | Ergonomic actions? | Three intent-shaped tools over the existing decision path: `trex_categorize` (`PIN`), `trex_note` (`NOTE`), `trex_mark_paid` (`SETTLE_OCCURRENCE`). |
| **A4** | Compact read? | A shared `GET /api/brief` + MCP `trex_brief`: a small snapshot (head, review counts, reconciliation, the month's committed totals and arrears, the newest ingest). |

## 2. Architecture

The action semantics and the attribution rule are **shared policy**; only the transport differs.

```
skill (bot code) ── HTTP ──▶ hub /api/brief, /api/decisions ──▶ sequencer (one writer)
MCP host ── Streamable HTTP ──▶ hub /mcp ── trex_brief / trex_submit_decisions / trex_categorize …
```

- **Attribution** lives in `McpAttribution`/`McpWriteTools` for MCP, and in the decision schema the
  skill doc documents for the API path. The hub already rejects an unknown user
  (`Sequencer.buildDecision`); the MCP layer fails fast with a clear tool error.
- **`trex_brief`** composes existing reads in `HubService`, so the API and MCP share one
  implementation. The bot asks `/api/brief`; the MCP tool wraps it.

## 3. Stages

### Stage A — acting-user attribution and the action tools (MCP)

**Files:** `McpWriteTools.java`, `McpAttribution.java`, `McpConfig.java` (unchanged gate), tests
`McpWriteToolsTest.java`.

- `trex_submit_decisions` gains an optional `actingUser`. Validate it against
  `api.refdata().users()` (active) plus `agent`; default `agent`. `McpAttribution.attribute(decisions,
  actingUser)` still forces `actor="user"` and still rejects a draft naming a different user.
- Add opt-in tools (present only when `TREX_MCP_ALLOW_WRITES=1`), each mirroring `decisions.js`:
  `trex_categorize` (`PIN`: `externalIds[]`, `category`), `trex_note` (`NOTE`: `externalId`,
  `text`), `trex_mark_paid` (`SETTLE_OCCURRENCE`: `commitmentId`, `dueDates[]`). Each takes
  `asOfN` (required, for the staleness check) and optional `actingUser`, builds the draft, and calls
  the same `submitDecisions` path.
- **Acceptance:** `anActingUserMustBeDeclared`, `actingUserIsStamped`, `agentIsTheDefaultActingUser`,
  `everyActionToolBuildsItsDraft`, `anActionToolRequiresAsOfN`, `aSuppliedActorIsForcedToUser`
  (kept).

### Stage B — the compact read (hub + MCP)

**Files:** new `trex-v2-hub/api/BriefResponse.java`; `HubApi.brief()`, `HubService.brief()`,
`HubHttpApi` route `GET /api/brief`; `McpTools` tool `trex_brief`; the `trex_get` allow-list gains
`/api/brief`; tests `BriefTest`/`McpToolsTest`.

- `BriefResponse(long asOfN, List<ReviewCount> review, ReconcileSummary reconcile,
  ExpectedSummary expected, IngestSummary lastIngest)` — small and stable; every number comes from an
  existing read. It is a **view**, rebuildable, storing nothing.
- **Acceptance:** `briefSummarisesTheHeadAndTheMonth`, `briefIsSmallerThanItsParts`,
  `theBriefRouteIsServed`, `trexBriefIsAReadTool`.

### Stage C — the skill doc, notes, archive

**Files:** `docs/ASSISTANT.md` (new); `docs/DEPLOYMENTS.md`; `CHANGELOG.md`; move this plan to
`docs/plans/`.

- `docs/ASSISTANT.md` is the binding-agnostic contract: the small reads to prefer, the three action
  payloads (as the bot would POST them), the attribution rule, and the safety posture (read-only by
  default; identify the person before a write; never egress). It names the MCP equivalents so one
  document serves both bindings.
- **Acceptance (docs):** the doc names each read, each action's JSON, the attribution rule and the
  MCP tool names; DEPLOYMENTS points at it.

## 4. Global constraints

- JDK 25; no new dependency; `mvn -q test` green.
- No second writer: the only write is `HubApi.submitDecisions` (MCP) / `POST /api/decisions` (skill).
- Attribution: a write names a declared user or `agent`; `actor` is forced to `user`.
- `/mcp` and `/api` stay loopback/protected; nothing here adds auth.
- Tests never touch `run/` or a real index.
