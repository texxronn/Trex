# V2-MCP-SERVER-PLAN.md

**Status:** revised (2026-10-10) — **built into the hub** (operator direction; supersedes the earlier
"new module + stdio-only" draft in this file's git history). Waiting on the operator's review of the
reversal, then Stages 1–5.

**Authority.** `V2-SPEC.md` is the specification; `AGENTS.md` binds every worker. This is a follow-on
plan, not a change to the spine: it adds no derivation, no truth and no invariant. It implements the
MCP server the operator parked in `docs/plans/V2-COMMITMENTS-PLAN.md` §12, now placed **inside the
hub** rather than in a separate process.

**Grounding (the *why*, from `V2-COMMITMENTS-PLAN.md` §12).** A thin server over the hub's **own read
surface** — never the journal or the index directly; read tools first; a single `submit_decisions`
write tool that posts drafts through the existing decision path, so prechecks, staleness (`409`) and
attribution hold. An agent acting for the person is a **distinct, attributed user** (an `agent` entry
in `users.yaml`), never silently `ron`; write tools are opt-in. This plan supersedes the paragraph's
"stdio (or loopback HTTP)" transport note: the operator chose **into the hub**, so the hub is the
server.

**Goal.** The hub exposes an MCP endpoint so an MCP client (Claude Desktop, an IDE agent, a script)
can read Trex's derived state and — only when the operator opts in — record the person's conclusions
through the same path the UI uses.

**Non-goals (v1).** No separate MCP module or daemon; no journal/index access outside `HubApi`; no new
derivation; no background writes; no Firefly/egress control; no multi-user auth (the hub is already
loopback/single-operator). The MCP surface is layered on the existing hub server.

---

## 1. Architecture

```
MCP client ── Streamable HTTP POST /mcp ──▶ hub :8090 ──▶ HubApi (in-process) ──▶ index
     │                                          └── submitDecisions ──▶ sequencer (one writer)
     └── or: stdio ──▶ trex mcp (bridge) ── HTTP POST ──▶ hub :8090/mcp
```

- **Code lives in `trex-v2-hub`**, in the `trex.v2.hub` package (`HubApi` is package-private, so the
  MCP classes must share it). New files: `McpApi.java` (the core: JSON-RPC dispatch + tools,
  resources, prompts), `McpHttp.java` (the Streamable HTTP binding, mounted by `HubHttpApi`), and
  `McpConfig.java` (the write gate).
- **Transport A — Streamable HTTP.** The hub's existing `HttpServer` gains a `/mcp` context:
  one JSON-RPC request per `POST`, answered `application/json`. **Sessionless and lenient**: it
  answers an `initialize` (2025-03-26…2025-11-25) *and* a self-contained stateless request
  (2026-07-28), because neither era's mandatory shape is assumed. `GET`/`DELETE` return `405`
  (no server-initiated SSE in v1). Notifications return `202`.
- **Transport B — stdio bridge.** A tiny `trex mcp` subcommand (`trex-v2-dist`) reads newline-
  delimited JSON-RPC from stdin and forwards each message to `${TREX_HUB_URL}/mcp`, writing the
  response to stdout. It holds no logic; stdio-only clients (Claude Desktop) get the hub's server.
- **Reads** call `HubApi` directly (no HTTP round-trip): `status`, `ledger`, `review`,
  `commitments`, `expected`, `accounts`, `opening`, `reconcile`, `transfers`, `workbook`, `notes`,
  `units`, `chains`, `ingests`, `configDrift`, `refdata`.
- **Writes** call `HubApi.submitDecisions(DecisionRequest)`, the same path as `POST /api/decisions`
  — the hub keeps being the read layer and the sequencer the only writer.

## 2. Operator decisions

| Id | Question | Resolution | Why |
|---|---|---|---|
| **D1** | Transport? | **Streamable HTTP on the hub**, lenient across the 2025 and 2026 protocol eras; plus a `trex mcp` stdio bridge. | Operator: "into the hub". The hub already runs an `HttpServer`; the bridge covers stdio-only clients without a second implementation. |
| **D2** | Protocol implementation? | **JDK + Jackson**, hand-rolled JSON-RPC. No new dependency. | AGENTS.md "JDK-only … no frameworks"; the recorded intent says the same. |
| **D3** | Write shape? | **One `submit_decisions` tool** posting `DecisionRequest`; no per-intent wrappers in v1. | Keeps prechecks, `409` staleness and attribution in one path (recorded intent). |
| **D4** | Attribution? | A dedicated **`agent`** user in `users.yaml`; the tool forces it and rejects another. | Attribution is permanent; an agent is not the person. |
| **D5** | Job control? | **Read-only** jobs view in v1; job starts opt-in in Stage 5; **`egress-firefly` is never a tool**. | A job start is a side effect; egress apply is destructive (D8). |
| **D6** | Tool surface? | **Typed tools per domain** + an allow-listed read-only `trex_get` passthrough. | An LLM picks typed tools more reliably. |
| **D7** | Placement? | **Inside `trex-v2-hub`**, served by the hub; no new module. (`HubApi`'s package-private visibility forces the same package.) | Operator: "into the hub". |
| **D8** | Process model? | **In-process `HubApi`**; no separate MCP process, no index access from outside the hub. | The hub already owns the index and the writer proxy. |

## 3. Surface (v1)

### 3.1 Read tools (always enabled)

Each maps to a `HubApi` method (the same data the hub's HTTP GETs serve). Input schemas mirror the
existing query parameters.

| Tool | `HubApi` call | Parameters |
|---|---|---|
| `trex_status` | `status()` + `head()` | — |
| `trex_units` | `units()` | — |
| `trex_ledger` | `ledger(BlotterQuery)` | `account, category, leg, role, direction, from, to, q, minAmount, maxAmount, hasReview, sort, order, limit, offset` |
| `trex_review` | `review(kind, account)` | `kind?, account?` |
| `trex_commitments` | `commitments()` | — |
| `trex_commitment_activity` | `activity(id)` | `id` |
| `trex_expected` | `expected(window, asOf)` | `window?, asOf?` |
| `trex_accounts` | `accounts(window, granularity, asOf)` | `window?, granularity?, asOf?` |
| `trex_opening` | `opening()` | — |
| `trex_reconcile` | `reconcile()` | — |
| `trex_transfers` | `transfers()` | — |
| `trex_workbook` | `workbook()` | — |
| `trex_notes` | `notes(externalId)` | `externalId` |
| `trex_chains` | `chains(account)` | `account?` |
| `trex_since` | `since(n, user)` | `n`, `user?` |
| `trex_ingests` | `ingests(sinceN)` | `sinceN?` |
| `trex_config_drift` | `configDrift()` | — |
| `trex_projection` | `projection()` | — |
| `trex_get` | allow-listed | `path` — only the read routes above |

### 3.2 Write tools (opt-in: `TREX_MCP_ALLOW_WRITES=1`)

| Tool | `HubApi` call | Note |
|---|---|---|
| `trex_submit_decisions` | `submitDecisions(DecisionRequest)` | Body `{ allOrNone?, asOfN, decisions: [DecisionDraft…] }`. The tool **requires `asOfN`** (from the read the draft was based on), so a stale write is a `409`, never a silent mis-post. `user` is forced to the agent; any other `user` is rejected. |
| `trex_ack` | `postAck(AckRequest)` | Stage 5, same gate. |
| `trex_start_job` | hub `/api/jobs/*` proxy | Stage 5, same gate; **`egress-firefly` excluded**. |

With writes off, these tools are absent from `tools/list` and `tools/call` returns an error.

### 3.3 Resources

- `trex://config/{accounts,categories,transfers,firefly,profiles}` — the live config files (the hub
  has the config dir), text/yaml.
- `trex://status` — a snapshot (`status()` + `head()`); `trex://reference` — `refdata()`.
- (later) `trex://events` — a subscription over the hub's SSE `/api/events`.

### 3.4 Prompts

`monthly_review`, `classify_transaction`, `reconcile_account`, `mark_paid` — each returns messages
that instruct the client to call the read tools and propose `trex_submit_decisions` drafts.

## 4. Safety and invariants

- **Never the journal or the index directly.** The MCP layer is inside the hub and uses `HubApi`; it
  adds no writer. Every write is `submitDecisions`, so `derive()`, the one-writer rule and every
  precheck are untouched.
- **Read-only by default.** Write tools are absent unless `TREX_MCP_ALLOW_WRITES=1`.
- **Staleness preserved.** `submit_decisions` refuses a draft without `asOfN`; the hub's `409` is
  returned verbatim.
- **Attribution is the agent's.** The configured agent user is stamped; `ron`/`mel` are rejected.
- **Egress is out.** `egress-firefly`, `--remove-orphans` and Firefly writes are never MCP tools.
- **Loopback only.** The hub binds `127.0.0.1` (dev publishes it that way); MCP rides that, no new
  listener.

## 5. Stages

### Stage 1 — the MCP core and the `/mcp` endpoint

**Files:** create `trex-v2-hub/src/main/java/trex/v2/hub/McpApi.java` (JSON-RPC dispatch;
`initialize`, `server/discover`, `ping`, `tools/list`, `tools/call`), `McpHttp.java` (Streamable HTTP
context), `McpConfig.java` (the write gate); modify `HubHttpApi.java` (mount `/mcp`),
`HubService.java` (pass the gate through); tests `McpApiTest.java`, `McpHttpTest.java`.

**Interfaces:**
- `McpApi.dispatch(JsonNode request) -> JsonNode response` — pure; unknown method → error `-32601`;
  malformed → `-32700`; a `tools/call` with bad arguments → a tool result with `isError:true`.
- `McpHttp.handle(HttpExchange, HubApi, McpConfig)` — `POST` only; `202` for notifications; `405`
  otherwise; `Content-Type: application/json`.
- `HubHttpApi.start(...)` gains the MCP context (reads `TREX_MCP_ALLOW_WRITES`).

**Acceptance:** `initializeNegotiatesAProtocolVersion`, `aStatelessRequestIsAnsweredWithoutInitialize`,
`anUnknownMethodIsMethodNotFound`, `malformedJsonIsParseError`, `toolsListIsStable`,
`aToolErrorBecomesAToolResultNotACrash`, `getAndDeleteAreNotAllowed`.

### Stage 2 — the read tools

**Files:** `trex-v2-hub/.../McpTools.java` (one method per `HubApi` call + schema), tests
`McpToolsTest.java` (a `HubApi` stub).

**Acceptance:** `everyReadToolCallsTheHubApi` (one per table row), `aLedgerQueryKeepsItsFilters`,
`theGetPassthroughIsAllowListed`, `limitsAreClampedLikeTheHub`.

### Stage 3 — resources and prompts

**Files:** `McpResources.java`, `McpPrompts.java`; tests `McpResourcesTest.java`.

**Acceptance:** `configResourcesAreReadFromTheConfigDir`, `aMissingConfigFileIsNotAnError`,
`promptsListIsStable`, `aPromptReturnsItsMessages`.

### Stage 4 — the write tool (opt-in, attributed)

**Files:** `McpWriteTools.java`, `McpAttribution.java`; tests `McpWriteToolsTest.java`. The
`DECISION` schema mirrors `DecisionDraft`.

**Acceptance:** `writeToolsAreHiddenUnlessOptedIn`, `submitDecisionsRequiresAnAsOfToken`,
`aStaleAsOfSurfacesTheHubs409`, `attributionForcesTheAgentUser`,
`aDraftNamingAnotherUserIsRejected`, `egressIsNotATool`.

### Stage 5 — the stdio bridge, jobs, docs, archive

**Files:** `trex-v2-dist/.../cli/McpCommand.java` (+ register in `Main`); `McpJobTools.java`;
`docs/DEPLOYMENTS.md` (the `/mcp` URL, the `trex mcp` command, the client config JSON, the
`TREX_MCP_ALLOW_WRITES` gate, the agent user); `CHANGELOG.md`; add `agent` to
`deploy/config/users.yaml`; move this plan to `docs/plans/`.

**Acceptance:** `theStdioBridgeForwardsJsonRpcToTheHub`, `jobsAreListed`, `startingAJobIsHiddenUnlessOptedIn`,
`egressApplyIsNeverExposed`, `theAgentUserIsDeclared`.

## 6. Global constraints

- JDK 25 at `~/Tools/JDK/jdk-25.0.4.1+1/`; Maven multi-module; `mvn -q test` green from the root.
- No new dependency. Jackson and the JDK `HttpServer`/`HttpClient` only.
- The MCP layer is inside `trex.v2.hub` (it needs package-private `HubApi`); it must not become a
  second writer — the only write is `submitDecisions`.
- One protocol version advertised (`2026-07-28`), but the dispatcher answers the 2025 `initialize`
  handshake too; no session id, no SSE in v1.
- Tests never touch `run/` or a real index; the `HubApi` stub and an ephemeral `HttpServer`.
- `AGENTS.md` gains: "A new MCP tool states which hub method it wraps and whether it writes."

## 7. Review focus (acceptance scenarios)

1. **A stale read, then a decision.** Read the review list, wait for an ingest, submit a draft with
   the old `asOfN` → `409`, surfaced to the client, nothing written. (Stage 4.)
2. **An agent that tries to be Ron.** A draft with `user:"ron"` → rejected; the log shows `agent`.
   (Stage 4.)
3. **A 2025 client.** `initialize` → capabilities; `notifications/initialized` → `202`; `tools/list`
   works. **A 2026 client.** A stateless `tools/call` with `_meta` and headers works with no
   `initialize`. (Stage 1.)
4. **Writes off.** No write tool is advertised; `tools/call` on one is refused. (Stage 4.)
5. **A malformed call.** Missing `externalId` on `trex_notes` → a tool error, never a crashed
   server. (Stage 2.)

## 8. Out of scope (parked)

- SSE streams, resource subscriptions, sampling, elicitation; multi-round-trip requests.
- Auth tokens (the hub is loopback-only); a hosted/hypertext MCP endpoint.
- Per-intent ergonomic write tools; job automation; Firefly/egress control.
