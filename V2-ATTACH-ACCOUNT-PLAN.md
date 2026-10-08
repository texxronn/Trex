# V2-ATTACH-ACCOUNT-PLAN.md

> **Personal project, single operator, private.** `V2-PROPOSAL.md` is the specification; this file
> is the build order for one change to it. Where this file and the proposal disagree, the proposal
> wins; the proposal is amended in the same change (§7).

**Status:** implemented; build and tests green; local stack redeployed and the 154 legs attached.
**Authority:** `V2-PROPOSAL.md` §6.2, §6.7, §6.10, §9.4, §9.8, §9.9.C, §11, §14; `AGENTS.md`.
**Adds to the decision set:** one action, `ATTACH_ACCOUNT` (§6.2; §7.1).

---

## 1. The problem, in one paragraph

154 `UNMATCHED_LEG` items are transfers between our own accounts whose contra history was **pruned
and is unobtainable** — card payments to `bw-credit-card` (2019-10…2023-12; the card's statements
begin 2024-01-02) and Oskos to `cba-smartaccess` (2023-09…2024-09; its statements begin
2024-10-02). They are genuinely transfers. Dismissing misrepresents them (a dismissal is not a
`TRANSFER`); synthetic facts would fabricate observations — and the balances — we do not have.

## 2. The model's answer: an account side, not a synthetic leg

A **clearing account** (§6.10) already represents a counterparty whose statements are gone: a leg
pairs **directly with the account**, the transfer is **one real leg and one account side**, there is
**no contra fact**, and the account's opening is computed backwards from a declared closing. That is
the honest proxy for a pruned ledger, and it *is* a `TRANSFER` and is projected.

What §6.10 lacks is reach: it is a **pattern** property, and patterns are date-blind, so it cannot
target only the pre-coverage legs of an *open* account. This plan keeps the mechanism and adds an
**id-scoped** way to invoke it.

**Materialise the clearing side as a derived leg.** §6.10 leaves the clearing side as an *account*,
not a leg, so a clearing transfer has one row and per-account transaction queries are asymmetric. We
extend §6.10: emit, per clearing pairing, a **derived** leg in the clearing account — never a fact:
no journal line, no evidence, never a decision target. Then every transfer has **two concrete legs**,
`txn_current` is **complete per account**, and the projection mirrors Firefly, which already models a
transfer as two transactions. The clearing leg's running balance is derived (`opening + Σ movements`)
and lands on the declared closing, so the position is computed, never chained; the account stays
`CLEARING` (no source chain to break).

Every transaction row carries a derived **`synthetic`** boolean: **false** for a mirrored fact,
**true** only for a clearing leg. It is an invariant that a **clearing account accepts only synthetic
rows** (it holds no facts) and that a **real account never carries a synthetic row**. The flag is
derived — never a journal field, never in identity — and is what queries and the projection key off.

## 3. The change

### 3.1 Config: legacy clearing accounts

Declare one clearing account per pruned counterparty, with `closingBalance` = the real account's
balance at its coverage start (the handover point) and `closedAt` that date, so no movement is
expected after it.

```yaml
accounts:
  - ref: "bw-card-legacy"        # the BankWest card before we hold statements
    currency: "AUD"
    balanceSource: clearing
    closingBalance: <the bw-credit-card balance at 2024-01-02, cents>
    closedAt: "2024-01-01"
  - ref: "cba-legacy"            # the CBA SmartAccess account before we hold statements
    currency: "AUD"
    balanceSource: clearing
    closingBalance: <the cba-smartaccess balance at 2024-10-02, cents>
    closedAt: "2024-10-01"
```

The computed opening (`closing − Σ movements`) absorbs the pre-history — exactly its purpose —
and is shown in Accounts/reconcile, never silent.

### 3.2 Decision: `ATTACH_ACCOUNT` (§6.2, new)

| Action | Payload | Meaning |
|---|---|---|
| `ATTACH_ACCOUNT` | `externalIds`, `account`, `comment?` | These transfer-shaped legs are transfers to/from `account`; the contra is not held. |

A batch may name many ids (one append). It is a **conclusion**, not an observation; attributed,
revocable with `REVOKE`, and id-scoped so it lands only the pre-coverage legs and leaves the 2024+
matching intact.

### 3.3 Derivation (§6.10 generalized)

- An effective `ATTACH_ACCOUNT` pairs each named leg with the named account as an **account side**,
  overriding the matcher — the same shape the config `clearing:` produces. The leg becomes
  `TRANSFER` and stops being a unit of its own.
- **The clearing leg is materialised (derived).** For each clearing pairing — config `clearing:` or
  decision `ATTACH_ACCOUNT` — emit a derived leg in the clearing account: the opposite amount, the
  real leg's date, `role: transaction`, `transfer_id` = the pairing, `synthetic: true`, and a running
  balance computed from the account's declared closing. It carries a reserved, clearly-synthetic id
  (e.g. `clr|<clearing>|<realLeg>`), and is excluded from: the journal, evidence, decision targets,
  identity, and the source chain. It exists so a transfer is uniformly two legs and per-account
  queries are complete. The invariant holds in both directions: a clearing account holds only
  synthetic rows; a real account holds none.
- Latest effective decision wins per leg; a later `PAIR`/`MARK_EXTERNAL` overturns it; `REVOKE`
  releases it back to the matcher. Ids resolve through the supersession map (§9.4).
- The account side carries **no fact and no source balance**, so the account's *source* chain is
  unaffected; its derived balance lands on the declared closing.
