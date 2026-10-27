# V2-HIDE-PLAN.md

> **Status:** proposed; not started. `V2-SPEC.md` is the specification; this file is the build order
> for one change to it. Where this file and the spec disagree, the spec wins, and the spec is
> amended in the same change (§7).

**Authority:** `V2-SPEC.md` §3, §5, §6.1/§6.2/§6.8, §7, §9, §11.1; `AGENTS.md`.
**Adds to the decision set:** two actions, `HIDE` and `UNHIDE`.

---

## 1. The problem, in one paragraph

Some rows are real money the operator wants **counted but never seen** — a private gift, a
surprise, a sensitive payment. They must remain journal facts, keep participating in the balance
chain, transfers, commitments, headroom and units, and be **absent from every read surface**: the
trex UI, the MCP read tools, and the Firefly egress. The operator's phrasing: "not in UI anywhere —
but they participate in balance. Just PIN."

## 2. The model's answer: a derived `hidden`, not a fact property

`hidden` is a **sibling of `role`** (§6.2): a per-fact attribute derived from a person's *decision*,
never stored on the fact, never in identity. It is a `HIDE`/`UNHIDE` decision, effective per fact
through the supersession map, latest wins — exactly the shape of `PIN`/`UNPIN`.

- It is a **conclusion about display**, not about money: it changes no economic output. Balance,
  chain resolution, transfer pairing, commitments, headroom and units compute as if it were absent.
- It is **derived**, so a reflow (`trex index --rebuild`) reproduces it, and `REVOKE`/`UNHIDE`
  restores a row with nothing deleted.
- It is **not in the state hash** (§6.8): hiding never invalidates a `USER_ACK`, and no read row's
  hash moves because of it.

## 3. The change

### 3.1 Decision: `HIDE` / `UNHIDE` (new)

| Action | Payload | Meaning |
|---|---|---|
| `HIDE` | `externalIds`, `comment?` | These current facts are hidden from every read surface; they still post. |
| `UNHIDE` | `externalIds`, `comment?` | Undo — the family inverse, like `UNPIN`. `REVOKE` also works. |

A batch may name many ids (one append). Ids resolve through the supersession map. Attributed and
revocable. Latest effective decision per fact wins.

### 3.2 Derivation (§6.1, §6.2)

Alongside `role`, `derive()` computes a boolean **`hidden`** for each current fact: true iff its
latest effective `HIDE`/`UNHIDE` is a `HIDE`. It is carried on `txn_current` (and, transitively, on
whatever a transfer/unit is built from — see §3.3). `deriveVersion` bumps; `hashVersion` does not.

**`HIDE` on a matched transfer leg hides the whole transfer** (a pair is one unit): a transfer row
is hidden iff either of its legs is hidden. Hiding is otherwise allowed on any role.

### 3.3 The presentation rule (the whole work)

The invariant to enforce and test: **a hidden row appears in no hub response.** Economics include
it; presentation never does.

| surface | hidden rows |
|---|---|
| `/api/ledger` (Blotter), `/api/eyeball`, `/api/workbook` | filtered out |
| `/api/review`, `/api/dismissals` | an item is hidden when its subject id is hidden; hidden members are dropped from a shown cluster's member list |
| `/api/notes` | a hidden row's thread is not served |
| `/api/transfers`, `/api/units` | a transfer/unit row is hidden if any leg is hidden |
| `/api/commitments/activity` | hidden facts removed from the series; the commitment's faces, arrears and next-due still count them |
| `/api/expected` | the occurrence still renders (`occurred`), but a hidden `matchedExternalId` is redacted |
| `/api/chains`, `/api/reconcile`, `/api/opening` | arithmetic **includes** them; the row/forks listing omits them, and a fork that references a hidden value redacts it |
| `/api/since`, `/api/brief`, `/api/events` (SSE) | hidden rows **and** `HIDE`/`UNHIDE` decisions omitted |
| `/api/accounts`, `/api/ingests` | coverage counts include them (they are real rows) |
| `/api/status` | table counts and review counts unchanged; a hidden row's review item does not appear in the queue count |
| MCP read tools | the same filters — the MCP is a client of the hub, not a bypass |

`hidden` is therefore a **SQL filter**, not a post-filter: row queries add `AND hidden = 0`, and
subject/id lookups exclude hidden ids.

### 3.4 Headroom counts hidden rows

