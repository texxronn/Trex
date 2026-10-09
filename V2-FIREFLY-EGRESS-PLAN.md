# V2-FIREFLY-EGRESS-PLAN.md

# Firefly egress convergence — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> **Personal project, single operator, private.** `V2-SPEC.md` is the authority. This file is the
> build order for closing the gaps between v2 and the Firefly egress found in the review of
> 2026-10-09. Where this file and the spec disagree, the spec wins — until each stage's amendment
> lands (every stage amends `V2-SPEC.md` §11 in the same PR, `AGENTS.md`).
>
> **Status:** proposed — waiting on Stage 0 (live measurements) and the operator decisions in §2.
> **Review (2026-10-09):** `docs/reviews/V2-FIREFLY-EGRESS-REVIEW.md` — R1–R8 resolved; the second
> pass on those resolutions (V1–V7) is folded into Tasks 4.1, 4.4, 5.3, D8 and Stage 6.
> **Authority:** `V2-SPEC.md` §4 (identity), §6.3 (transfers, clearing), §6.7, §11 (egress);
> `V2-PROPOSAL.md` §11 (the rationale and the v1 lessons — read for *why*); `AGENTS.md`.

**Goal:** make `trex egress firefly` converge Firefly to the derivation again — every unit lands,
every later change to a unit reaches Firefly, nothing of yours in Firefly is overwritten, and the
lessons learned building it are written into the spec rather than left in commit messages.

**Architecture:** the egress stays a batch CLI that reads `/api/units` from the hub and writes
Firefly through its REST API. One hub fix (clearing transfers), one new hub field set (transfer legs
and the supersession map), and an egress that compares a **content fingerprint** it can recompute
from both sides (what it would post, what Firefly holds) instead of a hub hash it cannot recover.
Every write stays read-modify-write; nothing is deleted without `--remove-orphans`.

**Tech Stack:** Java 25 (`~/Tools/JDK/jdk-25.0.4.1+1/`), Maven, JUnit 5, Jackson (already a
dependency of `trex-v2-log`), JDK `HttpServer` fakes. No new dependencies.

**Spec:** `V2-SPEC.md` §11 (as amended by Stage 1) and `V2-PROPOSAL.md` §11 (rationale).

## Why the gaps exist

The egress was built on 2026-09-29 (P3, `cd8d96a`) with the v1 lessons. Later features moved
underneath it without revisiting it:

- clearing accounts (2026-10-07, `7eadb6b`) — their transfers never reach the egress (finding F1);
- the transfer-id rule moved from chain roots to **current** ids (`V2-SPEC.md` §4) — a supersede now
  changes a unit's id, so Firefly sees an orphan plus a create instead of an update (F11);
- the content-update path was planned (proposal §11.5) but never written (F2).

Stage 1 adds a process rule so this cannot recur silently: a change to derive or identity states its
egress impact in the same spec PR.

## The findings this plan closes

| Id | Severity | Finding | Stage |
|---|---|---|---|
| F1 | High | Clearing transfers are dropped by the hub (`HubQueries.projectionUnits` looks the clearing side up as a fact; `CURRENT_FACTS` excludes it); `firefly.yaml` maps none of the seven clearing accounts. Silent. | 2 |
| F2 | High | A content change (amount, date, description) is planned as an update, but `retag()` only rewrites category and tags. Firefly keeps the old amount and the run reports success. | 4 |
| F3 | Medium | `--verify` rebuilds state with a blank `stateHash`; a blank hash skips the content check, so a timer-driven verify hides F2 forever. | 4 |
| F4 | Medium | A re-tag replaces the whole tag list, deleting tags you added in Firefly. | 3 |
| F5 | Medium | Rebuild treats any group with an `external_id` as ours; the Data Importer or a hand entry can carry one, and `--remove-orphans` would delete it. | 3 |
| F6 | Medium | The startup checks the spec and `firefly.yaml` promise are missing: account **type** and **currency** are never compared with Firefly. | 3 |
| F7 | Low | A `Duplicate` create records the current category without looking at the group, hiding a stale category until `--verify`. | 4 |
| F8 | Low | Deleting an orphan that is already gone (404) throws. | 3 |
| F9 | Low | A replaced unit (orphan + its successor) is not reported as a pair. | 5 |
| F10 | Low | A content-only move is counted as both a retag and an update. | 4 |
| F11 | Spec | A supersede (re-parse) re-mints ids, so the old group is orphaned and the new unit created: a double count in Firefly until a human removes the orphan. | 5 |

## Global Constraints

- `derive()` is pure; nothing in this plan changes derive. The hub change in Stage 2 is a read.
- **Nothing is deleted automatically.** Only `--remove-orphans` deletes a Firefly group.
- **Read back only what we wrote.** The egress owns: `external_id`, the `trex` and
  `trex-category:*` tags, the first line of `notes`, and — on a single-split group — type, date,
  amount, currency, source, destination, description. Category moves only while it still equals
  our tag. Everything else (other tags, budget, bill, piggy bank, splits, the rest of the notes) is
  yours and is returned untouched.
- Every `PUT` is read-modify-write of the whole group, returning every split and `group_title`.
- When a field moves by **name**, its **id** is removed from the body (Firefly resolves the id first:
  measured for `category_id`; Stage 0 measures accounts).
- Retry 5xx, 429 and dropped connections only; a 4xx stops the pass naming the unit (unchanged).
- `apply_rules: false` on every create and update (unchanged; budgets stay Firefly's, decided
  2026-10-09).
- The token comes from `FIREFLY_TOKEN` only (unchanged).
- JDK-only plus the existing Jackson; no new dependency.
- Build: `export JAVA_HOME=~/Tools/JDK/jdk-25.0.4.1+1 && mvn -q -pl <module> -am test`. The full
  gate is `mvn test` from the root.
- Each stage is one PR on its own branch, merged on GitHub; never push to `master` (`AGENTS.md`).
- Never point a test or the spike at `deploy/dev/run` or `<repo>/run` data you have not been told
  to use; the spike uses a throwaway Firefly account and deletes what it made.

## Review Focus

1. **A hand-split group whose unit's amount later changes.** Expected: the split amounts are left
   as you made them, the plan says so, and `--verify` does not report the group forever.
   → test `aHandSplitGroupIsNeverRewrittenAndVerifiesClean` (Task 4.3).
2. **The first run after upgrading, with old hub hashes in `projection_state`.** Expected: every row
   is checked once (a GET), nothing is written when Firefly already matches, and the second run is
   quiet. → test `oldStateHashesAreCheckedOnceWithoutWrites` (Task 4.3).
3. **A transaction you created in Firefly that happens to carry an `external_id`.** Expected: never
   an orphan, never deleted. → test `aForeignGroupWithAnExternalIdIsNeverOurs` (Task 3.2).
4. **A re-parse that supersedes a leg of a projected transfer.** Expected: the existing group is
   re-keyed in place; no second transfer appears. → test `aSupersededTransferLegRekeysTheGroup`
   (Task 5.3).
5. **A clearing account missing from `firefly.yaml` after Stage 2.** Expected: the pass refuses
   before the first write and names the ref. → test `anUnmappedClearingAccountStopsBeforeAnyWrite`
   (Task 2.2).

---

## 1. Summary

| Stage | What | Findings | Kind | Gate |
|---|---|---|---|---|
| 0 | Live measurements against the dev Firefly | — | measure | — |
| 1 | `V2-SPEC.md` §11 rewritten as rules; the process rule | (all, docs) | spec | **D6** |
| 2 | Clearing transfers reach Firefly | F1 | hub + config | **D1**, **D7** |
| 3 | Startup checks, ownership, tags, 404 | F4, F5, F6, F8 | egress | **D4** |
| 4 | Content fingerprint and convergence; `--validate` (R2) | F2, F3, F7, F10, R2 | egress | **D2**, **D9**, Stage 0 |
| 5 | Re-key superseded units; report replacements | F9, F11 | hub + egress | **D3**, **D5**, Stage 0 |
| 6 | Operations notes; archive the plan | — | docs | — |

Stages 2 and 3 are independent of each other; 4 needs 3 (ownership helper), 5 needs 4 (`converge`).

**D8 (review R1)** is a deployment doctrine, not a stage: the kept Firefly is refed from empty with
the final build; no partial-stage apply touches it (see §2 and Stage 4 Step 6).

---

## 2. Operator decisions

| Id | Question | Recommendation | Why it is a decision |
|---|---|---|---|
| **D1** | How do clearing transfers land in Firefly? | **Map each clearing account in `firefly.yaml` as a Firefly account** (types below), created with its computed opening. Firefly balances then land on each declared closing, as trex's do. | Alternative: post them as withdrawals/deposits to a counterparty named after the clearing account (no new accounts, but Firefly's net worth would be wrong for the open ones). Changes what Firefly holds. |
| **D2** | Who wins on amount / date / description when trex and Firefly differ? | **Trex, on a single-split group** (the bank said so; a correction belongs in trex). **A hand-split group is never rewritten** — reported by `--validate` instead. | Proposal §11.5 covered trex-side content moves only; a hand edit of an amount was unstated. |
| **D3** | What happens to a projected unit whose id is superseded? | **Re-key the existing group in place** (new `external_id`, new content). No delete, no double count. | Proposal §11.5 said "identity survives"; `V2-SPEC.md` §4 made that false. |
| **D4** | Which Firefly groups are "ours"? | **Those whose first split carries the `trex` tag.** An `external_id` alone is not ownership. | Changes rebuild and orphan semantics. |
| **D5** | A unit that changed kind (an EXTERNAL row became a transfer leg, or a transfer was unpaired)? | **Report the pair (orphan → successors); never re-key across kinds.** `--remove-orphans` stays the instruction. | Re-keying across kinds would change the Firefly type of a group; Stage 0 A3 decides whether that is even possible, and the conservative answer holds either way. |
| **D6** | Add the process rule to `AGENTS.md`? | **Yes**: "A change to derive, identity or units states its effect on the egress in the same spec PR." | It edits `AGENTS.md`. |
| **D7** | Map `cash-ron` too? | **Yes, as an asset** ("Cash (Ron)"). Its facts are units today; the preflight would stop the first time one exists. | Adds a Firefly account. |
| **D8** | How is Firefly upgraded across these stages? **(review R1)** | **No migration: build all stages, then refeed the kept Firefly from empty with the final build.** No apply on a kept instance before that; Stage 4's live check is throwaway only. The legs-backfill marker is not built. **Refeeding from empty is also the standing repair, so Firefly-side work is disposable by doctrine** (review V7): hand-assigned budgets, piggy-bank links, your tags and hand-splits are lost on every refeed. Keep budget assignment as a Firefly **rule group** you re-run after a refeed, never by hand. | A deployment doctrine, not code; it makes Firefly-side work disposable. |
| **D9** | How are edits made in Firefly handled? **(review R2)** | **Operationally, not by auto-repair:** publish the Do/Don't contract; a read-only `--validate` detects and classifies (exit 1); a planned read that finds its group missing stops with a named `Refused`; recreation is the explicit `--verify` → `--apply` path, or retire the unit in trex. | It changes proposal §11.5's automatic recreate. |