- Precedence and effectiveness are the ordinary §9.8 rules; the sequencer validates references and
  structure only (known ids, a known account, non-blank) — never semantics.

### 3.4 Projection (Firefly)

The one-sided transfer is a unit and is projected. The egress provisions the clearing account with
the computed opening and posts the transfer; Firefly shows the counterpart transaction (its model
of a transfer is two transactions) and its balance lands on the declared closing. trex writes no
fact; the `clearing_account` field on the transfer row already carries the account side.

### 3.5 Retrofit

If a real statement for a pruned period ever turns up, ingest it and `REVOKE` the `ATTACH_ACCOUNT`
decisions for those ids; the next reflow re-pairs against real legs and the computed opening
shrinks to nothing. Nothing is deleted and nothing is edited.

## 4. Invariant check

- **Facts are what a source said.** No synthetic fact, no invented balance.
- **Decisions are what a person concluded**, append-only, attributed, reversible; effectiveness is
  derived, never stored.
- **Nothing is deleted; every derived table is disposable; one writer, one atomic append.** Untouched.
- **No `externalId` rewrite; no category on a transaction.** Untouched.

## 5. Blast radius

| Module | File | Change |
|---|---|---|
| core | `Action.java` | add `ATTACH_ACCOUNT` |
| core | `Decision.java` | add `AttachAccount(externalIds, account, comment, actor, user)` |
| core | `derive/Derive.java` | apply effective `ATTACH_ACCOUNT` when pairing: an account side, like `clearing:` |
| core | `config/DeriveConfig.java` | `DERIVE_VERSION` bump (derivation semantics change) |
| log | `LogCodec.java` | encode/decode `AttachAccount` |
| sequencer | `api/DecisionDraft.java`, `Sequencer.java` | `account` field; validate known ids + known account |
| index | `Sql.java` / `Indexer.java` | `txn_current` gains `synthetic`; the transfer row already carries `clearing_account` (account side) — reuse |
| index | `resources/…/schema.sql` | `txn_current.synthetic INTEGER NOT NULL DEFAULT 0` |
| hub | `api/LedgerRow.java` | add `synthetic` (so the Blotter can render clearing legs as derived) |
| hub | `HubService.java` | precheck: ids exist, account exists, non-blank; a transfer-shaped subject where required |
| hub web | `js/decisions.js` | `attachAccount(ctx, ids, account, comment)`; a Review action |
| deploy | `deploy/config/accounts.yaml` | the two legacy clearing accounts (§3.1) |
| docs | `V2-PROPOSAL.md`, `V2-SPEC.md`, `CHANGELOG.md` | §7 amendments |

Tests: `core/DeriveTest`, `log/LogCodecTest`, `sequencer/SequencerTest`, `hub/HubDecisionPathTest`.

## 6. Stages

1. **Core** — `Action`, `Decision.AttachAccount`, `derives` pairing; `deriveVersion` bump.
   Acceptance: an attached leg is a `TRANSFER` with the named account as the account side; the
   account's chain is unchanged; `REVOKE` returns it to the matcher; a `SUPERSEDE` carries it.
2. **Log codec** — round-trip; the golden log still parses.
3. **Sequencer** — `account` in the draft; a known id and a known account, else `400`.
4. **Hub** — precheck; a **Review** action to attach a whole cluster/selection to an account; the
   `decisions` helper.
5. **Config** — the two legacy clearing accounts; apply one batch of `ATTACH_ACCOUNT` to the 154
   legs; confirm `UNMATCHED_LEG` falls and the transfers appear.
6. **Egress check** — `--plan` shows the one-sided transfers and the provisioned clearing opening.
7. **Docs and rollout** — as-built, changelog, proposal amendment; local rebuild → host.

## 7. Proposed proposal amendments

1. **§6.2 / §6.7** — add `ATTACH_ACCOUNT` (`externalIds`, `account`, `comment?`).
2. **§6.10** — state that (a) the account side may be chosen by an `ATTACH_ACCOUNT` decision, not
   only by a pattern's `clearing:` (a decided pairing is for a pruned *period* of an open account,
   which a date-blind pattern cannot isolate); and (b) the clearing side is materialised as a
   **derived leg** — no fact, no evidence, not a decision target — so a clearing transfer has two
   concrete legs and per-account queries are complete. The account stays `CLEARING` (computed
   opening, declared closing, no source chain).

## 8. Decisions taken

1. **Landing zone: clearing accounts** (config), for the two pruned periods; the leg pairs with the
   account as an account side — **no synthetic leg, no synthetic fact**.
2. **Id-scoped decision, not config**, to choose *which* legs land there — patterns are date-blind.
3. **Firefly shows the counterpart** because it models a transfer as two transactions; the egress
   provisions the clearing account and posts the transfer. trex invents nothing.
4. **Not synthetic facts**: they would freeze invented ids and invented balances and collide with any
   future statement.
5. **`synthetic` is a derived boolean on the transaction row**, not a journal field: false for a
   mirrored fact, true only for a clearing leg. A **clearing account accepts only synthetic rows**; a
   real account carries none. Individual, per-id — never a rule. It generalises the existing
   `clearing:` accounts (westpac-card, nab-fixed, nab-offset), which also gain two-legged transfers.

## 9. Rollback

Additive: a new decision action and a re-derivable pairing. Rollback is "redeploy the previous image"
(all decisions remain valid lines); `REVOKE` removes the pairing without deleting anything.
