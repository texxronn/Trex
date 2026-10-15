# V2-EZB-VIEW-PLAN.md

> **Personal project, single operator, private.** `V2-SPEC.md` is the specification; this file is the
> build order for one change to it. Where this file and the spec disagree, the spec wins; the spec is
> amended first (Stage 2), per `AGENTS.md`.

**Status:** Stage 0/1 ran as a **live spike** on 2026-10-15 against a real
`mayswind/ezbookkeeping:2.0.1` container: trex's whole book (5701 units + 21 openings) was re-fed
over the HTTP API and **reconciles to the cent, idempotently** (§9). The decision below is taken
provisionally: it stands unless the operator's Stage 0 judgement of the charts goes against it.
Nothing has been built in `trex-v2-egress` yet.

**Decisions (operator, 2026-10-15):** replace Firefly III as the downstream with ezbookkeeping, used
purely as a chart view (trex owns ingest, categories, review and day-to-day reconciliation). The
Firefly egress stays in the tree **dormant** (unscheduled, not deleted). Budgets: out of scope,
revisited later. The egress is Java in `trex-v2-egress`, JDK-only; ezbookkeeping (Go) is reached over
its HTTP API only.

**Authority:** `V2-SPEC.md` §11 (egress), §12 (config); `AGENTS.md`.

---

## 1. The problem

Firefly III was chosen as the downstream to own charts, reports and budgets. Trex has since grown to
cover most of what Firefly does, and Firefly's charts are weak. The only thing wanted downstream now is
**better visualization**. ezbookkeeping (mayswind, MIT, Go, v2.0.1 2026-09-25; SQLite/MySQL/PG; one
container) offers Sankey, category and balance trends, a custom chart builder, Insights Explorer and a
calendar view.

## 2. What ezbookkeeping offers (verified against a real 2.0.1 instance, 2026-10-15)

Everything below was checked against the running container and its source, not the docs alone.

- **No `external_id`, no duplicate hash.** The only re-submission guard is `duplicate_checker`
  (`in_memory`, `duplicate_submissions_interval` default 300 s), keyed by the `clientSessionId` the
  caller supplies; it does not survive a restart.
- **Auth.** API tokens (`[security] enable_api_token`, env `EBK_SECURITY_ENABLE_API_TOKEN`,
  `Authorization: Bearer`) reach accounts, categories, tags and the import route. **`data/clear/*`
  refuses an API token** (`202007 token is invalid`) and needs a **normal login token plus the
  account password**. The login route is **`/api/authorize.json`** — *outside* the `/api/v1`
  namespace — and its body is **`{"loginName","password"}`** (`username` silently 401s).
- **Namespace.** Every other route is `/api/v1/<path>.json`. Relevant ones verified present:
  `accounts/list|add|modify`, `transaction/categories/list|add|add_batch`, `transaction/tags/…`,
  `transactions/list|list/all|add|modify|delete|batch_delete|import|import/process|parse_import`,
  `data/clear/transactions`, `data/clear/transactions/by_account`, `data/clear/all`,
  `tokens/generate/api`, `tokens/generate/mcp`.
- **Import.** `POST /api/v1/transactions/import.json` takes
  `{transactions: [TransactionCreateRequest], clientSessionId}` and is **asynchronous**: it returns
  immediately and progress is read from `GET transactions/import/process.json` (`null` when idle).
  Every write needs **`X-Timezone-Name` and `X-Timezone-Offset`** headers or it is refused
  (`200008 client timezone offset is invalid`).
- **`TransactionCreateRequest`:** `type` (1 balance modification, 2 income, 3 expense, 4 transfer),
  `categoryId` (**a secondary/child category, required** — a primary one is refused, `206005`),
  `time` (unix), `utcOffset` (minutes), `sourceAccountId`, `destinationAccountId` (**optional**),
  `sourceAmount` / `destinationAmount` (int64 **hundredths**), `tagIds`, `comment` (≤ 255).