`hidden` does not touch §9.2: a hidden −$500 still moves "left this month". (An invisible row that
also didn't move the number would be the worse lie.)

### 3.5 Egress withholds hidden rows

The egress reads `/api/units`; hidden units are filtered there, so a hidden row is projected
**nowhere** — not trex, not Firefly. Stated consequence: **Firefly's balances diverge from trex by
the hidden amounts.** (This is the one place "counted here, absent there" is visible; it is the
operator's accepted trade — §8.)

### 3.6 Un-hide is API/CLI only

There is **no UI path back**: a hidden row is invisible everywhere in the web UI. Restoring is
`UNHIDE`/`REVOKE` through `POST /api/decisions` (or the CLI), by id. The Blotter gains a
`Hide` action (row menu, like the other decisions); it does not gain a "show hidden" view.

## 4. Invariant check

- **Facts are what a source said.** Untouched — never hidden on the line, no synthetic fact.
- **Decisions are a person's conclusion**, append-only, attributed, revocable; effectiveness
  derived, never stored. `HIDE` is a conclusion about display; `UNHIDE`/`REVOKE` restores it.
- **Nothing is deleted; every derived table is disposable.** `hidden` is derived, and `--rebuild`
  reproduces it.
- **No `externalId` rewrite; no category on a transaction.** Untouched.
- **`derive()` stays pure.** `hidden` is a function of `(facts, decisions)` like `role`.

## 5. Blast radius

| module | file | change |
|---|---|---|
| core | `Action.java` | add `HIDE`, `UNHIDE` |
| core | `Decision.java` | `Hide(externalIds, comment, actor, user)`, `Unhide(...)` |
| core | `derive/Derive.java` | compute `hidden`; transfer hidden iff a leg is hidden |
| core | `config/DeriveConfig.java` | `deriveVersion` bump |
| log | `LogCodec.java` | encode/decode the two actions |
| sequencer | `api/DecisionDraft.java`, `Sequencer.java` | fields + validation (known ids, non-blank) |
| index | `schema.sql` | `txn_current.hidden INTEGER NOT NULL DEFAULT 0` (and any derived view that carries it) |
| index | `Indexer.java` / `Sql.java` | persist `hidden` |
| hub | `HubSql.java` | `AND hidden = 0` on every row query; id/subject guards |
| hub | `HubService.java` | review/since/brief/events filters; precheck; `/api/units` filter |
| hub | `McpTools.java` | inherits the filtered hub reads |
| egress | `FireflyEgress` / `HubClient` | no code change expected (units already filtered); assert the withholding |
| web | `js/blotter.js` | a `Hide` action in the row menu (no show-hidden view) |
| docs | `V2-SPEC.md`, `CHANGELOG.md` | §7 amendments |

**Tests:** `DeriveTest` (economics byte-identical with and without a hide; transfer hidden iff a
leg is), `LogCodecTest`, `SequencerTest`, `HubDecisionPathTest`, `IndexerTest`
(rebuild ≡ incremental), and a new **`HubHiddenTest`** that hides a fact and asserts it is absent
from *every* read endpoint (and the SSE stream) while the totals are unchanged — the load-bearing
acceptance test. `HubUnitsTest` pins the egress withholding.

## 6. Stages

1. **Core** — actions, decisions, `hidden` in derive; `deriveVersion` bump. Acceptance: hiding
   changes no economic output; a transfer with a hidden leg is hidden; `UNHIDE`/`REVOKE` restores.
2. **Log codec** — round-trip; the golden log still parses.
3. **Sequencer** — draft fields; known ids, else `400`.
4. **Index** — `txn_current.hidden`; `rebuild ≡ incremental`.
5. **Hub** — the §3.3 filters, precheck, the invisible-everywhere test across `/api` + `/events` +
   MCP.
6. **Web** — the `Hide` row action.
7. **Docs and rollout** — spec amendment, changelog, rebuild → host.

## 7. Proposed spec amendments

1. **§3 / §5** — add `HIDE` and `UNHIDE` to the decision action set, with their payloads.
2. **§6.1** — the `hidden` flag; `deriveVersion` bump; note it is not in the state hash.
3. **§6.2 / §7** — `txn_current.hidden`; the presentation rule: hidden rows are filtered from every
   read surface (the §3.3 table), never from the economics.
4. **§9** — the per-endpoint rule (the §3.3 table), including `/api/units` and MCP.
5. **§11.1** — the Firefly effect: hidden units are **withheld**, so Firefly diverges by their sum.
6. **§17** — record the change.

## 8. Decisions taken

1. **Presentation only.** `hidden` never changes balance, transfers, commitments, headroom or units.
2. **A derived column, not a fact property** — a sibling of `role`; reproducible by `--rebuild`.
3. **A pair is a unit** — hiding either leg hides the transfer.
4. **Headroom counts hidden rows** — the number stays honest.
5. **The egress withholds hidden rows** (Firefly diverges by their sum) — an accepted trade.
6. **No UI path back** — restore by `UNHIDE`/`REVOKE` via the API/CLI only.
7. **The MCP reads honour hiding** — no privileged bypass.

## 9. Rollback

Additive: a new action and a re-derivable flag. Rollback is "redeploy the previous image" (all
decisions stay valid lines); `UNHIDE` or `REVOKE` clears a hide without deleting anything.