Proposed `firefly.yaml` additions for D1/D7 (names are labels; **types need the operator's eye**):

```yaml
  # Clearing accounts (V2-SPEC.md §6.7): closed counterparties. Created with their computed opening.
  westpac-card:            { name: "Westpac Card (closed)",       type: liability }
  nab-fixed:               { name: "NAB Fixed Loan (closed)",     type: liability }
  nab-variable:            { name: "NAB Variable Loan (closed)",  type: liability }
  nab-offset:              { name: "NAB Offset (closed)",         type: asset }
  carryover:               { name: "Carryover",                   type: asset }
  bw-card-legacy:          { name: "BankWest Card (pre-2024)",    type: liability }
  cba-legacy:              { name: "CBA SmartAccess (pre-2024-10)", type: asset }
  # Cash (declared): balances arrive by attestation; an attestation itself is never projected.
  cash-ron:                { name: "Cash (Ron)",                  type: asset }
```

---

## 3. Stage 0 — live measurements (gate for Stages 4 and 5)

The fakes in the test suite must reproduce Firefly, not our guess of it. Every behaviour Stages 4–5
depend on is measured once against the dev Firefly (the instance the dev stack already uses) and
written into §3.2. **The operator runs this, or explicitly hands an agent the token.**

### Task 0.1: Run the spike and record the answers

**Files:**
- Create (scratch, not committed): `$SCRATCH/firefly-spike.sh`
- Modify: `V2-FIREFLY-EGRESS-PLAN.md` §3.2 (the answers)

- [ ] **Step 1: Write the script**

```bash
#!/usr/bin/env bash
# Measures the Firefly behaviours the egress depends on. Creates one throwaway account and a few
# transactions, then deletes the transactions. Needs FIREFLY_URL and FIREFLY_TOKEN; jq.
set -euo pipefail
: "${FIREFLY_URL:?}" "${FIREFLY_TOKEN:?}"
api() { curl -s -w '\n%{http_code}\n' -X "$1" "$FIREFLY_URL/api/v1/$2" \
  -H "Authorization: Bearer $FIREFLY_TOKEN" -H 'Accept: application/json' \
  -H 'Content-Type: application/json' ${3:+-d "$3"}; }
body() { sed '$d'; }      # strip the trailing status line

echo "== version";  api GET about | body | jq -r .data.version
ACC=$(api POST accounts '{"name":"trex-spike","type":"asset","account_role":"defaultAsset","currency_code":"AUD"}' | body | jq -r .data.id)
LIA=$(api POST accounts '{"name":"trex-spike-card","type":"liability","liability_type":"debt","liability_direction":"credit","currency_code":"AUD"}' | body | jq -r .data.id)
echo "asset=$ACC liability=$LIA"

echo "== A7 account type and currency as listed"
api GET "accounts?type=liability&limit=100" | body | jq -r '.data[]|select(.attributes.name=="trex-spike-card")|.attributes|[.type,.currency_code]|@tsv'

G=$(api POST transactions "{\"error_if_duplicate_hash\":true,\"apply_rules\":false,\"transactions\":[{\"type\":\"withdrawal\",\"date\":\"2026-01-05\",\"amount\":\"10.00\",\"currency_code\":\"AUD\",\"description\":\"Spike Shop  Sydney\",\"source_id\":\"$ACC\",\"destination_name\":\"SPIKE SHOP\",\"external_id\":\"spike-1\",\"tags\":[\"trex\",\"trex-category:A\",\"mine\"],\"category_name\":\"A\",\"notes\":\"trex n=1 rules=x\\nSPIKE SHOP SYDNEY\"}]}" | body | jq -r .data.id)
echo "group=$G"

echo "== A5 formats as read back (date, amount, description, ids and names)"
api GET "transactions/$G" | body | jq '.data.attributes.transactions[0]|{date,amount,description,source_id,source_name,destination_id,destination_name,category_id,category_name,currency_id,currency_code,external_id,tags}'

SPLIT=$(api GET "transactions/$G" | body | jq -c '.data.attributes.transactions[0]')
put() { api PUT "transactions/$G" "{\"apply_rules\":false,\"transactions\":[$1]}" | tail -1; }

echo "== A1 change external_id";        put "$(jq -c '.external_id="spike-1b"' <<<"$SPLIT")"
echo "== A2 change amount/date/descr";  put "$(jq -c '.external_id="spike-1b"|.amount="12.34"|.date="2026-01-06"|.description="Spike Shop"' <<<"$SPLIT")"
echo "== A4 stale destination_id, new destination_name"
put "$(jq -c '.external_id="spike-1b"|.amount="12.34"|.date="2026-01-06"|.description="Spike Shop"|.destination_name="OTHER SHOP"' <<<"$SPLIT")"
api GET "transactions/$G" | body | jq '.data.attributes.transactions[0]|{external_id,amount,date,description,destination_name}'

echo "== A3 change type withdrawal -> deposit (swap sides)"
put "$(jq -c --arg acc "$ACC" '.type="deposit"|del(.source_id,.destination_id)|.source_name="SPIKE SHOP"|.destination_id=$acc' <<<"$SPLIT")"
api GET "transactions/$G" | body | jq -r '.data.attributes.transactions[0].type'

echo "== A8 asset -> liability withdrawal by id, read back"
T=$(api POST transactions "{\"apply_rules\":false,\"transactions\":[{\"type\":\"withdrawal\",\"date\":\"2026-01-07\",\"amount\":\"5.00\",\"currency_code\":\"AUD\",\"description\":\"card payment\",\"source_id\":\"$ACC\",\"destination_id\":\"$LIA\",\"external_id\":\"spike-2\",\"tags\":[\"trex\"]}]}" | body | jq -r .data.id)
api GET "transactions/$T" | body | jq '.data.attributes.transactions[0]|{type,destination_id,destination_name}'

echo "== cleanup"
api DELETE "transactions/$G" | tail -1; api DELETE "transactions/$T" | tail -1
echo "== A6 delete a group that is gone"; api DELETE "transactions/$G" | tail -1
echo "Delete accounts trex-spike and trex-spike-card by hand in the Firefly UI."
```

- [ ] **Step 2: Run it**

Run: `set -a; . ~/.config/trex/firefly.env; set +a; bash $SCRATCH/firefly-spike.sh | tee $SCRATCH/spike.out`
Expected: every section prints; HTTP statuses are the last line of each PUT/DELETE.

- [ ] **Step 3: Record the answers in §3.2**, one line each, quoting the observed values.

### 3.1 What each answer decides

| Id | Question | If yes | If no |
|---|---|---|---|
| A1 | Does a PUT change `external_id`? | Stage 5 re-keys in place | Stage 5 reports supersedes as replacements only (D3 falls back to D5 behaviour) |
| A2 | Does a PUT change amount, date, description on a single split? | Stage 4 as written | Stop: F2 needs a different design |
| A3 | Does a PUT change the type? | Note only (D5 stays conservative) | Confirms D5 |
| A4 | With a stale `destination_id`, does the new `destination_name` win? | `applyContent` may keep ids | `applyContent` removes `*_id` when it sets `*_name` (the plan's default) |
| A5 | Date and amount formats read back | `Content.observed` normalises exactly these; if the echo's first ten characters differ from the date sent, compare `OffsetDateTime.parse(echo).toLocalDate()` and post `<date>T12:00:00` so the local day cannot shift | — |
| A6 | Status of deleting a missing group | Task 3.4 treats it as gone | — |
| A7 | `attributes.type` value for a liability; `currency_code` present | Task 3.1 compares against it | — |
| A8 | Read-back of an asset → liability withdrawal | `Content.observed` uses the destination id | — |

### 3.2 As measured

*(filled in by Task 0.1)*

---

## 4. Stage 1 — the spec carries the lessons (gate D6)

### Task 1.1: Rewrite `V2-SPEC.md` §11 as rules

**Files:**
- Modify: `V2-SPEC.md` §11 (lines starting `## 11. Egress and export`)
- Modify: `AGENTS.md` ("Development style" list) — only if D6 is accepted

- [ ] **Step 1: Replace the Firefly bullet of §11 with a subsection `### 11.1 Firefly`** holding
  these rules (each one line or two, with its reason; the measured numbers stay in the code
  comments that depend on them):

```markdown
### 11.1 Firefly

A batch (`plan` / `apply` / `verify`), never a daemon; started from Jobs or the CLI.

**What is projected.** Resolved units from `/api/units`: posted EXTERNAL transactions and TRANSFER
rows (a clearing pair included, carried by its real leg). Never a leg (a transfer and its legs
double-count), never HELD/REVIEW, PENDING, an ATTESTATION (a $0 transaction forever) or a retired
fact. Transaction notes and commitments are not projected (Firefly holds money that moved).

**The type comes from the two Firefly account kinds**, not from trex: asset↔asset and
liability↔liability are `transfer`; asset→liability is `withdrawal`; liability→asset is `deposit`
(Firefly refuses a `transfer` across the line).

**Every posting carries** `external_id` = unit id (part of Firefly's duplicate hash, so a collision
can only name the same row); `internal_reference` = account ref; tags `trex` and
`trex-category:<CATEGORY>`; `category_name` (always, `UNCATEGORIZED` included); notes whose first
line is `trex n=<n> rules=<configRevision>` (+ ` legs=<a>,<b>` on a transfer) and whose second is the
raw description; `error_if_duplicate_hash: true` (the 422 names the existing group, which is how a
lost state recovers); `apply_rules: false` (trex is the single classifier; budgets are Firefly's).
Counterparties are merchant stems. Amounts are two-decimal strings from cents.

**Ownership.** A group is ours iff its first split carries the `trex` tag. The egress owns `external_id`, its two
tags, the first notes line, and on a single-split group the type, date, amount, currency, source,
destination and description. Category moves only while it equals our tag (otherwise you changed it,
and you win). Everything else — other tags, budget, bill, piggy bank, splits, the rest of the notes —
is returned untouched. A hand-split group's content is never rewritten.

**Every write is read-modify-write.** A PUT replaces the group: return every split and
`group_title`. When a field moves by name, drop its id (Firefly resolves the id first — the failure
that looks exactly like success).

**Convergence.** `projection_state` (an accelerator, rebuildable from Firefly) records per unit the
group id, the category last written and a content fingerprint `fp1:<sha256>` over
type|date|amount|currency|source|destination|description (the amount exact, never rounded), computable both from the posting and from
Firefly's read-back. A unit converges when its category or fingerprint differs; converge reads the
group, writes only what moved, and records the result. `verify` rebuilds the state from Firefly
(fingerprints included) and plans; non-empty is exit 1.

**Identity moves.** A superseded unit's group is re-keyed in place (new `external_id`, new content),
found through the hub's supersession map (EXTERNAL) or the legs in the notes (TRANSFER). A unit that
changed kind is an orphan reported beside its successors. Nothing is deleted without
`--remove-orphans`; a missing group on delete is already gone.

**Firefly-side edits.** Firefly is edited only within the operational contract
(`docs/DEPLOYMENTS.md`): your tags, budgets, bills, notes below our first line and hand-splits are
yours; deleting a tagged transaction, removing the `trex` tag, editing `external_id`, the first
notes line (`n=`/`rules=`/`legs=`) or a single-split group's content is outside it. A read-only
`--validate` reports and classifies every such edit (`MISSING`, `UNTAGGED`, `TAMPERED`, `DRIFT`;
exit 1) beside the normal states (`BEHIND`, `ORPHAN`, `HAND_SPLIT`; informational); it never writes.
A violation is something Firefly changed, never something trex moved on from: the first notes line
is checked for shape, not values, and content against the fingerprint trex last wrote. When a planned read finds its group missing,
the pass stops with a named refusal — never a stack trace and never an automatic recreate:
recreation is the explicit recovery (`--verify` rebuilds the state from Firefly, so the unit becomes
a create; `--apply` recreates it), or retire the unit in trex and clean up with `--remove-orphans`.

**Accounts.** `firefly.yaml` maps each ref to a Firefly name and type, keyed by name (a rebuilt
instance changes every id). Startup stops on an unknown name, a type that disagrees, or a currency
that disagrees with `accounts.yaml`. The whole unit list is checked for unmapped refs before the
first write. Creation is opt-in (`--create-missing-accounts`); the opening is the backward figure
(a clearing account's computed opening), a liability seeded negative.

**Failure.** Retry 5xx, 429 and dropped connections with backoff and jitter, honouring
`Retry-After`; a 4xx stops the pass naming the unit and Firefly's message (a create also names the
account and date); exit non-zero. State is recorded as each write lands, so a rerun resumes.

**Cost.** A rule change re-plans every unit but writes only those whose category changed (a GET +
PUT each); unchanged rows keep a stale `rules=` in their notes by design. A first full apply on an empty Firefly is slow (measured ~0.6/s past a
thousand creates) and safe to interrupt.
```

- [ ] **Step 2: Mark the not-yet-built rules.** In the same PR, append to the subsection:
  `*Stages 2–5 of V2-FIREFLY-EGRESS-PLAN.md build the clearing, ownership, validation, fingerprint
  and re-key rules; until each lands, the code is behind this text.*` Each later stage removes its
  part of this sentence.

- [ ] **Step 3 (D6): Add to `AGENTS.md` "Development style"**:
  `- A change to derive, identity or units states its effect on the Firefly egress in the same spec PR.`

- [ ] **Step 4: Commit and open the PR**

```bash
git checkout -b docs/firefly-egress-spec
git add V2-SPEC.md AGENTS.md V2-FIREFLY-EGRESS-PLAN.md
git commit -m "docs: the Firefly egress rules in the spec, and the egress-impact rule"
```

---

## 5. Stage 2 — clearing transfers reach Firefly (F1; gates D1, D7)

### Task 2.1: The hub publishes clearing transfers

**Files:**
- Modify: `trex-v2-hub/src/main/java/trex/v2/hub/HubSql.java:264` (`TRANSFER_LEGS`)
- Modify: `trex-v2-hub/src/main/java/trex/v2/hub/HubQueries.java:1056-1112` (`projectionUnits`)
- Test: `trex-v2-hub/src/test/java/trex/v2/hub/HubUnitsTest.java`

**Interfaces:**
- Produces: a TRANSFER `ProjectionUnit` for every clearing pair, with `accountRef` = the paying
  side and `toAccountRef` = the receiving side, where one side is the clearing account ref.

- [ ] **Step 1: Write the failing test** (add to `HubUnitsTest`)

```java
    @Test
    void aClearingTransferIsOneUnitBetweenTheRealAccountAndTheClearingAccount(@TempDir Path dir)
            throws Exception {
        Path configDir = dir.resolve("config");
        Files.createDirectories(configDir);
        config(configDir);
        Files.writeString(configDir.resolve("accounts.yaml"), """
            accounts:
              - ref: "ing-savings"
                currency: "AUD"
                balanceSource: statement
              - ref: "ing-orange"
                currency: "AUD"
                balanceSource: statement
              - ref: "cash-ron"
                currency: "AUD"
                balanceSource: declared
              - ref: "nab-fixed"
                currency: "AUD"
                balanceSource: clearing
                closingBalance: 0
            """);
        Files.writeString(configDir.resolve("transfers.yaml"), """
            windowDays: 4
            transferPatterns:
              ing-orange:
                - { match: 'NAB Fixed Payments', rail: BANK_TRANSFER, clearing: nab-fixed }
              default: []
            """);
        Path journal = dir.resolve("trex.jsonl");
        try (JsonlJournal j = new JsonlJournal(journal)) {
            j.appendBatch(List.of(fact(1, "pay", "ing-orange", -3180000, "NAB Fixed Payments",
                Observation.POSTED)));
        }
        try (HubService hub = HubService.start(new HubConfig(journal, dir.resolve("trex.sqlite"),
                configDir, "127.0.0.1", 0, 50))) {
            await(() -> hub.units().units().size() == 1);
            var unit = hub.units().units().getFirst();
            assertEquals("TRANSFER", unit.unitKind());
            assertEquals("ing-orange", unit.accountRef(), "the paying side");
            assertEquals("nab-fixed", unit.toAccountRef(), "the clearing side");
            assertEquals(3180000, unit.amount());
            assertEquals(LocalDate.of(2026, 9, 1), unit.date());
        }
    }
```

- [ ] **Step 2: Run it to see it fail**

Run: `mvn -q -pl trex-v2-hub -am test -Dtest=HubUnitsTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — `await` times out (the unit is dropped, size stays 0).

- [ ] **Step 3: Implement.** In `HubSql`:

```java
    static final String TRANSFER_LEGS = "SELECT transfer_id, from_leg, to_leg, clearing_account FROM transfer";
```

In `HubQueries.projectionUnits`, read the fourth column and handle the clearing pair before the
two-fact case. A unit row whose legs cannot be found is a broken index, not a row to skip — it was
exactly this silence that hid F1:

```java
            Map<String, String[]> legs = new java.util.HashMap<>();
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(HubSql.TRANSFER_LEGS)) {
                while (rs.next()) {
                    legs.put(rs.getString(1), new String[] { rs.getString(2), rs.getString(3), rs.getString(4) });
                }
            }
```

```java
                    if ("TRANSFER".equals(kind)) {
                        String[] pair = legs.get(unitId);
                        if (pair == null) {
                            throw new IllegalStateException("unit " + unitId + " has no transfer row");
                        }
                        String clearing = pair[2];
                        if (clearing != null) {
                            // One real leg and an account side (§6.7): the clearing ref stands in the
                            // transfer row where a leg id would be, so it is never a fact.
                            boolean realPays = !clearing.equals(pair[0]);
                            trex.v2.core.Fact real = facts.get(realPays ? pair[0] : pair[1]);
                            if (real == null) {
                                throw new IllegalStateException("clearing unit " + unitId + " has no current real leg");
                            }
                            String fromRef = realPays ? real.accountRef() : clearing;
                            String toRef = realPays ? clearing : real.accountRef();
                            long amount = Math.abs(real.amount());
                            out.add(new trex.v2.hub.api.ProjectionUnit(unitId, "TRANSFER", real.n(), fromRef,
                                toRef, real.date(), amount, currency, "TRANSFER", "STRUCTURAL", "MATCHED",
                                false, false, real.rawDescription(),
                                unitHash(unitId, "TRANSFER", fromRef, toRef, real.date(), amount, currency,
                                    "TRANSFER")));
                            continue;
                        }
                        trex.v2.core.Fact from = facts.get(pair[0]);
                        trex.v2.core.Fact to = facts.get(pair[1]);
                        if (from == null || to == null) {
                            throw new IllegalStateException("transfer unit " + unitId + " has a leg that is not current");
                        }
                        // … existing ProjectionUnit construction unchanged …
```

Also replace the EXTERNAL branch's `if (fact == null) { continue; }` with
`throw new IllegalStateException("unit " + unitId + " has no current fact");`.

- [ ] **Step 4: Run the hub tests**

Run: `mvn -q -pl trex-v2-hub -am test`
Expected: PASS (the existing `theUnitSetIsTransfersPlusPostedExternalTransactionsOnly` still passes —
if it now throws, an existing fixture had a dangling unit: stop and report it rather than reverting
to `continue`).

- [ ] **Step 5: Commit**

```bash
git checkout -b fix/firefly-clearing-units
git add trex-v2-hub
git commit -m "fix(hub): clearing transfers are projectable units; a dangling unit is an error"
```

### Task 2.2: Map the clearing accounts; the preflight guards them

**Files:**
- Modify: `deploy/config/firefly.yaml` (the block in §2, as the operator approved it)
- Test: `trex-v2-egress/src/test/java/trex/v2/egress/firefly/FireflyEgressTest.java`
- Modify: `V2-SPEC.md` §11.1 (drop "clearing" from the not-yet-built sentence)

- [ ] **Step 1: Write the test** (pins Review Focus 5; it passes on today's code, and must keep
  passing — the clearing ref now reaches the egress, so the preflight is what stands between an
  unmapped clearing account and a half-written pass)

```java
    @Test
    void anUnmappedClearingAccountStopsBeforeAnyWrite() throws Exception {
        try (FakeFirefly fake = new FakeFirefly(); FakeHub hub = new FakeHub()) {
            hub.units = List.of(
                FakeHub.unit("ext1", "EXTERNAL", 1, "ing-savings", null, "2026-09-01", -1000, "GROCERIES",
                    "COLES 1234", "h1"),
                FakeHub.unit("TRF-c", "TRANSFER", 2, "ing-orange", "nab-fixed", "2026-09-01", 3180000,
                    "TRANSFER", "NAB Fixed Payments", "h2"));
            FireflyEgress.Refused refused = org.junit.jupiter.api.Assertions.assertThrows(
                FireflyEgress.Refused.class,
                () -> egress(hub, fake, accounts(), FireflyEgress.Mode.APPLY, false).run());
            assertTrue(refused.getMessage().contains("nab-fixed"), refused.getMessage());
            assertEquals(0, fake.posts.get(), "nothing written");
        }
    }
```

- [ ] **Step 2: Run it**

Run: `mvn -q -pl trex-v2-egress -am test -Dtest=FireflyEgressTest`
Expected: PASS.

- [ ] **Step 3: Add the approved block to `deploy/config/firefly.yaml`.** Then, on the dev stack,
  `trex egress firefly --print-accounts …` lists the new refs as `(missing)`, and
  `--create-missing-accounts --plan` creates them with their computed openings (the hub's
  `/api/opening` already computes a clearing account's opening and first date). The operator runs
  this, not an agent.

- [ ] **Step 4: Update §11.1 and commit; open the PR (Tasks 2.1 + 2.2)**

```bash
git add deploy/config/firefly.yaml trex-v2-egress V2-SPEC.md
git commit -m "config: map the clearing accounts and cash in firefly.yaml"
```

---

## 6. Stage 3 — startup checks, ownership, tags, 404 (F4, F5, F6, F8; gate D4)

### Task 3.1: Type and currency are checked at startup

**Files:**
- Create: `trex-v2-egress/src/main/java/trex/v2/egress/firefly/AccountChecks.java`
- Modify: `trex-v2-dist/src/main/java/trex/v2/cli/EgressCommand.java:134-152`
- Test: `trex-v2-egress/src/test/java/trex/v2/egress/firefly/AccountChecksTest.java`

**Interfaces:**
- Produces: `static List<String> AccountChecks.problems(AccountMap resolved, Map<String, FireflyClient.AccountInfo> instanceByName, Map<String, String> currencyByRef)` — one human sentence per disagreement, sorted; empty means fine.

- [ ] **Step 1: Write the failing test**

```java
package trex.v2.egress.firefly;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountChecksTest {

    private static AccountMap map() throws Exception {
        Path file = Files.createTempFile("firefly", ".yaml");
        Files.writeString(file, """
            accounts:
              ing-savings:    { name: "ING Savings",     type: asset }
              ing-credit-card: { name: "ING Credit Card", type: liability }
            """);
        return AccountMap.load(file).resolved(Map.of("ING Savings", "1", "ING Credit Card", "2"));
    }

    @Test
    void agreementIsSilent() throws Exception {
        var instance = Map.of(
            "ING Savings", new FireflyClient.AccountInfo("1", "ING Savings", "asset", "AUD", "0"),
            "ING Credit Card", new FireflyClient.AccountInfo("2", "ING Credit Card", "liability", "AUD", "0"));
        assertTrue(AccountChecks.problems(map(), instance,
            Map.of("ing-savings", "AUD", "ing-credit-card", "AUD")).isEmpty());
    }

    @Test
    void aWrongTypeAndAWrongCurrencyAreBothNamed() throws Exception {
        var instance = Map.of(
            "ING Savings", new FireflyClient.AccountInfo("1", "ING Savings", "asset", "USD", "0"),
            "ING Credit Card", new FireflyClient.AccountInfo("2", "ING Credit Card", "asset", "AUD", "0"));
        List<String> problems = AccountChecks.problems(map(), instance,
            Map.of("ing-savings", "AUD", "ing-credit-card", "AUD"));
        assertEquals(2, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("ing-credit-card") && problems.get(0).contains("liability"));
        assertTrue(problems.get(1).contains("ing-savings") && problems.get(1).contains("USD"));
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `mvn -q -pl trex-v2-egress -am test -Dtest=AccountChecksTest`
Expected: FAIL — `AccountChecks` does not exist.

- [ ] **Step 3: Implement**

```java
package trex.v2.egress.firefly;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What startup compares against the instance before anything is written (V2-SPEC.md §11.1). The
 * type is load-bearing — it decides the transaction type, and Firefly refuses a transfer across the
 * asset/liability line. The currency is checked because Firefly converts silently on a mismatch and
 * the ledger simply stops reconciling, with nothing to show for it.
 */
public final class AccountChecks {

    private AccountChecks() {}

    public static List<String> problems(AccountMap resolved, Map<String, FireflyClient.AccountInfo> instanceByName,
                                        Map<String, String> currencyByRef) {
        List<String> out = new ArrayList<>();
        resolved.byRef().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            String ref = e.getKey();
            AccountMap.Entry want = e.getValue();
            FireflyClient.AccountInfo have = instanceByName.get(want.name());
            if (have == null) {
                return;                       // unresolved names are reported separately
            }
            boolean wantLiability = want.kind() == AccountMap.Kind.LIABILITY;
            if (wantLiability != have.isLiability()) {
                out.add(ref + ": firefly.yaml says " + (wantLiability ? "liability" : "asset")
                    + " but Firefly's \"" + want.name() + "\" is " + have.type());
            }
            String currency = currencyByRef.get(ref);
            if (currency != null && have.currency() != null && !currency.equals(have.currency())) {
                out.add(ref + ": accounts.yaml says " + currency + " but Firefly's \"" + want.name()
                    + "\" is " + have.currency());
            }
        });
        return out;
    }
}
```

In `EgressCommand.call()`, right after the unresolved-name stop (`return 1;`):

```java
            List<String> problems = AccountChecks.problems(resolved, firefly.accounts(), hub.currencyByAccount());
            if (!problems.isEmpty()) {
                System.err.println("firefly.yaml disagrees with the instance; nothing has been written:");
                problems.forEach(p -> System.err.println("  " + p));
                return 1;
            }
```

(`firefly.accounts()` is called again after `--create-missing-accounts`, which may have added
accounts.) If Stage 0 A7 showed a liability's `type` as something other than a `liabilit…` prefix,
change `AccountInfo.isLiability()` to match and add that value to the test.

- [ ] **Step 4: Run the egress and dist tests**

Run: `mvn -q -pl trex-v2-egress,trex-v2-dist -am test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git checkout -b fix/firefly-egress-guards
git add trex-v2-egress trex-v2-dist
git commit -m "fix(egress): check account type and currency against Firefly at startup"
```

### Task 3.2: Ours means the `trex` tag

**Files:**
- Modify: `trex-v2-egress/src/main/java/trex/v2/egress/firefly/FireflyClient.java` (`allTransactions`, new `isOurs`)
- Test: `trex-v2-egress/src/test/java/trex/v2/egress/firefly/FireflyEgressTest.java`

**Interfaces:**
- Produces: `public static boolean FireflyClient.isOurs(JsonNode split)` — true iff the split's tags contain `Projection.TAG`.

- [ ] **Step 1: Write the failing test** (Review Focus 3)

```java
    @Test
    void aForeignGroupWithAnExternalIdIsNeverOurs() throws Exception {
        try (FakeFirefly fake = new FakeFirefly(); FakeHub hub = new FakeHub()) {
            fake.seedGroup("importer-77", null, List.of(new java.util.HashMap<>(Map.of(
                "external_id", "importer-77", "description", "Imported by hand",
                "tags", List.of("imported"), "category_name", "FOOD"))));
            FireflyEgress.Outcome verified = egress(hub, fake, accounts(), FireflyEgress.Mode.VERIFY, true).run();
            assertEquals(0, verified.orphans(), "not ours, so not an orphan");
            assertEquals(1, fake.groups().size(), "and never deleted, even with --remove-orphans");
        }
    }
```

- [ ] **Step 2: Run it to see it fail**

Run: `mvn -q -pl trex-v2-egress -am test -Dtest=FireflyEgressTest#aForeignGroupWithAnExternalIdIsNeverOurs`
Expected: FAIL — `orphans` is 1. (`removeOrphans` is only acted on in APPLY; the orphan count is the
signal.)

- [ ] **Step 3: Implement.** In `FireflyClient`:

```java
    /** Ours is the trex tag, never an external_id alone: an importer or a hand entry can carry one. */
    public static boolean isOurs(JsonNode split) {
        for (JsonNode t : split.path("tags")) {
            if (Projection.TAG.equals(t.asText())) {
                return true;
            }
        }
        return false;
    }
```

and in `allTransactions` replace the `external == null` skip with:

```java
                String external = first.path("external_id").asText(null);
                if (external == null || !isOurs(first)) {
                    continue;                  // not ours
                }
```

- [ ] **Step 4: Run the egress tests**

Run: `mvn -q -pl trex-v2-egress -am test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add trex-v2-egress
git commit -m "fix(egress): a group is ours only when it carries the trex tag"
```

### Task 3.3: A re-tag keeps your tags

**Files:**
- Modify: `trex-v2-egress/src/main/java/trex/v2/egress/firefly/Projection.java` (new `tags`)
- Modify: `trex-v2-egress/src/main/java/trex/v2/egress/firefly/FireflyEgress.java:186` (use it)
- Test: `trex-v2-egress/src/test/java/trex/v2/egress/firefly/ProjectionTest.java`, `FireflyEgressTest.java`

**Interfaces:**
- Produces: `public static List<String> Projection.tags(JsonNode existing, String category)` — the existing tags minus `trex` and every `trex-category:*`, then `trex`, then `trex-category:<category>`.

- [ ] **Step 1: Write the failing tests.** In `ProjectionTest`:

```java
    @Test
    void tagsKeepYoursAndReplaceOurs() {
        com.fasterxml.jackson.databind.JsonNode existing = trex.v2.log.Json.mapper().valueToTree(
            List.of("holiday", "trex", "trex-category:OLD", "tax-2026"));
        assertEquals(List.of("holiday", "tax-2026", "trex", "trex-category:NEW"),
            Projection.tags(existing, "NEW"));
    }
```

In `FireflyEgressTest`:

```java
    @Test
    void aRetagKeepsTagsYouAddedInFirefly() throws Exception {
        try (FakeFirefly fake = new FakeFirefly(); FakeHub hub = new FakeHub()) {
            String gid = fake.seedGroup("ext1", null, List.of(new java.util.HashMap<>(Map.of(
                "external_id", "ext1", "type", "withdrawal", "date", "2026-09-01", "amount", "10.00",
                "currency_code", "AUD", "source_id", "1", "destination_name", "COLES",
                "description", "COLES 1234", "category_name", "GROCERIES",
                "tags", List.of("trex", "trex-category:GROCERIES", "holiday")))));
            hub.projection.put("ext1", Map.of("unitId", "ext1", "unitKind", "EXTERNAL", "groupId", gid,
                "category", "GROCERIES", "stateHash", "", "configRevision", "cfg", "deriveVersion", "d",
                "verifiedAt", "t"));
            hub.units = List.of(FakeHub.unit("ext1", "EXTERNAL", 1, "ing-savings", null, "2026-09-01",
                -1000, "FOOD", "COLES 1234", "h1"));
            egress(hub, fake, accounts(), FireflyEgress.Mode.APPLY, false).run();
            assertTrue(((List<?>) splitOf(fake, "ext1").get("tags")).contains("holiday"));
        }
    }
```

- [ ] **Step 2: Run them to see them fail**

Run: `mvn -q -pl trex-v2-egress -am test -Dtest='ProjectionTest,FireflyEgressTest'`
Expected: FAIL — `Projection.tags` missing; then `holiday` gone.

- [ ] **Step 3: Implement** in `Projection`:

```java
    /** Yours stay; ours are replaced. A re-tag that wrote only ours would delete what you added. */
    public static List<String> tags(com.fasterxml.jackson.databind.JsonNode existing, String category) {
        List<String> out = new java.util.ArrayList<>();
        for (com.fasterxml.jackson.databind.JsonNode t : existing) {
            String v = t.asText();
            if (!v.equals(TAG) && !v.startsWith(CATEGORY_TAG_PREFIX)) {
                out.add(v);
            }
        }
        out.add(TAG);
        out.add(CATEGORY_TAG_PREFIX + category);
        return out;
    }
```

and in `FireflyEgress.retag` replace the `map.put("tags", …)` line with
`map.put("tags", Projection.tags(split.path("tags"), unit.category()));`.

- [ ] **Step 4: Run the egress tests**

Run: `mvn -q -pl trex-v2-egress -am test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add trex-v2-egress
git commit -m "fix(egress): a re-tag keeps the tags you added in Firefly"
```

### Task 3.4: Deleting a group that is already gone

**Files:**
- Modify: `trex-v2-egress/src/main/java/trex/v2/egress/firefly/FireflyClient.java` (`deleteTransaction`)
- Modify: `trex-v2-egress/src/test/java/trex/v2/egress/firefly/FakeFirefly.java` (DELETE of a missing id → 404, as Stage 0 A6 measured; if A6 measured another status, use that status in both places)
- Test: `trex-v2-egress/src/test/java/trex/v2/egress/firefly/FireflyClientTest.java`

- [ ] **Step 1: Write the failing test**

```java
    @Test
    void deletingAGroupThatIsAlreadyGoneIsNotAnError() throws Exception {
        try (FakeFirefly fake = new FakeFirefly()) {
            new FireflyClient(fake.url(), "token").deleteTransaction("404404");
            assertEquals(1, fake.deletes.get());
        }
    }
```

In `FakeFirefly`'s DELETE case, respond 404 when `removed == null`:

```java
                    case "DELETE" -> {
                        deletes.incrementAndGet();
                        Group removed = byId.remove(id);
                        if (removed == null) {
                            respond(exchange, 404, "{\"message\":\"Resource not found\"}");
                            return;
                        }
                        removed.splits().forEach(s -> idByExternal.remove(s.get("external_id")));
                        respond(exchange, 204, "");
                    }
```

- [ ] **Step 2: Run it to see it fail**

Run: `mvn -q -pl trex-v2-egress -am test -Dtest=FireflyClientTest`
Expected: FAIL — `IOException: deleting group 404404: 404 …`.

- [ ] **Step 3: Implement**

```java
    /** Gone is the goal: a group already deleted (by you, or by a run that stopped) is not an error. */
    public void deleteTransaction(String groupId) throws IOException, InterruptedException {
        HttpResponse<String> r = send("DELETE", "/api/v1/transactions/" + groupId, null);
        if (r.statusCode() == 404) {
            return;
        }
        if (r.statusCode() / 100 != 2) {
            throw new IOException("deleting group " + groupId + ": " + r.statusCode() + " " + message(r.body()));
        }
    }
```

- [ ] **Step 4: Run the egress tests; update §11.1 (drop "ownership" from the not-yet-built sentence)**

Run: `mvn -q -pl trex-v2-egress -am test`
Expected: PASS.

- [ ] **Step 5: Commit; open the PR (Tasks 3.1–3.4)**

```bash
git add trex-v2-egress V2-SPEC.md
git commit -m "fix(egress): deleting an orphan that is already gone is not an error"
```

---

## 7. Stage 4 — content fingerprint and convergence (F2, F3, F7, F10; gates D2, Stage 0)

### Task 4.1: The content fingerprint

**Files:**
- Create: `trex-v2-egress/src/main/java/trex/v2/egress/firefly/Content.java`
- Modify: `trex-v2-egress/src/main/java/trex/v2/egress/firefly/Projection.java` (`Posting.split()`)
- Modify: `trex-v2-egress/src/main/java/trex/v2/egress/firefly/AccountMap.java` (`ids()`)
- Test: `trex-v2-egress/src/test/java/trex/v2/egress/firefly/ContentTest.java`

**Interfaces:**
- Produces:
  - `public record Content(String type, String date, String amount, String currency, String source, String destination, String description)` — `amount` is the **exact** decimal, normalised (review V6)
  - `public static final String Content.HAND_SPLIT = "fp1:hand-split"`
  - `public static Content Content.expected(Map<String, Object> split)` — from a posting split
  - `public static Content Content.observed(JsonNode split, Set<String> ownIds)` — from Firefly's read-back
  - `public String Content.fingerprint()` — `"fp1:" + sha256(type|date|amount|currency|source|destination|description)`
  - `static String Content.exact(String amount)` — absolute value, trailing zeros stripped, at least two decimals (`"10.000000000000"` → `"10.00"`, `"10.004"` stays `"10.004"`, blank → `"0.00"`)
  - `static String Content.day(String date)` — the local day of a posted or echoed date (review V3: **both** sides use it)
  - `static long Content.cents(String amount)` — rounded HALF_UP; only for summing a hand-split group's splits
  - `public static boolean Content.verified(String stateHash)` — true iff it starts with `fp1:`
  - `public Map<String, Object> Projection.Posting.split()` — the posting's single split
  - `public Set<String> AccountMap.ids()` — the resolved Firefly ids of every mapped account

An account side is written as `id:<id>` when it is one of our mapped accounts and `name:<name>`
otherwise (a merchant counterparty), so both sides compare the same way whatever Firefly echoes.

- [ ] **Step 1: Write the failing test.** Use the formats Stage 0 A5 recorded; the values below are
  the ones Firefly 6.x is expected to return — replace them with the measured ones if they differ.
  If A5 shows the echo's date differing from the date sent, `Content.observed` parses
  `OffsetDateTime` and compares `toLocalDate()`, and `Projection.of` posts `<date>T12:00:00` so the
  local day cannot shift (see §3.1 A5).

```java
package trex.v2.egress.firefly;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import trex.v2.log.Json;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentTest {

    private static final Map<String, Object> POSTED = Map.of(
        "type", "withdrawal", "date", "2026-09-01", "amount", "10.00", "currency_code", "AUD",
        "source_id", "1", "destination_name", "COLES", "description", "Coles 1234");

    @Test
    void whatWePostAndWhatFireflyEchoesHaveTheSameFingerprint() throws Exception {
        JsonNode echoed = Json.mapper().readTree("""
            {"type":"withdrawal","date":"2026-09-01T00:00:00+10:00","amount":"10.000000000000",
             "currency_code":"AUD","source_id":"1","source_name":"ING Savings",
             "destination_id":"412","destination_name":"COLES","description":"Coles 1234"}""");
        assertEquals(Content.expected(POSTED), Content.observed(echoed, Set.of("1", "2")));
        assertEquals(Content.expected(POSTED).fingerprint(), Content.observed(echoed, Set.of("1", "2")).fingerprint());
    }

    @Test
    void anOwnLiabilityOnTheFarSideComparesById() throws Exception {
        Map<String, Object> cardPayment = Map.of("type", "withdrawal", "date", "2026-09-01", "amount", "50.00",
            "currency_code", "AUD", "source_id", "1", "destination_id", "2", "description", "Card");
        JsonNode echoed = Json.mapper().readTree("""
            {"type":"withdrawal","date":"2026-09-01T00:00:00+10:00","amount":"50.00","currency_code":"AUD",
             "source_id":"1","source_name":"ING Savings","destination_id":"2","destination_name":"ING Card",
             "description":"Card"}""");
        assertEquals(Content.expected(cardPayment), Content.observed(echoed, Set.of("1", "2")));
    }

    @Test
    void anAmountMoveChangesTheFingerprint() {
        Map<String, Object> moved = new java.util.HashMap<>(POSTED);
        moved.put("amount", "12.00");
        assertNotEquals(Content.expected(POSTED).fingerprint(), Content.expected(moved).fingerprint());
    }

    @Test
    void onlyFp1HashesAreVerified() {
        assertTrue(Content.verified(Content.expected(POSTED).fingerprint()));
        assertTrue(Content.verified(Content.HAND_SPLIT));
        assertFalse(Content.verified("9f2c…an old hub unitHash"));
        assertFalse(Content.verified(""));
    }

    @Test
    void amountsCompareExactlySoASubCentEditIsDrift() {
        assertEquals("10.00", Content.exact("10.000000000000"));
        assertEquals("100.00", Content.exact("100"));
        assertEquals("10.004", Content.exact("10.004"), "below half a cent is still a difference (V6)");
        assertEquals("0.00", Content.exact(""));
        assertEquals("0.00", Content.exact(null));
        Map<String, Object> edited = new java.util.HashMap<>(POSTED);
        edited.put("amount", "10.004");
        assertNotEquals(Content.expected(POSTED).fingerprint(), Content.expected(edited).fingerprint());
    }

    @Test
    void handSplitSumsRoundInsteadOfThrowing() {
        assertEquals(1001, Content.cents("10.005"));
        assertEquals(0, Content.cents(""));
        assertEquals(0, Content.cents(null));
    }

    @Test
    void aNoonPostingAndItsEchoAreTheSameDay() throws Exception {
        // If Stage 0 A5 triggers the fallback, Projection.of posts <date>T12:00:00 (review R7/V3):
        // the expected side must reduce to the day exactly as the observed side does.
        Map<String, Object> noon = new java.util.HashMap<>(POSTED);
        noon.put("date", "2026-09-01T12:00:00");
        JsonNode echoed = Json.mapper().readTree("""
            {"type":"withdrawal","date":"2026-09-01T12:00:00+10:00","amount":"10.00","currency_code":"AUD",
             "source_id":"1","destination_id":"412","destination_name":"COLES","description":"Coles 1234"}""");
        assertEquals("2026-09-01", Content.expected(noon).date());
        assertEquals(Content.expected(noon), Content.observed(echoed, Set.of("1", "2")));
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `mvn -q -pl trex-v2-egress -am test -Dtest=ContentTest`
Expected: FAIL — `Content` does not exist.

- [ ] **Step 3: Implement**

```java
package trex.v2.egress.firefly;

import com.fasterxml.jackson.databind.JsonNode;
import trex.v2.core.Hashes;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Set;

/**
 * What a Firefly transaction says, reduced to the fields the egress owns on a single split
 * (V2-SPEC.md §11.1), so it can be computed from both sides: the posting we would send and the group
 * Firefly holds. That is the point — the hub's unit hash cannot be recovered from Firefly, so a
 * rebuilt state had a blank hash and content drift became invisible (F3).
 */
public record Content(String type, String date, String amount, String currency, String source,
                      String destination, String description) {

    static final String PREFIX = "fp1:";

    /** Recorded for a group you split by hand: its content is yours and is never compared. */
    public static final String HAND_SPLIT = PREFIX + "hand-split";

    public String fingerprint() {
        return PREFIX + Hashes.sha256(String.join("|", type, date, amount, currency,
            source, destination, description));
    }

    /** A state hash this version wrote. Anything else (blank, an old hub hash) is checked once. */
    public static boolean verified(String stateHash) {
        return stateHash != null && stateHash.startsWith(PREFIX);
    }

    public static Content expected(Map<String, Object> split) {
        return new Content(str(split.get("type")), day(str(split.get("date"))),
            exact(str(split.get("amount"))), str(split.get("currency_code")),
            side(split.get("source_id"), split.get("source_name")),
            side(split.get("destination_id"), split.get("destination_name")),
            str(split.get("description")));
    }

    public static Content observed(JsonNode split, Set<String> ownIds) {
        return new Content(split.path("type").asText(""), day(split.path("date").asText("")),
            exact(split.path("amount").asText("")), split.path("currency_code").asText(""),
            observedSide(split, "source", ownIds), observedSide(split, "destination", ownIds),
            split.path("description").asText(""));
    }

    private static String observedSide(JsonNode split, String side, Set<String> ownIds) {
        String id = split.path(side + "_id").asText(null);
        return id != null && ownIds.contains(id) ? "id:" + id : "name:" + split.path(side + "_name").asText("");
    }

    private static String side(Object id, Object name) {
        return id != null ? "id:" + id : "name:" + str(name);
    }

    /**
     * The local day of a date we posted or Firefly echoed. Both sides go through here (review V3): a
     * noon posting ({@code 2026-09-01T12:00:00}) and its echo ({@code 2026-09-01T12:00:00+10:00})
     * must agree. Firefly echoes a local date-time in its own zone, so the day is the first ten
     * characters. If Stage 0 A5 measured a UTC-normalised echo instead, use
     * {@code OffsetDateTime.parse(date).toLocalDate()} for an offset-bearing value — safe only because
     * the posting is at noon, which no offset of at most twelve hours moves to another day.
     */
    static String day(String date) {
        if (date == null) {
            return "";
        }
        return date.length() >= 10 ? date.substring(0, 10) : date;
    }

    /**
     * The exact amount, normalised, so the fingerprint sees every difference: Firefly's
     * {@code "10.000000000000"} equals our {@code "10.00"}, but a hand-edited {@code "10.004"} does
     * not — rounding to cents would hide a remainder below half a cent (review V6). Blank is zero,
     * never a throw (R4).
     */
    static String exact(String amount) {
        if (amount == null || amount.isBlank()) {
            return "0.00";
        }
        BigDecimal v = new BigDecimal(amount.strip()).abs().stripTrailingZeros();
        return (v.scale() < 2 ? v.setScale(2) : v).toPlainString();
    }

    /** Cents, rounded HALF_UP — only for summing a hand-split group, never for the fingerprint. */
    static long cents(String amount) {
        if (amount == null || amount.isBlank()) {
            return 0;
        }
        return new BigDecimal(amount.strip()).abs().setScale(2, RoundingMode.HALF_UP)
            .movePointRight(2).longValueExact();
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }
}
```

In `Projection`:

```java
    public record Posting(String unitId, Map<String, Object> body) {

        @SuppressWarnings("unchecked")
        public Map<String, Object> split() {
            return ((List<Map<String, Object>>) body.get("transactions")).getFirst();
        }
    }
```

In `AccountMap`:

```java
    /** The Firefly ids of our own accounts: on a read-back, these sides compare by id, others by name. */
    public java.util.Set<String> ids() {
        java.util.Set<String> out = new java.util.TreeSet<>();
        byRef.values().forEach(e -> {
            if (e.id() != null) {
                out.add(e.id());
            }
        });
        return out;
    }
```

(`trex.v2.core.Hashes.sha256` is the helper the hub uses for `unitHash`; the egress already
depends on `trex-v2-core`.)

- [ ] **Step 4: Run the egress tests**

Run: `mvn -q -pl trex-v2-egress -am test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git checkout -b fix/firefly-egress-convergence
git add trex-v2-egress
git commit -m "feat(egress): a content fingerprint computable from the posting and from Firefly"
```

### Task 4.2: The fake behaves like Firefly on content

**Files:**
- Modify: `trex-v2-egress/src/test/java/trex/v2/egress/firefly/FakeFirefly.java`

The fake must echo what Firefly echoes (Stage 0 A5) and reproduce the id-wins-over-name trap for
accounts if Stage 0 A4 measured it — otherwise the tests below pass against a Firefly that does not
exist.

- [ ] **Step 1: Make stored splits look like Firefly's read-back.** In `create` and `put`, store each
  split through `echo(split)`:

```java
    /**
     * What Firefly gives back for what it was given (measured, Stage 0 A5/A4): a date-time, a long
     * decimal, an id beside every name. An id that is present wins over a name, as Firefly resolves
     * it first — echoing a stale id is the failure that looks like success.
     */
    private Map<String, Object> echo(Map<String, Object> in) {
        Map<String, Object> out = new LinkedHashMap<>(in);
        Object date = in.get("date");
        if (date instanceof String d && d.length() == 10) {
            out.put("date", d + "T00:00:00+10:00");
        }
        Object amount = in.get("amount");
        if (amount instanceof String a) {
            out.put("amount", new java.math.BigDecimal(a).setScale(12).toPlainString());
        }
        for (String side : List.of("source", "destination")) {
            Object id = in.get(side + "_id");
            Object name = in.get(side + "_name");
            if (id == null && name != null) {
                out.put(side + "_id", counterpartyIds.computeIfAbsent(String.valueOf(name),
                    n -> String.valueOf(900 + counterpartyIds.size())));
            } else if (id != null) {
                out.put(side + "_name", accountName(String.valueOf(id), name));
            }
        }
        return out;
    }

    private final Map<String, String> counterpartyIds = new ConcurrentHashMap<>();

    /** An id that names a counterparty keeps that counterparty: the id wins over a new name. */
    private String accountName(String id, Object fallback) {
        return counterpartyIds.entrySet().stream().filter(e -> e.getValue().equals(id))
            .map(Map.Entry::getKey).findFirst()
            .orElseGet(() -> accounts.entrySet().stream().filter(e -> id.equals(e.getValue().get("id")))
                .map(Map.Entry::getKey).findFirst().orElse(fallback == null ? "" : String.valueOf(fallback)));
    }
```

Store with `byId.put(id, new Group(id, null, List.of(echo(split))))` in `create` and with the splits
mapped through `echo` in `put`. In `put`, also re-index `idByExternal` (drop the group's old
external ids, add the new ones), because a re-key (Stage 5) changes `external_id` on a PUT and a later
duplicate check must see the new one. If Stage 0 A4 showed the **name** wins, delete the "id wins" branch
(`else if (id != null)` keeps `name` when one was sent) and say so in the comment.

- [ ] **Step 2: Run the egress tests**

Run: `mvn -q -pl trex-v2-egress -am test`
Expected: PASS — existing tests read `tags`, `category_name` and `external_id`, which `echo` keeps.
If one fails on a date or amount comparison, that test was asserting an echo Firefly never makes;
fix the assertion, not the fake.

- [ ] **Step 3: Commit**

```bash
git add trex-v2-egress
git commit -m "test(egress): the fake Firefly echoes dates, amounts and account ids as Firefly does"
```

### Task 4.3: `converge` replaces `retag`; verify rebuilds fingerprints

**Files:**
- Modify: `trex-v2-egress/src/main/java/trex/v2/egress/firefly/FireflyEgress.java` (plan loop, `create`, `retag` → `converge`, `record`, `rebuildFromFirefly`, `Outcome`)
- Modify: `trex-v2-egress/src/main/java/trex/v2/egress/firefly/FireflyClient.java` (`groupOrNull` — null on 404, D9)
- Modify: `trex-v2-dist/src/main/java/trex/v2/cli/EgressCommand.java:158-160` (summary line)
- Test: `trex-v2-egress/src/test/java/trex/v2/egress/firefly/FireflyEgressTest.java`

**Interfaces:**
- Consumes: `Content`, `Content.HAND_SPLIT`, `Content.verified`, `Projection.Posting.split()`, `Projection.tags`, `AccountMap.ids()`, `FireflyClient.isOurs`.
- Produces:
  - `public record Outcome(int creates, int rekeys, int retags, int updates, int unchanged, int orphans, int removed, int preserved)` with `empty()` true iff `creates + rekeys + retags + updates + orphans == 0`
  - `private Step converge(HubUnit unit, String groupId, String revision, Map<String, ProjectionState> known)` where `enum Step { UNCHANGED, RETAGGED, UPDATED, PRESERVED, NOT_OURS }`
  - projection state's `stateHash` now holds a `Content` fingerprint or `Content.HAND_SPLIT`

- [ ] **Step 1: Write the failing tests** (in `FireflyEgressTest`; add a helper)

```java
    /** A group as a previous run left it, and the state that run recorded. */
    private static String projected(FakeFirefly fake, FakeHub hub, String unitId, String amount,
                                    String category, String stateHash) {
        String gid = fake.seedGroup(unitId, null, List.of(new java.util.HashMap<>(Map.of(
            "external_id", unitId, "type", "withdrawal", "date", "2026-09-01T00:00:00+10:00",
            "amount", amount, "currency_code", "AUD", "source_id", "1", "source_name", "ING Savings",
            "destination_id", "901", "destination_name", "COLES", "description", "COLES 1234",
            "category_name", category, "tags", List.of("trex", "trex-category:" + category)))));
        hub.projection.put(unitId, Map.of("unitId", unitId, "unitKind", "EXTERNAL", "groupId", gid,
            "category", category, "stateHash", stateHash, "configRevision", "cfg", "deriveVersion", "d",
            "verifiedAt", "t"));
        return gid;
    }

    @Test
    void aRestatedAmountReachesFirefly() throws Exception {
        try (FakeFirefly fake = new FakeFirefly(); FakeHub hub = new FakeHub()) {
            projected(fake, hub, "ext1", "10.00", "GROCERIES", "fp1:stale");
            hub.units = List.of(FakeHub.unit("ext1", "EXTERNAL", 1, "ing-savings", null, "2026-09-01",
                -1200, "GROCERIES", "COLES 1234", "h2"));
            FireflyEgress.Outcome out = egress(hub, fake, accounts(), FireflyEgress.Mode.APPLY, false).run();
            assertEquals(1, out.updates());
            assertEquals(0, out.retags(), "a content move is not a retag (F10)");
            assertEquals(0, new java.math.BigDecimal("12.00").compareTo(
                new java.math.BigDecimal((String) splitOf(fake, "ext1").get("amount"))));
            assertTrue(egress(hub, fake, accounts(), FireflyEgress.Mode.PLAN, false).run().empty());
            assertTrue(egress(hub, fake, accounts(), FireflyEgress.Mode.VERIFY, false).run().empty(),
                "and a rebuilt state agrees (F3)");
        }
    }

    @Test
    void verifyFindsContentDriftThatTheStateDoesNotKnowAbout() throws Exception {
        try (FakeFirefly fake = new FakeFirefly(); FakeHub hub = new FakeHub()) {
            hub.units = List.of(FakeHub.unit("ext1", "EXTERNAL", 1, "ing-savings", null, "2026-09-01",
                -1000, "GROCERIES", "COLES 1234", "h1"));
            egress(hub, fake, accounts(), FireflyEgress.Mode.APPLY, false).run();
            splitOf(fake, "ext1").put("amount", "99.000000000000");   // edited in Firefly
            assertFalse(egress(hub, fake, accounts(), FireflyEgress.Mode.VERIFY, false).run().empty());
            egress(hub, fake, accounts(), FireflyEgress.Mode.APPLY, false).run();
            assertEquals(0, new java.math.BigDecimal("10.00").compareTo(
                new java.math.BigDecimal((String) splitOf(fake, "ext1").get("amount"))), "trex wins (D2)");
        }
    }

    @Test
    void aHandSplitGroupIsNeverRewrittenAndVerifiesClean() throws Exception {
        try (FakeFirefly fake = new FakeFirefly(); FakeHub hub = new FakeHub()) {
            Map<String, Object> a = new java.util.HashMap<>(Map.of("external_id", "ext1", "type", "withdrawal",
                "date", "2026-09-01T00:00:00+10:00", "amount", "6.00", "currency_code", "AUD",
                "source_id", "1", "destination_name", "COLES", "description", "food",
                "category_name", "GROCERIES", "tags", List.of("trex", "trex-category:GROCERIES")));
            Map<String, Object> b = new java.util.HashMap<>(a);
            b.put("amount", "4.00");
            b.put("description", "soap");
            String gid = fake.seedGroup("ext1", "COLES 1234", List.of(a, b));
            hub.projection.put("ext1", Map.of("unitId", "ext1", "unitKind", "EXTERNAL", "groupId", gid,
                "category", "GROCERIES", "stateHash", "", "configRevision", "cfg", "deriveVersion", "d",
                "verifiedAt", "t"));
            hub.units = List.of(FakeHub.unit("ext1", "EXTERNAL", 1, "ing-savings", null, "2026-09-01",
                -1200, "GROCERIES", "COLES 1234", "h2"));     // the bank restated 10.00 -> 12.00
            egress(hub, fake, accounts(), FireflyEgress.Mode.APPLY, false).run();
            var splits = fake.groups().get(gid).splits();
            assertEquals(2, splits.size());
            assertEquals("6.00", splits.get(0).get("amount"), "your split stays as you made it");
            assertEquals(Content.HAND_SPLIT, hub.projection.get("ext1").get("stateHash"));
            assertTrue(egress(hub, fake, accounts(), FireflyEgress.Mode.VERIFY, false).run().empty(),
                "a hand-split group does not fail verify forever");
        }
    }

    @Test
    void oldStateHashesAreCheckedOnceWithoutWrites() throws Exception {
        try (FakeFirefly fake = new FakeFirefly(); FakeHub hub = new FakeHub()) {
            hub.units = List.of(FakeHub.unit("ext1", "EXTERNAL", 1, "ing-savings", null, "2026-09-01",
                -1000, "GROCERIES", "COLES 1234", "h1"));
            egress(hub, fake, accounts(), FireflyEgress.Mode.APPLY, false).run();
            Map<String, Object> old = new java.util.HashMap<>(hub.projection.get("ext1"));
            old.put("stateHash", "9f2c-an-old-hub-unit-hash");
            hub.projection.put("ext1", old);
            int puts = fake.puts.get();
            FireflyEgress.Outcome first = egress(hub, fake, accounts(), FireflyEgress.Mode.APPLY, false).run();
            assertEquals(puts, fake.puts.get(), "Firefly already matches: nothing written");
            assertEquals(1, first.unchanged());
            assertTrue(egress(hub, fake, accounts(), FireflyEgress.Mode.PLAN, false).run().empty(), "and quiet after");
        }
    }

    @Test
    void aDuplicateCreateConvergesTheExistingGroup() throws Exception {
        try (FakeFirefly fake = new FakeFirefly(); FakeHub hub = new FakeHub()) {
            fake.seedGroup("ext1", null, List.of(new java.util.HashMap<>(Map.of("external_id", "ext1",
                "type", "withdrawal", "date", "2026-09-01T00:00:00+10:00", "amount", "10.00",
                "currency_code", "AUD", "source_id", "1", "destination_name", "COLES",
                "description", "COLES 1234", "category_name", "GROCERIES",
                "tags", List.of("trex", "trex-category:GROCERIES")))));
            hub.units = List.of(FakeHub.unit("ext1", "EXTERNAL", 1, "ing-savings", null, "2026-09-01",
                -1000, "FOOD", "COLES 1234", "h1"));      // state lost; trex has since moved the category
            egress(hub, fake, accounts(), FireflyEgress.Mode.APPLY, false).run();
            assertEquals("FOOD", splitOf(fake, "ext1").get("category_name"), "F7: not left stale");
        }
    }
```

The existing tests that assert `outcome.retags()` keep their meaning (a category move is a retag).

One more test for the contract (D9/R2): apply a unit, delete its group in the fake, move its
category, and assert the apply stops with `Refused` naming the unit and the group — no raw
`IOException`.

- [ ] **Step 2: Run them to see them fail**

Run: `mvn -q -pl trex-v2-egress -am test -Dtest=FireflyEgressTest`
Expected: FAIL — `updates()` is 0 and the amount is still `10.00`; the hand-split and old-hash tests
fail on `stateHash`; the duplicate test leaves `GROCERIES`; `Outcome` has no `rekeys()`/`updates()` in
the right places (compile error first — fix by changing `Outcome` in Step 3).

- [ ] **Step 3: Implement.** In `FireflyEgress`:

`Outcome`:

```java
    public record Outcome(int creates, int rekeys, int retags, int updates, int unchanged, int orphans,
                          int removed, int preserved) {

        public boolean empty() {
            return creates == 0 && rekeys == 0 && retags == 0 && updates == 0 && orphans == 0;
        }
    }
```

The plan loop (replace the `else if` chain that fills `moves`):

```java
            ProjectionState row = known.get(unit.unitId());
            if (row == null) {
                creates.add(unit);
                continue;
            }
            boolean categoryMoved = !unit.category().equals(row.category());
            boolean contentUnknown = !Content.verified(row.stateHash());
            boolean contentMoved = !contentUnknown && !Content.HAND_SPLIT.equals(row.stateHash())
                && !row.stateHash().equals(expected(unit, revision).fingerprint());
            if (categoryMoved || contentUnknown || contentMoved) {
                moves.add(unit);
            } else {
                unchanged++;
            }
```

with

```java
    private Content expected(HubUnit unit, String revision) {
        return Content.expected(Projection.of(unit, revision, accounts).split());
    }
```

In PLAN/VERIFY output, print each move with its reason:
`out.println("  " + (categoryMoved ? "RETAG " : contentUnknown ? "CHECK " : "UPDATE") + " " + u.unitId() + …)`
(compute the reason per unit in the print loop the same way).

`create` — a duplicate converges instead of recording blindly:

```java
            case FireflyClient.Result.Created c -> {
                record(unit, c.groupId(), expected(unit, revision).fingerprint(), revision, known);
                yield Step.CREATED;
            }
            case FireflyClient.Result.Duplicate d -> converge(unit, d.groupId(), revision, known);
```

(change `create` to return `Step`, add `CREATED` to `Step`, and count `CREATED` as a create in
`run()`; a duplicate counts as whatever `converge` returned.)

`converge` replaces `retag`:

```java
    /**
     * Read the group, change only what we own and what moved, write back only if something did
     * (V2-SPEC.md §11.1). Read-modify-write is a rule: a body built from scratch collapses a split
     * group, and group_title must come back or Firefly refuses a multi-split group. On a single split
     * trex is the authority for content (the bank said so); a group you split by hand keeps its
     * content, and only its category (where untouched) and tags move.
     */
    @SuppressWarnings("unchecked")
    private Step converge(HubUnit unit, String groupId, String revision, Map<String, ProjectionState> known)
            throws IOException, InterruptedException {
        JsonNode group = firefly.group(groupId).path("data").path("attributes");
        JsonNode splits = group.path("transactions");
        if (splits.isEmpty() || !FireflyClient.isOurs(splits.get(0))) {
            out.println("  NOT OURS " + unit.unitId() + " (group " + groupId + ") — the trex tag is gone; left alone");
            return Step.NOT_OURS;
        }
        Map<String, Object> want = Projection.of(unit, revision, accounts).split();
        Content expected = Content.expected(want);
        boolean single = splits.size() == 1;
        boolean contentMoves = single && !expected.equals(Content.observed(splits.get(0), accounts.ids()));
        if (!single) {
            long sum = 0;
            for (JsonNode s : splits) {
                sum += Content.cents(s.path("amount").asText("0"));
            }
            if (sum != Content.cents(expected.amount())) {
                out.println("  HAND-SPLIT " + unit.unitId() + " (group " + groupId + ") — the bank now says "
                    + expected.amount() + "; your splits total " + Projection.amount(sum)
                    + " and are left as you made them");
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        String title = group.path("group_title").asText("");
        if (!title.isEmpty()) {
            body.put("group_title", title);
        }
        List<Map<String, Object>> splitsOut = new ArrayList<>();
        boolean preserved = false;
        boolean changed = contentMoves;
        for (JsonNode split : splits) {
            Map<String, Object> map = trex.v2.log.Json.mapper().convertValue(split, Map.class);
            String tagCategory = FireflyClient.tagCategory(split);
            String current = split.path("category_name").asText(null);
            if (tagCategory != null && tagCategory.equals(current)) {
                if (!unit.category().equals(current)) {
                    map.put("category_name", unit.category());
                    // Firefly resolves category_id before category_name: a stale id makes the name a
                    // silent no-op — the tag moves and the category stays put.
                    map.remove("category_id");
                    changed = true;
                }
            } else {
                preserved = true;
            }
            List<String> tags = Projection.tags(split.path("tags"), unit.category());
            if (!tags.equals(trex.v2.log.Json.mapper().convertValue(split.path("tags"), List.class))) {
                changed = true;
            }
            map.put("tags", tags);
            if (contentMoves) {
                applyContent(map, want);
            }
            splitsOut.add(map);
        }
        String fingerprint = single ? expected.fingerprint() : Content.HAND_SPLIT;
        if (!changed) {
            record(unit, groupId, fingerprint, revision, known);
            return Step.UNCHANGED;
        }
        body.put("apply_rules", false);
        body.put("transactions", splitsOut);
        FireflyClient.Result result = firefly.put(groupId, body);
        if (result instanceof FireflyClient.Result.Failed f) {
            throw new Refused("Firefly refused the update of " + unit.unitId()
                + " (group " + groupId + "): " + f.status() + " " + f.message());
        }
        record(unit, groupId, fingerprint, revision, known);
        return contentMoves ? Step.UPDATED : preserved ? Step.PRESERVED : Step.RETAGGED;
    }

    /**
     * The content we own, from the posting. A side moved by name drops its id, and a side moved by
     * id drops its name: Firefly resolves the id first (measured for categories; Stage 0 A4 for
     * accounts), so a stale id would win silently.
     */
    private static void applyContent(Map<String, Object> map, Map<String, Object> want) {
        for (String key : List.of("type", "date", "amount", "currency_code", "description", "external_id")) {
            map.put(key, want.get(key));
        }
        map.remove("currency_id");
        for (String side : List.of("source", "destination")) {
            map.remove(side + "_id");
            map.remove(side + "_name");
            if (want.containsKey(side + "_id")) {
                map.put(side + "_id", want.get(side + "_id"));
            }
            if (want.containsKey(side + "_name")) {
                map.put(side + "_name", want.get(side + "_name"));
            }
        }
    }
```

(`Projection.amount(long)` is package-private and already exists; make `Content.cents(String)`
package-private rather than private so `converge` can sum a hand-split group.)

`record` takes the fingerprint:

```java
    private void record(HubUnit unit, String groupId, String fingerprint, String revision,
                        Map<String, ProjectionState> known) {
        ProjectionState state = new ProjectionState(unit.unitId(), unit.unitKind(), groupId,
            unit.category(), fingerprint, revision, deriveVersion, Instant.now().toString());
        known.put(unit.unitId(), state);
        hub.record(false, List.of(state));
    }
```

`run()` counts by `Step`: `CREATED` → creates; `UPDATED` → updates; `RETAGGED` → retags;
`PRESERVED` → preserved (as today, not also a retag); `UNCHANGED` → unchanged; `NOT_OURS` → neither
(printed).

The PLAN/VERIFY early return reports what it would do, split by reason:

```java
            int categoryMoves = (int) moves.stream()
                .filter(u -> !u.category().equals(known.get(u.unitId()).category())).count();
            return new Outcome(creates.size(), 0, categoryMoves, moves.size() - categoryMoves, unchanged,
                orphans.size(), 0, 0);
```

(Stage 5 replaces the `0` re-key count with `rekeys.size()`.)

`rebuildFromFirefly` records what Firefly actually holds:

```java
    private Map<String, ProjectionState> rebuildFromFirefly() throws IOException, InterruptedException {
        Map<String, ProjectionState> out = new TreeMap<>();
        for (FireflyClient.Existing e : firefly.allTransactions()) {
            JsonNode splits = e.group().path("attributes").path("transactions");
            String fingerprint = splits.size() == 1
                ? Content.observed(splits.get(0), accounts.ids()).fingerprint() : Content.HAND_SPLIT;
            String kind = e.externalId().startsWith("TRF-") ? "TRANSFER" : "EXTERNAL";
            out.put(e.externalId(), new ProjectionState(e.externalId(), kind, e.groupId(),
                e.projectedCategory() == null ? "" : e.projectedCategory(), fingerprint, "", deriveVersion,
                Instant.now().toString()));
        }
        return out;
    }
```

In `EgressCommand`, the summary line:

```java
            System.out.printf("done: %d created, %d re-keyed, %d updated, %d retagged, %d unchanged, "
                    + "%d orphan(s), %d removed, %d of your edits preserved%n",
                outcome.creates(), outcome.rekeys(), outcome.updates(), outcome.retags(), outcome.unchanged(),
                outcome.orphans(), outcome.removed(), outcome.preserved());
```

`rekeys` is always 0 until Stage 5.

**A missing group is a named stop (D9/R2).** Add `FireflyClient.groupOrNull(groupId)` (null on 404;
`group()` keeps throwing) and have `converge` stop with `throw new Refused("group " + groupId
+ " for unit " + unit.unitId() + " is missing in Firefly — run --validate; to recreate it run
--verify then --apply")` when it is null. Never let the 404 surface as an `IOException`, and never
recreate from `converge`.

- [ ] **Step 4: Run all tests**

Run: `mvn -q test`
Expected: PASS. Fix any other caller of the old `Outcome` constructor (search: `new Outcome(`,
`new FireflyEgress.Outcome(`). An existing test that seeds a split **without** content fields
(type, date, amount, currency_code, source_id, destination_name, description) now also sees a content
move and counts an update instead of a retag: give its seeded split the content its unit would post,
so the test keeps isolating the category behaviour it was written for. Do not weaken its assertions.

- [ ] **Step 5: Update §11.1 (drop "fingerprint" from the not-yet-built sentence); commit.**

```bash
git add trex-v2-egress trex-v2-dist V2-SPEC.md
git commit -m "fix(egress): content moves reach Firefly; verify rebuilds fingerprints; a duplicate converges"
```

- [ ] **Step 6: Live check on the dev stack (operator).** `--verify` once: expect a list of `CHECK`
  rows on the first run (old hashes), then `--apply` (GETs, no PUTs where Firefly matches), then
  `--verify` empty. **Run this against the throwaway dev instance only (D8): the kept instance is
  refed after Stage 5, and a Stage-4-only apply there would leave transfers without `legs=`.**

### Task 4.4: `--validate` — the contract checker (D9; review R2)

**Files:**
- Create: `trex-v2-egress/src/main/java/trex/v2/egress/firefly/Validate.java`
- Modify: `trex-v2-egress/src/main/java/trex/v2/egress/firefly/FireflyClient.java` (`inventory()` — every group, untagged included)
- Modify: `trex-v2-dist/src/main/java/trex/v2/cli/EgressCommand.java` (`--validate`: resolve, run `Validate.check`, print, exit 1 on a violation)
- Test: `trex-v2-egress/src/test/java/trex/v2/egress/firefly/ValidateTest.java`

**Interfaces:**
- Produces: `record Validate.Finding(String kind, String unitId, String groupId, String detail)`;
  `static List<Validate.Finding> Validate.check(Units units, List<ProjectionState> state,
  List<FireflyClient.Existing> inventory, AccountMap accounts)` — pure, read-only, sorted.
- `--validate` prints one line per finding, then `validate: N finding(s)`; exit 1 when any of
  `MISSING`, `UNTAGGED`, `TAMPERED`, `DRIFT` is present — each one a Firefly-side edit outside the
  contract. `BEHIND`, `ORPHAN`, `HAND_SPLIT` and a category override are informational lines: they
  describe normal states (work the next apply does, or edits the contract allows). It writes neither
  Firefly nor the hub.

**The rule that keeps it quiet (review V1, V2):** a violation is something **Firefly changed**, never
something **trex moved on from**. `--validate` runs on a timer between ingests and applies, so a
class that also fires for pending trex-side work, or for values that go stale by design, would exit 1
on ordinary days — the noise the daily-use goal rules out. Two consequences:

- the first notes line is checked for **shape**, never for its values: `rules=` goes stale on every
  unchanged row after any rule edit, and `n=` moves when a new observation of the same content
  lands; both are refreshed only when a group converges for another reason (Task 5.2);
- content is judged against the **recorded** fingerprint, which is what trex last wrote: Firefly
  differing from it is an edit (`DRIFT`); Firefly matching it while trex's expected content differs
  is work the next apply does (`BEHIND`).

**Rules:**
- `MISSING` — a state row whose unit is current and whose group is absent from the inventory (the
  group was deleted in Firefly);
- `UNTAGGED` — an inventory group whose `external_id` names a current unit but which carries no
  `trex` tag (ownership lost);
- `TAMPERED` — a state row whose group exists and is ours but whose `external_id` is not the state
  row's unit id, or whose first notes line no longer parses as `trex n=<digits> …`, or (a transfer
  with a `legs=` value) whose legs, each resolved through `Units.resolved()`, are not the unit's legs.
  The values of `n=` and `rules=` are **never** compared (V1);
- `DRIFT` — a single-split group whose observed fingerprint (`Content.observed(...).fingerprint()`)
  differs from the state row's recorded, verified fingerprint: Firefly was edited since trex wrote it.
  Trex's next converge overwrites it (D2). A state row whose hash is not verified (`Content.verified`
  false) or is `Content.HAND_SPLIT` is never `DRIFT` (V2);
- `BEHIND` — a single-split group whose observed fingerprint equals the recorded one while
  `Content.expected` (from `Projection.of(unit, units.configRevision(), accounts)`) differs: trex has
  moved (a restatement, a re-parse) and the next apply updates it. Informational (V2);
- `ORPHAN` — a state row with no current unit (status only; `--remove-orphans` is the instruction);
- `HAND_SPLIT` — a hand-split group whose splits no longer sum to the unit (each split's amount at
  cents, rounded as Task 4.1; reported on every run, never rewritten).

- [ ] **Step 1: Write the failing tests** (in `ValidateTest`, with `FakeFirefly`/`FakeHub` and the
  `projected` helper): a deleted group is `MISSING`; a group whose tag was removed is `UNTAGGED`;
  a hand-edited single-split amount is `DRIFT`; a hand-split sum mismatch is `HAND_SPLIT`; and
  `validate` writes nothing (`fake.posts/puts/deletes` and `hub.projection` unchanged). Plus the
  three quiet cases that must **not** exit 1 (V1, V2):
  - `aRuleEditLeavesStaleNotesButNoViolation` — apply, then change `hub.configRevision` and a
    unit's `n` without touching its content or category: no finding, exit 0;
  - `aTrexSideRestatementIsBehindNotDrift` — apply, then change the unit's amount in `hub.units`
    only: one `BEHIND`, exit 0;
  - `aMangledNotesLineIsTampered` — apply, then overwrite the group's notes with `"my notes"`: one
    `TAMPERED`, exit 1.
- [ ] **Step 2: Run them to see them fail.** Expected: compile error — `Validate` does not exist.
- [ ] **Step 3: Implement** per the interfaces above. `inventory()` is `allTransactions` with the
  `isOurs` filter removed (the tags are kept, so an untagged group can be named).
- [ ] **Step 4: Run all tests.** Run: `mvn -q test`. Expected: PASS.
- [ ] **Step 5: Update §11.1 (drop "validation" from the not-yet-built sentence); commit; open the PR (Tasks 4.1–4.4)**

```bash
git add trex-v2-egress trex-v2-dist V2-SPEC.md
git commit -m "feat(egress): the Firefly contract checker"
```

---

## 8. Stage 5 — re-key superseded units; report replacements (F9, F11; gates D3, D5, Stage 0 A1)

If Stage 0 A1 measured that Firefly does **not** change `external_id` on a PUT, build Tasks 5.1, 5.2
and the *report* half of 5.3 only (every supersede becomes a reported replacement), and record that
D3 fell back.

### Task 5.1: The hub publishes legs and the supersession map

**Files:**
- Modify: `trex-v2-hub/src/main/java/trex/v2/hub/api/ProjectionUnit.java` (add `List<String> legs`)
- Modify: `trex-v2-hub/src/main/java/trex/v2/hub/api/UnitsResponse.java` (add `Map<String, String> resolved`)
- Modify: `trex-v2-hub/src/main/java/trex/v2/hub/HubQueries.java` (`projectionUnits` fills `legs`; new `resolvedIds()`)
- Modify: `trex-v2-hub/src/main/java/trex/v2/hub/HubSql.java` (new `RESOLVED_IDS`)
- Modify: `trex-v2-hub/src/main/java/trex/v2/hub/HubService.java:351-355` (`units()` passes it)
- Modify: `trex-v2-egress/src/main/java/trex/v2/egress/hub/HubClient.java` (`HubUnit.legs`, `Units.resolved`)
- Modify: `trex-v2-egress/src/test/java/trex/v2/egress/hub/FakeHub.java` (`legs`, `resolved`)
- Modify: `trex-v2-egress/src/test/java/trex/v2/egress/firefly/ProjectionTest.java` (existing `new HubUnit(...)` calls gain `List.of()` legs)
- Test: `trex-v2-hub/src/test/java/trex/v2/hub/HubUnitsTest.java`

**Interfaces:**
- Produces:
  - `ProjectionUnit(..., String unitHash, List<String> legs)` — TRANSFER: `[from_leg, to_leg]` exactly as the transfer row holds them (a clearing pair's account side is the clearing ref); EXTERNAL: `[]`
  - `UnitsResponse(long asOfN, String configRevision, String deriveVersion, String hashVersion, List<ProjectionUnit> units, Map<String, String> resolved)` — every `chain_resolved` row whose id differs from its current id
  - egress: `HubUnit(..., String unitHash, List<String> legs)`; `Units(..., List<HubUnit> units, Map<String, String> resolved)`
  - test: `FakeHub.unit(...)` unchanged (legs `[]`); new `FakeHub.transfer(String unitId, long n, String from, String to, String date, long amount, String fromLeg, String toLeg)`; `public volatile Map<String, String> resolved = new HashMap<>()`

- [ ] **Step 1: Write the failing test** (in `HubUnitsTest`)

```java
    @Test
    void theUnitsCarryTheSupersessionMapAndTransferLegs(@TempDir Path dir) throws Exception {
        Path configDir = dir.resolve("config");
        Files.createDirectories(configDir);
        config(configDir);
        Path journal = dir.resolve("trex.jsonl");
        try (JsonlJournal j = new JsonlJournal(journal)) {
            j.appendBatch(List.of(
                fact(1, "old", "ing-savings", -1000, "COLES 1234", Observation.POSTED),
                fact(2, "new", "ing-savings", -1000, "COLES 1234 SYDNEY", Observation.POSTED),
                new trex.v2.core.Decision.Supersede(3, "old", "new", "reparse",
                    trex.v2.core.Actor.SYSTEM, null, AT),
                fact(4, "t1", "ing-savings", -500, "Transfer to Savings 1111", Observation.POSTED),
                fact(5, "t2", "ing-orange", 500, "Transfer from Savings 1111", Observation.POSTED)));
        }
        try (HubService hub = HubService.start(new HubConfig(journal, dir.resolve("trex.sqlite"),
                configDir, "127.0.0.1", 0, 50))) {
            await(() -> hub.units().units().size() == 2);
            UnitsResponse units = hub.units();
            assertEquals(java.util.Map.of("old", "new"), units.resolved());
            var transfer = units.units().stream().filter(u -> u.unitKind().equals("TRANSFER")).findFirst().orElseThrow();
            assertEquals(java.util.Set.of("t1", "t2"), java.util.Set.copyOf(transfer.legs()));
            var external = units.units().stream().filter(u -> u.unitKind().equals("EXTERNAL")).findFirst().orElseThrow();
            assertEquals("new", external.unitId());
            assertTrue(external.legs().isEmpty());
        }
    }
```

- [ ] **Step 2: Run it to see it fail**

Run: `mvn -q -pl trex-v2-hub -am test -Dtest=HubUnitsTest`
Expected: FAIL — compile error (`resolved()`, `legs()` do not exist).

- [ ] **Step 3: Implement.** `HubSql`:

```java
    static final String RESOLVED_IDS =
        "SELECT id, current_id FROM chain_resolved WHERE id <> current_id ORDER BY id";
```

`HubQueries`:

```java
    /** Superseded ids and where they resolve now: how the egress re-keys a group instead of orphaning it. */
    public Map<String, String> resolvedIds() {
        return read(conn -> {
            Map<String, String> out = new java.util.TreeMap<>();
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(HubSql.RESOLVED_IDS)) {
                while (rs.next()) {
                    out.put(rs.getString(1), rs.getString(2));
                }
            }
            return out;
        });
    }
```

In `projectionUnits`, pass `List.of(pair[0], pair[1])` as the new last argument of each TRANSFER
`ProjectionUnit` (both the clearing and the two-fact branch) and `List.of()` for EXTERNAL.
`ProjectionUnit` gains `List<String> legs` as its last component (import `java.util.List`);
`UnitsResponse` gains `java.util.Map<String, String> resolved` as its last component; `HubService.units()`
passes `reads.resolvedIds()`. Mirror both on the egress side (`HubClient.HubUnit`, `HubClient.Units`).
`FakeHub`:

```java
    public volatile Map<String, String> resolved = new java.util.HashMap<>();
```

include `"resolved", resolved` in the `/api/units` map, add `"legs", List.of()` to `unit(...)`, and:

```java
    public static Map<String, Object> transfer(String unitId, long n, String from, String to, String date,
                                               long amount, String fromLeg, String toLeg) {
        Map<String, Object> u = unit(unitId, "TRANSFER", n, from, to, date, amount, "TRANSFER",
            "Transfer", "h-" + unitId);
        u.put("legs", List.of(fromLeg, toLeg));
        return u;
    }
```

- [ ] **Step 4: Run all tests**

Run: `mvn -q test`
Expected: PASS (fix every `new ProjectionUnit(` / `new HubUnit(` / `new UnitsResponse(` call site the
compiler names).

- [ ] **Step 5: Commit**

```bash
git checkout -b fix/firefly-egress-rekey
git add trex-v2-hub trex-v2-egress
git commit -m "feat(hub): units carry transfer legs and the supersession map"
```

### Task 5.2: A transfer's notes record its legs

**Files:**
- Modify: `trex-v2-egress/src/main/java/trex/v2/egress/firefly/Projection.java` (`notes`)
- Modify: `trex-v2-egress/src/main/java/trex/v2/egress/firefly/FireflyClient.java` (`legs(String)`)
- Modify: `trex-v2-egress/src/main/java/trex/v2/egress/firefly/FireflyEgress.java` (`converge` refreshes the first notes line)
- Test: `ProjectionTest.java`, `FireflyClientTest.java`

**Interfaces:**
- Produces:
  - `static String Projection.notes(String existing, HubUnit unit, String configRevision)` — replaces the first line when it starts with `trex `, otherwise prepends; the first line is `trex n=<n> rules=<rev>` plus ` legs=<a>,<b>` when the unit has legs
  - `static List<String> FireflyClient.legs(String notes)` — the legs on the first line, or `[]`

- [ ] **Step 1: Write the failing tests**

```java
    // ProjectionTest
    @Test
    void notesReplaceOnlyOurFirstLine() {
        HubClient.HubUnit unit = new HubClient.HubUnit("TRF-1", "TRANSFER", 7, "ing-savings", "ing-orange",
            java.time.LocalDate.of(2026, 9, 1), 500, "AUD", "TRANSFER", "STRUCTURAL", "MATCHED", false, false,
            "Transfer to Savings", "h", List.of("a", "b"));
        assertEquals("trex n=7 rules=cfg legs=a,b\nTransfer to Savings\nmy own note",
            Projection.notes("trex n=3 rules=old\nTransfer to Savings\nmy own note", unit, "cfg"));
        assertEquals("trex n=7 rules=cfg legs=a,b\nwritten by hand",
            Projection.notes("written by hand", unit, "cfg"));
    }

    // FireflyClientTest
    @Test
    void legsAreReadFromTheFirstNotesLine() {
        assertEquals(List.of("a", "b"), FireflyClient.legs("trex n=7 rules=cfg legs=a,b\nraw"));
        assertEquals(List.of(), FireflyClient.legs("trex n=7 rules=cfg\nraw"));
        assertEquals(List.of(), FireflyClient.legs(null));
    }
```

- [ ] **Step 2: Run them to see them fail**

Run: `mvn -q -pl trex-v2-egress -am test -Dtest='ProjectionTest,FireflyClientTest'`
Expected: FAIL — the methods do not exist.

- [ ] **Step 3: Implement.** `Projection` — the posting uses the new form with no existing notes:

```java
    /**
     * Our first notes line, and anything below it kept: what a rebuild needs that the tags do not
     * carry — the journal n, the rules revision, and a transfer's legs (how a re-parsed transfer is
     * found again, Stage 5). The raw description is written once, under it, on create.
     */
    static String notes(String existing, HubUnit unit, String configRevision) {
        String first = "trex n=" + unit.n() + " rules=" + (configRevision == null ? "none" : configRevision)
            + (unit.legs() == null || unit.legs().isEmpty() ? "" : " legs=" + String.join(",", unit.legs()));
        if (existing == null || existing.isEmpty()) {
            return first + "\n" + unit.rawDescription();
        }
        int nl = existing.indexOf('\n');
        String head = nl < 0 ? existing : existing.substring(0, nl);
        String rest = nl < 0 ? "" : existing.substring(nl);
        return head.startsWith("trex ") ? first + rest : first + "\n" + existing;
    }
```

and in `Projection.of` replace `split.put("notes", notes(unit, configRevision))` with
`split.put("notes", notes(null, unit, configRevision))`; delete the old two-argument `notes`.

`FireflyClient`:

```java
    private static final Pattern LEGS = Pattern.compile("\\blegs=(\\S+)");

    /** A transfer's legs, from our first notes line — empty for a group projected before Stage 5. */
    static List<String> legs(String notes) {
        if (notes == null) {
            return List.of();
        }
        int nl = notes.indexOf('\n');
        Matcher m = LEGS.matcher(nl < 0 ? notes : notes.substring(0, nl));
        return m.find() ? List.of(m.group(1).split(",")) : List.of();
    }
```

In `converge`, after the tag line for the **first** split only, refresh the notes and count it as a
change when it differs:

```java
            if (splitsOut.isEmpty()) {
                String notes = Projection.notes(split.path("notes").asText(""), unit, revision);
                if (!notes.equals(split.path("notes").asText(""))) {
                    map.put("notes", notes);
                    changed = true;
                }
            }
```

Notes are not part of the fingerprint, so this refresh never forces a write by itself. Under D8 no
kept instance has pre-Stage-5 groups, so it is not a backfill mechanism: it keeps `rules=` current
and writes the new `legs=` when a group is re-keyed.

- [ ] **Step 4: Run the egress tests**

Run: `mvn -q -pl trex-v2-egress -am test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add trex-v2-egress
git commit -m "feat(egress): a transfer's notes carry its legs; our notes line is refreshed, yours kept"
```

### Task 5.3: Orphans are matched to successors — re-keyed or reported

**Files:**
- Create: `trex-v2-egress/src/main/java/trex/v2/egress/firefly/Successors.java`
- Modify: `trex-v2-egress/src/main/java/trex/v2/egress/firefly/FireflyEgress.java` (`run()`)
- Test: `trex-v2-egress/src/test/java/trex/v2/egress/firefly/SuccessorsTest.java`, `FireflyEgressTest.java`

**Interfaces:**
- Consumes: `HubClient.Units.resolved()`, `HubUnit.legs()`, `FireflyClient.legs(String)`, `converge`.
- Produces:
  - `record Successors.Match(String orphanId, List<String> successorIds, boolean sameKind)`
  - `static Successors.Match Successors.of(String orphanId, List<String> orphanLegs, Map<String, String> resolved, List<HubUnit> units)` — `successorIds` empty when there is none

Rules (D3, D5):
- an EXTERNAL orphan resolves through `resolved`; if the result is a current EXTERNAL unit → that one,
  same kind; if it is a leg of a current TRANSFER unit → that transfer, **not** same kind;
- a TRANSFER orphan's legs (from its notes) each resolve through `resolved` (default: itself); a
  current TRANSFER unit with exactly that leg set → same kind; otherwise every current EXTERNAL unit
  among the resolved legs → not same kind (an unpair).

- [ ] **Step 1: Write the failing tests**

```java
package trex.v2.egress.firefly;

import org.junit.jupiter.api.Test;
import trex.v2.egress.hub.HubClient.HubUnit;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SuccessorsTest {

    private static HubUnit external(String id) {
        return new HubUnit(id, "EXTERNAL", 1, "ing-savings", null, LocalDate.of(2026, 9, 1), -1000, "AUD",
            "FOOD", "RULE", "EXTERNAL", false, false, "raw", "h", List.of());
    }

    private static HubUnit transfer(String id, String a, String b) {
        return new HubUnit(id, "TRANSFER", 2, "ing-savings", "ing-orange", LocalDate.of(2026, 9, 1), 500, "AUD",
            "TRANSFER", "STRUCTURAL", "MATCHED", false, false, "raw", "h", List.of(a, b));
    }

    @Test
    void aSupersededExternalRowHasItsSuccessor() {
        var m = Successors.of("old", List.of(), Map.of("old", "new"), List.of(external("new")));
        assertEquals(List.of("new"), m.successorIds());
        assertTrue(m.sameKind());
    }

    @Test
    void aTransferWhoseLegWasSupersededHasTheNewTransfer() {
        var m = Successors.of("TRF-old", List.of("a", "b"), Map.of("a", "a2"),
            List.of(transfer("TRF-new", "a2", "b")));
        assertEquals(List.of("TRF-new"), m.successorIds());
        assertTrue(m.sameKind());
    }

    @Test
    void anUnpairedTransferIsReplacedByItsLegsAcrossKinds() {
        var m = Successors.of("TRF-old", List.of("a", "b"), Map.of(), List.of(external("a"), external("b")));
        assertEquals(List.of("a", "b"), m.successorIds());
        assertFalse(m.sameKind());
    }

    @Test
    void anExternalRowThatBecameALegPointsAtItsTransfer() {
        var m = Successors.of("a", List.of(), Map.of(), List.of(transfer("TRF-1", "a", "b")));
        assertEquals(List.of("TRF-1"), m.successorIds());
        assertFalse(m.sameKind());
    }

    @Test
    void aGenuineOrphanHasNone() {
        assertTrue(Successors.of("gone", List.of(), Map.of(), List.of(external("other"))).successorIds().isEmpty());
    }
}
```

And in `FireflyEgressTest` (Review Focus 4):

```java
    @Test
    void aSupersededTransferLegRekeysTheGroup() throws Exception {
        try (FakeFirefly fake = new FakeFirefly(); FakeHub hub = new FakeHub()) {
            hub.units = List.of(FakeHub.transfer("TRF-old", 1, "ing-savings", "ing-orange", "2026-09-01", 500, "a", "b"));
            egress(hub, fake, accounts(), FireflyEgress.Mode.APPLY, false).run();
            // A re-parse supersedes leg a: the pair's id moves (V2-SPEC.md §4 hashes current ids).
            hub.resolved = Map.of("a", "a2");
            hub.units = List.of(FakeHub.transfer("TRF-new", 2, "ing-savings", "ing-orange", "2026-09-01", 500, "a2", "b"));
            FireflyEgress.Outcome out = egress(hub, fake, accounts(), FireflyEgress.Mode.APPLY, false).run();
            assertEquals(1, out.rekeys());
            assertEquals(0, out.creates(), "no second transfer");
            assertEquals(0, out.orphans());
            assertEquals(1, fake.groups().size());
            assertEquals("TRF-new", splitOf(fake, "TRF-new").get("external_id"));
            assertTrue(egress(hub, fake, accounts(), FireflyEgress.Mode.VERIFY, false).run().empty());
        }
    }

    @Test
    void anUnpairIsReportedAsAReplacementNotRekeyed() throws Exception {
        try (FakeFirefly fake = new FakeFirefly(); FakeHub hub = new FakeHub()) {
            hub.units = List.of(FakeHub.transfer("TRF-1", 1, "ing-savings", "ing-orange", "2026-09-01", 500, "a", "b"));
            egress(hub, fake, accounts(), FireflyEgress.Mode.APPLY, false).run();
            hub.units = List.of(
                FakeHub.unit("a", "EXTERNAL", 1, "ing-savings", null, "2026-09-01", -500, "OTHER", "Transfer", "h"),
                FakeHub.unit("b", "EXTERNAL", 2, "ing-orange", null, "2026-09-01", 500, "OTHER", "Transfer", "h"));
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            new FireflyEgress(new HubClient(hub.url()), new FireflyClient(fake.url(), "token"), accounts(),
                FireflyEgress.Mode.PLAN, false, new PrintStream(bytes), "derive/1").run();
            String plan = bytes.toString();
            assertTrue(plan.contains("ORPHAN TRF-1") && plan.contains("replaced by a, b"), plan);
        }
    }

    @Test
    void anOrphanDeletedInFireflyIsGoneNotAnAbort() throws Exception {            // review V4
        try (FakeFirefly fake = new FakeFirefly(); FakeHub hub = new FakeHub()) {
            hub.units = List.of(FakeHub.transfer("TRF-1", 1, "ing-savings", "ing-orange", "2026-09-01", 500, "a", "b"));
            egress(hub, fake, accounts(), FireflyEgress.Mode.APPLY, false).run();
            fake.groups().clear();                                   // you deleted it in Firefly
            hub.units = List.of();                                   // and trex no longer has the unit
            FireflyEgress.Outcome plan = egress(hub, fake, accounts(), FireflyEgress.Mode.PLAN, false).run();
            assertEquals(0, plan.orphans(), "a gone group is not an orphan to remove");
            egress(hub, fake, accounts(), FireflyEgress.Mode.APPLY, false).run();
            assertTrue(hub.projection.isEmpty(), "and it leaves the accelerator");
        }
    }

    @Test
    void aSupersededUnitWhoseGroupLostItsTagIsCreatedNotStuck() throws Exception {   // review V5
        try (FakeFirefly fake = new FakeFirefly(); FakeHub hub = new FakeHub()) {
            hub.units = List.of(FakeHub.unit("old", "EXTERNAL", 1, "ing-savings", null, "2026-09-01", -1000,
                "GROCERIES", "COLES 1234", "h1"));
            egress(hub, fake, accounts(), FireflyEgress.Mode.APPLY, false).run();
            splitOf(fake, "old").put("tags", List.of("mine"));      // you took it over in Firefly
            hub.resolved = Map.of("old", "new");
            hub.units = List.of(FakeHub.unit("new", "EXTERNAL", 2, "ing-savings", null, "2026-09-01", -1000,
                "GROCERIES", "COLES 1234", "h2"));
            FireflyEgress.Outcome out = egress(hub, fake, accounts(), FireflyEgress.Mode.APPLY, false).run();
            assertEquals(0, out.rekeys());
            assertEquals(1, out.creates(), "the new unit lands");
            assertEquals(2, fake.groups().size(), "your group is left alone");
            assertTrue(egress(hub, fake, accounts(), FireflyEgress.Mode.PLAN, false).run().empty(),
                "and the next plan is quiet, not stuck");
        }
    }
```

One more test (R3): `aSupersededHandSplitTransferRekeysItsIdentity` — project a transfer, split the
group in the fake into two (30.00 + 20.00), supersede a leg, apply; assert one group, **every**
split carries the new `external_id`, the split amounts are untouched, the state is `HAND_SPLIT`,
and `--verify` is empty. Run plan → apply → verify a second time and assert it stays empty.

- [ ] **Step 2: Run them to see them fail**

Run: `mvn -q -pl trex-v2-egress -am test -Dtest='SuccessorsTest,FireflyEgressTest'`
Expected: FAIL — `Successors` missing; then `rekeys` is 0 and `creates` is 1.

- [ ] **Step 3: Implement** `Successors`:

```java
package trex.v2.egress.firefly;

import trex.v2.egress.hub.HubClient.HubUnit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Where an orphaned unit went (V2-SPEC.md §11.1). Since §4 hashes a transfer over its legs' current
 * ids, a re-parse re-mints the unit id: without this, Firefly gets an orphan and a create — a double
 * count until a human removes the orphan. Same kind: the group is re-keyed in place. Across kinds (a
 * pair formed or broke): reported beside its successors, never rewritten (D5).
 */
final class Successors {

    record Match(String orphanId, List<String> successorIds, boolean sameKind) {}

    private Successors() {}

    static Match of(String orphanId, List<String> orphanLegs, Map<String, String> resolved, List<HubUnit> units) {
        if (orphanId.startsWith("TRF-")) {
            Set<String> legs = Set.copyOf(orphanLegs.stream().map(l -> resolved.getOrDefault(l, l)).toList());
            if (legs.isEmpty()) {
                return new Match(orphanId, List.of(), false);
            }
            for (HubUnit u : units) {
                if (u.unitKind().equals("TRANSFER") && Set.copyOf(u.legs()).equals(legs)) {
                    return new Match(orphanId, List.of(u.unitId()), true);
                }
            }
            List<String> out = new ArrayList<>();
            for (HubUnit u : units) {
                if (u.unitKind().equals("EXTERNAL") && legs.contains(u.unitId())) {
                    out.add(u.unitId());
                }
            }
            out.sort(null);
            return new Match(orphanId, out, false);
        }
        String current = resolved.getOrDefault(orphanId, orphanId);
        for (HubUnit u : units) {
            if (u.unitKind().equals("EXTERNAL") && u.unitId().equals(current) && !current.equals(orphanId)) {
                return new Match(orphanId, List.of(current), true);
            }
            if (u.unitKind().equals("TRANSFER") && u.legs().contains(current)) {
                return new Match(orphanId, List.of(u.unitId()), false);
            }
        }
        return new Match(orphanId, List.of(), false);
    }
}
```

In `FireflyEgress.run()`, after `creates` and `orphans` are computed and before printing, match
them. A TRANSFER orphan's legs come from its group's notes (one GET per transfer orphan; orphans
are few):

```java
        Map<String, HubUnit> createById = new TreeMap<>();
        creates.forEach(u -> createById.put(u.unitId(), u));
        List<Map.Entry<ProjectionState, HubUnit>> rekeys = new ArrayList<>();
        Map<String, String> replacedBy = new TreeMap<>();
        Map<String, String> foreign = new TreeMap<>();
        List<ProjectionState> gone = new ArrayList<>();
        Map<String, String> resolved = snapshot.resolved() == null ? Map.of() : snapshot.resolved();
        for (ProjectionState orphan : List.copyOf(orphans)) {
            // groupOrNull, never group(): an orphan whose group you deleted in Firefly is the normal
            // de-projection case, and a 404 here must not abort a plan (review V4, D9).
            JsonNode group = firefly.groupOrNull(orphan.groupId());
            if (group == null) {
                gone.add(orphan);             // nothing to re-key, report or delete: it is gone
                continue;
            }
            JsonNode first = group.path("data").path("attributes").path("transactions").path(0);
            List<String> legs = orphan.unitId().startsWith("TRF-")
                ? FireflyClient.legs(first.path("notes").asText(null)) : List.of();
            Successors.Match m = Successors.of(orphan.unitId(), legs, resolved, snapshot.units());
            boolean canRekey = m.sameKind() && createById.containsKey(m.successorIds().getFirst());
            if (canRekey && !FireflyClient.isOurs(first)) {
                // Not ours any more (D4): never re-key into it. The successor stays a create, so the
                // new unit still lands; the untagged group is left as yours (review V5).
                foreign.put(orphan.unitId(), m.successorIds().getFirst());
            } else if (canRekey) {
                rekeys.add(Map.entry(orphan, createById.remove(m.successorIds().getFirst())));
            } else if (!m.successorIds().isEmpty()) {
                replacedBy.put(orphan.unitId(), String.join(", ", m.successorIds()));
            }
        }
        Set<String> goneIds = new TreeSet<>();
        gone.forEach(g -> goneIds.add(g.unitId()));
        Set<String> rekeyed = new TreeSet<>();
        rekeys.forEach(e -> rekeyed.add(e.getKey().unitId()));
        orphans = orphans.stream()
            .filter(o -> !rekeyed.contains(o.unitId()) && !goneIds.contains(o.unitId())).toList();
        creates = snapshot.units().stream().filter(u -> createById.containsKey(u.unitId())).toList();
```

(`orphans` and `creates` become non-final locals; `creates` is rebuilt in snapshot order, so the
apply loop keeps posting in date order.)

Plan output: `"  REKEY  " + orphan + " -> " + successor` for each re-key;
`"  GONE   " + id + " (group " + groupId + ") — already deleted in Firefly; dropped from the state on apply"`
for each `gone`; `"  FOREIGN " + id + " (group " + groupId + ") — the trex tag is gone, so it is yours now; "
+ successor + " is created instead"` for each `foreign`; and the orphan line gains
`replacedBy.containsKey(id) ? " — replaced by " + replacedBy.get(id) + "; a double count until --remove-orphans" : ""`.
`gone` and `foreign` orphans are not counted as orphans (nothing for `--remove-orphans` to do: one is
gone, the other is not ours). On apply, both leave the accelerator with the re-keyed ones:

```java
        if (rekeyed > 0 || !gone.isEmpty() || !foreign.isEmpty()) {
            gone.forEach(g -> known.remove(g.unitId()));
            foreign.keySet().forEach(known::remove);
            hub.record(true, new ArrayList<>(known.values()));
        }
```

(this replaces the `if (rekeyed > 0)` block below.) `FireflyClient.groupOrNull` is the one Stage 4
added for D9.

Apply, before the creates (so a re-keyed id never posts):

```java
        int rekeyed = 0;
        for (Map.Entry<ProjectionState, HubUnit> e : rekeys) {
            progress.tick();
            // converge writes the new external_id with the content: same group, new identity.
            Step step = converge(e.getValue(), e.getKey().groupId(), revision, known, true);
            if (step != Step.NOT_OURS) {
                known.remove(e.getKey().unitId());
                rekeyed++;
            }
        }
        // the accelerator update for re-keyed, gone and foreign orphans: see the block above
```

Give `converge` a `boolean rekey` parameter (the existing calls pass `false`) and make it force the
content write on a re-key, since `external_id` is not part of the fingerprint:

```java
        boolean contentMoves = single && (rekey
            || !expected.equals(Content.observed(splits.get(0), accounts.ids())));
```

A re-key of a hand-split group cannot rewrite content: `contentMoves` is false for it, so the
identity move needs its own branch — after the split loop, before the `if (!changed)` early return —
and it must force the write, because nothing else may have changed:

```java
        if (rekey && !single) {
            for (Map<String, Object> map : splitsOut) {
                map.put("external_id", unit.unitId());
            }
            changed = true;                // the identity move must reach Firefly
        }
```

The fingerprint stays `HAND_SPLIT`, and Task 5.2's notes refresh carries the new `legs=`.

Count `rekeyed` into `Outcome.rekeys`; size the `Progress` with the re-keys included.

- [ ] **Step 4: Run all tests**

Run: `mvn -q test`
Expected: PASS.

- [ ] **Step 5: Update §11.1 (remove the not-yet-built sentence entirely); commit; open the PR (Tasks 5.1–5.3)**

```bash
git add trex-v2-egress trex-v2-hub V2-SPEC.md
git commit -m "fix(egress): a superseded unit re-keys its group; a replacement is reported as a pair"
```

---

## 9. Stage 6 — operations and archive

### Task 6.1: Notes for running it, and the archive

**Files:**
- Modify: `docs/DEPLOYMENTS.md` (the Firefly section near line 207)
- Modify: `docs/V2-PARITY.md` (the §11.5 delta: recreate only through the explicit recovery)
- Modify: `CHANGELOG.md`
- Move: `V2-FIREFLY-EGRESS-PLAN.md` → `docs/plans/V2-FIREFLY-EGRESS-PLAN.md` (status: built, PRs named)

- [ ] **Step 1: Add to `docs/DEPLOYMENTS.md`**, under the existing first-apply note:
  - the first run after Stage 4 checks every row once (`CHECK` lines; GETs, no writes where Firefly
    already matches);
  - after mapping the clearing accounts, run `--create-missing-accounts --plan` once, then `--apply`;
  - a rule change re-plans the units whose category changed (a GET + PUT each) — expected, not a
    fault; unchanged rows keep their old `rules=` notes value, which `--validate` deliberately ignores;
  - `--remove-orphans` is only for orphans the plan does **not** show as re-keyed; read the
    "replaced by" lines first;
  - **Firefly is rebuilt, not migrated (D8):** the kept instance is refed from empty with the final
    build; no partial-stage apply. If an instance ever carries pre-Stage-5 groups, refeed it;
  - **what a refeed costs (review V7):** everything done on the Firefly side — hand-assigned budgets,
    piggy-bank links, your tags, hand-splits — is gone after a refeed. Assign budgets with a Firefly
    rule group ("budget from category") and re-run it from "apply rule group to transactions" after
    a refeed and after each sync; never assign budgets by hand;
  - **a missing group stops the pass (D9):** one transaction deleted in Firefly blocks every later
    delta until you run `--verify` then `--apply` (or retire the unit in trex). That is the intended
    forcing function; `--validate` on a timer is how you hear about it first;
  - **the Do/Don't contract** with its per-violation remedies (the `--validate` classes), and
    schedule `--validate` (read-only) as the detector — not `--verify`, whose rebuild erases the
    "was known" signal.
- [ ] **Step 2: Add the CHANGELOG entry** naming F1–F11 in one line each.
- [ ] **Step 3: Move the plan to `docs/plans/`** with its status line set to built and the PRs listed.
- [ ] **Step 4: Commit; open the PR**

```bash
git checkout -b docs/firefly-egress-archive
git add docs CHANGELOG.md V2-FIREFLY-EGRESS-PLAN.md
git commit -m "docs: Firefly egress convergence — running notes; archive the plan"
```

---

## 10. Out of scope

- Budgets in Firefly (decided 2026-10-09: Firefly's; the egress never sets `budget_id`).
- Commitments in Firefly (decided 2026-10-09: not projected; the two views cross-check).
- Speed (decided 2026-10-09: a full re-projection is rare; daily runs are deltas).
- Re-keying across kinds (D5) and automatic orphan removal (never).
- A blast-radius count of Firefly re-tags in the Rules preview (noted, not planned).