- **Two different enums.** *Transaction* type is 1/2/3/4; *category* type is **1 income, 2 expense,
  3 transfer**. They are not the same numbering, and the category tree is per category type.
- **Provisioning.** `accounts/add.json` requires `name`, `category`, `type`, **`icon`** and
  **`color`**, `currency` — `icon` is an **int64 serialized as a string, min `"1"`**, `color` a 6-hex
  string (both new obligations the first draft missed). `balance` is a **hundredths string**
  (`"417628"`, not `"4176.28"`) and, with `balanceTime`, **creates a type-1 balance-modification
  transaction** — the opening is not a field, it is a row. `accounts/modify.json` **refuses a balance
  change** ("not supported to modify account balance"). Category add likewise needs `icon`/`color`,
  and a child under a primary (`parentId: "0"` makes the primary).
- **Account categories:** 1 cash, 2 checking, 3 credit card, 4 virtual, 5 debt, 6 receivables,
  7 investment, 8 savings, 9 CD. Negative balances (cards, loans) are set with a negative type-1 row.
- **Clearing data.** `data/clear/transactions.json` keeps accounts, categories, tags and saved
  explorations and deletes only transactions (**which resets every account balance to 0**, since the
  opening was a row); `data/clear/all.json` also deletes categories, tags and Insights explorations.
  Both require the login token + password.
- **Missing:** budgets, splits.

## 3. The model: a disposable view, not a converging mirror

Nobody edits ezbookkeeping, so every sync **clears its transactions and re-imports all current units
plus every opening**. Accounts and categories are *ensured* (created when missing, never deleted), so
the operator's saved charts and explorations, which reference stable ids, survive every sync.

That drops the whole §11.1 machinery: no external ids, ownership tags, fingerprints,
`projection_state`, re-keying, orphans or `--validate`. Superseded ids, kind changes (cash), the
re-keying of clearing pairs and content edits never arise; each sync is a fresh projection. Clearing
*units* still exist and map like any transfer, with the clearing ref as an ezbookkeeping account.
It is the invariant "every derived table is disposable" applied downstream.

**Input** is `HubClient.units(0)`, the same feed Firefly uses (`HubClient.java:48`). Legs, HELD,
PENDING and attestations are already excluded in `Derive.units()` (`Derive.java:2244`) and
`HubQueries.projectionUnits()` (`HubQueries.java:1191`). **No derive or identity change.** The one
hub change is the transfer-direction fix in §4, a genuine hub-contract gap the spike exposed; it is
settled first in Stage 2, with its Firefly impact stated.

**The units feed and `/api/opening` disagree about "now".** `Derive.units()` is the `asOf` view and
drops facts dated after `asOf`; `/api/opening`'s `latestBalance` is the whole statement chain and
keeps them. The spike measured one such row ($250) and exactly a $250 gap. **Verify must therefore
compare against a units-consistent expectation** (`backwardOpening + Σ units`), never `latestBalance`.

**Not atomic.** Clear-then-import leaves a partial view if it fails midway; the next apply heals it.
Acceptable for a view, and the spec says so.

## 4. Mapping (pure: unit → `TransactionCreateRequest`)

- **EXTERNAL, amount < 0** → expense (type 3); **amount > 0** → income (type 2). Both use
  `sourceAccountId` = the trex account and **omit** `destinationAccountId`; `sourceAmount = |cents|`.
  A zero-amount EXTERNAL row is skipped (none exist in the real book, but define it).
- **TRANSFER** → transfer (type 4); `sourceAmount` = `destinationAmount` = |cents|;
  `sourceAccountId` = the unit's `accountRef`, `destinationAccountId` = its `toAccountRef`.
  **Direction is the negative leg → the positive leg.** Today the units feed does *not* guarantee
  that: the matcher builds `TransferRow(f, g)` in iteration order, not by sign (`Derive.java:486`
  PAIR decision, `:686` T1, `:698` T2; only `pairClearing`, `:676`, orders by sign), and
  `HubQueries.projectionUnits()` (`:1247`) copies that order into `accountRef`/`toAccountRef`. The
  spec never says which leg is "from". The spike's 7 wrong balances came from this (§9), and the
  Firefly projection carries the same bug (`Projection.java:48`, `source_id = own`). **Fix in the hub,
  Stage 2:** `projectionUnits()` orients each matched pair by sign (swap when `from.amount() > 0`),
  and the spec records "a TRANSFER unit's `accountRef` is the paying leg's account". One read-model
  line, no new field, no derive change; the egress then trusts the unit and needs no `/api/ledger`
  read. *Firefly impact:* `unitHash` changes for every flipped transfer, so the dormant projection
  would see them as content updates (which it does not yet write — see the Firefly egress gaps).
- **Category:** trex master category → ezbookkeeping **secondary** category under one primary per
  category type (`trex-income`, `trex-expense`, `trex-transfer`). The child is mandatory; a primary
  is refused. `UNCATEGORIZED` is a real category.
- **Opening.** Every account's opening is **a type-1 row, in every import** — `sourceAmount` =
  `backwardOpening`, dated before the first unit. It cannot be a creation-only field (the clear
  deletes it) and cannot be an `accounts/modify` change (refused). A **declared** wallet has no
  statement chain, and its backward figure leaves `cash-ron` showing its unrecorded spend instead of
  its attested balance (the spike). Its opening is **`latestCount − Σ units dated ≤ countDate`**,
  posted before the first unit like any other opening — *not* the count itself, which the later
  units would add to again. The alternative (a type-1 row at each count date, absorbing the gap) is
  rejected unless S7 shows a type-1 amount *sets* the balance rather than adding a delta; the
  spike did not establish which. Whatever the rule, verify (§5) uses the same expectation.
- **Account category** per ref: cash (1) for `cash-ron`; credit card (3) for `*-credit-card`; debt (5)
  for loans/facilities and any clearing ref whose computed opening is negative; savings (8) for
  savings; checking (2) otherwise.
- **Time:** the unit date at 12:00 in the configured zone (`Australia/Sydney`); `utcOffset` derived
  **per date** (not "now") so DST cannot shift a day.
- **Comment:** `MerchantStem.stem(raw) — Clean.clean(raw)`, cut to 255.
- **Reuse:** amount helpers (`firefly/Projection.java:144`), `MerchantStem`, `Clean`, the `AccountMap`
  YAML shape (`firefly/AccountMap.java:30`), the preflight idea (refuse on an unmapped ref,
  `FireflyEgress.java:270`), the `Progress` printer, `FakeHub`, and the `FakeFirefly` pattern for a
  `FakeEzb`.

## 5. Run modes (same plan / apply / verify vocabulary)

- **plan:** fetch units; report unmapped refs, accounts and categories to create, row counts per type.
  No writes.
- **apply:** ensure accounts and categories, then clear transactions, then import in batches (openings
  first), then poll `import/process`. Every apply uses a **fresh `clientSessionId`** (a UUID): the
  duplicate checker refuses a reused one for 300 s, so a second apply inside five minutes would
  otherwise be dropped. The poll returning `null` does not mean success — apply ends by listing the
  transaction count and **fails (exit 1) unless it equals the rows sent**. Idempotent: a second apply
  reproduces the same view.
- **verify:** each mapped ezbookkeeping account balance against **`opening + Σ units`** — the
  backward opening, or the declared-wallet opening of §4 — never `latestBalance` (§3); plus row
  counts. A non-empty diff exits 1. Nothing to repair but re-apply.

## 6. Stages

### Stage 0 — trial without code (operator)

Gate: you prefer its charts. **Done** in effect — the spike below was run as a direct API re-feed
rather than the Firefly-CSV route, which also removed the trial's drift. The remaining judgement is
the operator's: Sankey, category trends, balance trends, Insights Explorer, calendar; card and loan
balances; refunds (income, or net spending?).

### Stage 1 — spike (scratchpad, dev ezbookkeeping container; findings in §9)

- **S1 — clear semantics.** `clear/transactions` keeps accounts, categories, tags and explorations but
  **resets balances to 0**; openings must be re-posted each import (type-1 rows); `accounts/modify`
  refuses balance changes. **Answered.**
- **S2 — import throughput.** 5718 rows (12 batches: 11 × 500 + 218) in ~2.5 s; async with a
  process poll. **Answered.**
- **S3 — token scope.** API token: accounts/categories/import yes; `clear/*` no (needs the login token
  + password). **Answered.**
- **S4 — cards and loans.** Modelled as credit-card (3) / debt (5) with negative type-1 openings;
  transfers across them reconcile. **Answered.**
- **S5 — refunds.** An income row is accepted; whether a refund should *net* against its charge is a
  chart-fidelity decision, not an API one — carry it to Stage 2 as a gate.
- **S6 — clearing without the password (open).** `clear/*` needs the login token + account password,
  so `EZB_PASSWORD` would live in the env. `transactions/list/all` + `transactions/batch_delete` are
  present but untried with an **API token**. If they work, apply deletes by id and the password is
  never stored; check before Stage 4.
- **S7 — type-1 semantics (open).** Does a balance-modification row's amount set the balance or add a
  delta? It decides the declared-wallet opening (§4). Check before Stage 3.

### Stage 2 — spec PR

This file, plus the spike results. In order:

1. **Transfer direction first** (§4), as its own commit or PR since it is a hub fix that stands
   without ezbookkeeping: the spec states "a TRANSFER unit's `accountRef` is the paying leg's
   account"; `HubQueries.projectionUnits()` orients matched pairs by sign; a hub test pairs a
   positive-first T1/T2/PAIR transfer and asserts the orientation; the Firefly impact (flipped
   `unitHash`es) is stated.
2. **Refunds (S5)** — operator gate: income rows, or netted against the charge.
3. `V2-SPEC.md` gets a new §11.2 "ezbookkeeping view" with §3–§5's rules (including "verify against
   the units view, not `latestBalance`", the declared-wallet opening, the fresh `clientSessionId` and
   the post-import count check), and §11.1 is marked dormant.

`AGENTS.md`'s "Firefly egress impact" rule is reworded to "downstream view impact"; that
is an invariant-file edit, so it needs operator OK in the PR. Touch-points beyond §11.1/§11.2: §12
(`ezb.yaml` among the config files; view-only, **not** hashed into `configRevision`), §13 (the CLI
list gains `egress ezb`), §16 (the job list and schedule example gain `egress-ezb`), §9.3 Jobs
(Plan/Verify/Apply for ezb), and §7 (`projection_state` / `/api/projection` stay Firefly-only).

### Stage 3 — mapping

Package `trex.v2.egress.ezb`: `EzbMap` (`deploy/config/ezb.yaml`: ref → name, ezbookkeeping account
category, the zone), `EzbRows` (the pure §4 mapping, including the direction rule and the two-enum
distinction). Unit tests, failing first.

### Stage 4 — client and view

`EzbClient` (Bearer API token via `java.net.http.HttpClient`; login + password for clear; the
timezone headers on every write; retry like `FireflyClient.java:62`), `EzbView` (plan / apply /
verify), `FakeEzb` on the JDK `HttpServer`. End-to-end tests over `FakeHub` + `FakeEzb`.

### Stage 5 — wiring

`EgressCommand` subcommand `ezb` (`trex-v2-dist/.../EgressCommand.java:32`); runner job `egress-ezb` in
`JobCatalogue.java:24` (apply behind `allowApply`); a `RunnerConfig` url field; Jobs UI buttons
(`jobs.js`, currently hard-coded to `egress-firefly`); a `schedule.yaml` example; `ezb.yaml` added to
the compose `init` seed loop and `configs:` block (`deploy/v2/compose*.yml`), the hub config-drift
list (`ConfigDrift.java:23`) and, if exposed, the MCP resource list (`HubService.java:133`).
`egress-firefly` stays registered but its `schedule.yaml` entry is removed (unscheduled).

### Stage 6 — deploy and docs

ezbookkeeping stays external like Firefly (`TREX_EZB_URL`, `EZB_TOKEN`, `EZB_USER` / `EZB_PASSWORD`)
in `deploy/v2/compose*.yml`, `trex.env`, `dev.sh` and the systemd unit; it joins the "back up three
things" exclusion (§14) as a rebuildable view: a lost instance is re-applied, not restored. The
`docs/DEPLOYMENTS.md` "Project" step and `README.md` are updated.

## 7. Verification

- `mvn -q verify` green at each stage. The ezb tests cover the mapping (expense, income,
  transfer trusting the unit's orientation, clearing, the declared-wallet opening, the 255-character
  cut, zone and per-date offset), the preflight refusal, the apply order (ensure, then clear, then
  import openings-first), a fresh `clientSessionId` per apply, a short post-import count failing
  apply, and a verify diff against the units view exiting 1.
- Dev end-to-end: a scratchpad `TREX_DEV_RUN` (never the operator's `deploy/dev/run` or `run/`) and a
  real `mayswind/ezbookkeeping:2.0.1` container with `enable_import` and an API token. Run plan,
  apply, then verify, which must exit 0. Account balances in the UI must equal `backwardOpening + Σ
  units`, applying twice must give identical counts, and a saved exploration must survive a re-apply.

## 8. Out of scope

Budgets; deleting the Firefly code; tags; incremental sync.

## 9. Spike results (recorded, 2026-10-15)

A scratchpad script (Python, `/tmp/opencode/feed.py`; not in the repo) drove a live re-feed. No Java
was written.

**Instance.** `mayswind/ezbookkeeping:2.0.1`, host port 8095 (Firefly holds 8083, the hub 8090),
named volume, `EBK_SECURITY_ENABLE_API_TOKEN=true`, `EBK_SECURITY_SECRET_KEY` set by hand,
`EBK_DATA_ENABLE_IMPORT=true` (the default). A user was added with
`ezbookkeeping userdata user-add -n … -i … -c AUD` (nickname is required) and an API token with
`userdata user-session-new -t api -e 0`.

**Feed.** 5701 units (4815 EXTERNAL, 886 TRANSFER) + 21 openings → **5718 transactions**, imported in
12 batches (11 × 500 + 218) in **~2.5 s**; a second full apply gave **identical** balances and the
same 5718 count, with all 22 accounts and 235 categories surviving the clear.

**Open (record before Stage 2):** the counts do not close. 5701 + 21 = 5722, not 5718, so 4 rows are
unaccounted for (zero-amount openings skipped? rows refused?); and 22 accounts carry 21 openings, so
one account has none — name it and why (a clearing ref with a computed opening? `cash-ron`?).

**Reconciliation.** With direction resolved from leg signs, **0/22 accounts mismatch** against
`backwardOpening + Σ units`. Before that fix, **7/22** mismatched, chiefly the transfer-heavy offset,
orange and CBA accounts (off by up to $308 k) — the single most important thing the spike found.

**Recorded facts.** API namespace and login split; `loginName`/`password` payload; required
`icon`(≥1, string)/`color` on accounts and categories; hundredths everywhere (balance as a string);
timezone headers on every write; the opening as a type-1 row that a clear deletes and `modify`
cannot change; child-category requirement; `destinationAccountId` optional; async import; the
`units`-vs-`latestBalance` `asOf` gap; the declared-wallet opening gap.
