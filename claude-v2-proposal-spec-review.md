# V2-PROPOSAL.md + V2-SPEC.md review — correctness gaps and drift

**Reviewed:** `V2-PROPOSAL.md` (2,585 lines) and `V2-SPEC.md` (312 lines), as of 2026-09-30,
spot-checked against the `trex-v2-*` code at `3bd1df5`.
**Out of scope:** authentication and security (deliberately left out of v2).
**Lens:** the proposal promises that derivation never guesses and never hides a conflict
(§9.9.C "never a guess", §19 "no silent divergence between two sources"). Anything that
breaks that promise is ranked highest.

Severity: **H** = a derivation or write-path result that is silently wrong; **M** = a
contract gap, a latent bug, or the spec and code disagreeing; **L** = editorial or
under-specified.

---

## Verdict

The architecture is sound. Facts and decisions are split cleanly, `derive()` is pure, undo is
always an appended decision, and the writer really is small. Three places break the core
promise: the matcher and the dedup path can guess or hide a conflict without saying so. The
rest is mostly drift. The proposal is declared authoritative, but the code has moved past it
in about ten recorded places, and the proposal still carries the old text.

---

## High

### H1. A second observation of the same id never raises a review item

- **Promise.** §6.5's outcome table: an existing id with a new observation is `Flagged`, and
  a `POTENTIAL_DUP` (balance differs) or `RESTATEMENT` (same receipt, amount or text differs)
  item "is then derived".
- **Reality.** §9.9.F defines both predicates over two *different* current ids, and
  `Derive.java:757-792` implements exactly that. `txn_current` keeps the latest posted
  observation per chain, so the older one simply disappears from view.
- **Failure.** The bank re-issues a receipt-keyed row with a different amount. The sequencer
  answers `Flagged`, the new amount becomes current, and nothing reaches the review queue. The
  same happens to the balance-conflict case, which is the one §6.5 argues most carefully for.
- **Fix.** Add a predicate: a chain whose posted observations have more than one distinct
  dedup key opens `POTENTIAL_DUP` if only `balance` differs, and `RESTATEMENT` if `amount` or
  `rawDescription` differs. Put the predicate in §9.9.F and in the SPEC.

### H2. The matcher can pick a pair arbitrarily

- **Code.** `Derive.java:388-450` works through legs in `(date, n)` order. Each leg looks for
  candidates among the legs not yet paired, and pairs when it finds exactly one.
- **Failure.** On day d, account X has two −100 rows (f, then h) and account Y has one +100
  row. f is processed first, sees one candidate, and pairs with it. h is left `HELD` with no
  item until `UNMATCHED_LEG` fires 30 days later. Which X leg got paired is just an ordering
  accident, and nothing tells the user it was a coin toss.
- **Spec.** §9.9.B and §9.9.C ("emits at most one contra per leg"; "more than one candidate
  at the winning tier") allow this, because ambiguity is only checked from the side of the leg
  being processed.
- **Fix.** Pair only when the two legs are each other's only candidate at that tier. Otherwise
  both legs are `HELD` and one `AMBIGUOUS_TRANSFER` item names all of them. Update §9.9.B/C.

### H3. T1 (shared receipt) ignores amount and date, and `TRF-<receipt>` can collide

- **Spec.** §8.3 itself says "date in the key because receipts recur". But the T1 rule in
  §9.9.C checks only the receipt, the accounts, the sign and the currency: no equal
  magnitude, no date window. The SPEC (§6) says equal magnitude is always required, which the
  code doesn't do.
- **Code.** The T1 candidate filter (`Derive.java:397-402`) matches the proposal.
  `pairT1` (`Derive.java:490`) does `rows.put("TRF-" + receipt, …)`, so a second pair with
  the same receipt overwrites the first. Its legs stay `MATCHED`, the transfer row is gone,
  and neither leg nor the transfer is projected. The money silently leaves Firefly.
- **Fix.** T1 should require `|amount|` equal and dates within `windowDays`. The id should
  be `TRF-<receipt>` only when the receipt is unique among transfer-shaped legs, and
  otherwise the hashed `transferId(rootA, rootB)`. A duplicate `transfer_id` should be a
  derive error, never an overwrite.

---

## Medium

### M1. The sequencer reads its config once; the hub reloads live

- `SequencerService.java:60` builds the `Sequencer` with the registry and `RuleSet` it loaded
  at startup, and nothing reloads them. The hub reloads on file change.
- Failure: `PUT /api/config/categories` adds `PETS`. The hub's precheck passes, and the
  sequencer answers 400 "category 'PETS' is not declared" until it is restarted. New users and
  new accounts behave the same way (a new account's first ingest is rejected).
- There is also a doc conflict. The proposal (§9.3, §20) says names live in `refdata.yaml`
  and the sequencer validates against it. The SPEC (§12) says `categories.yaml` declares them
  and `refdata.yaml` is an optional override. §5.3 still lists `transfers.yaml` as sequencer
  config, which the writer has no use for.
- Fix: either the sequencer watches `users.yaml`, `accounts.yaml` and the category names,
  or the SPEC states "restart the sequencer after editing these", and the hub surfaces the
  400 as that instruction. Pick one home for category names.

### M2. The dedup key leaves out `observation`

- `SequencerState.java:33` builds the key from `externalId, accountRef, date, amount,
  rawDescription, receipt, occ, balance`, exactly as §6.5 lists it.
- Failure: a source whose pending row and posted row have the same text, date, amount and
  balance (typical of a feed that reports balance 0 or none). The posted row is a
  `Duplicate`, nothing is appended, and the fact stays pending until it becomes
  `STALE_PENDING`.
- Latent today (the CSV and PDF adapters differ in text or balance), but it will bite with
  the first feed. Add `observation` to the key and to §6.5.

### M3. `asOf` leaks into review verdicts

- §9.1 and §15.17 guarantee that a later `asOf` moves "only stale/age statuses", and "every
  review verdict is identical".
- Whether an `UNMATCHED_LEG` item exists at all depends on `asOf` (`Derive.java:794-803`).
- Fix: either say that `UNMATCHED_LEG` is time-relative and excluded from the guarantee, or
  make it an age badge on the `HELD` row rather than a review item.

### M4. §9.9.D (pending) contradicts itself and the code

| Point | §9.9.D says | Code does (`Derive.java:660-740`) |
|---|---|---|
| Sign of the settling row | opposite | same (`oppositeSign(...)` rejects) |
| Text match | `merchantStem` equal | `MerchantStem.similar(…, restatementOverlap)` |
| OPEN vs STALE | a mix of `asOf` and the frontier | frontier only; `asOf` unused |

- The prose also leaves one case undefined: zero candidates, `asOf` past the window, and the
  frontier not past it.
- The SPEC's §17 records "the sign convention" as a delta, but the proposal text was never
  corrected. Write the code's rule back into §9.9.D and §12.3.

### M5. Feed cursors (`source_cursor`) exist only in SQLite

- `HubService.putCursors` writes to the index only. §7.5's rule is that anything you would
  miss after deleting the index belongs in the log or a file. `rm index/` loses every cursor.
- Not live yet (there is no feed adapter). Fix before the first feed: derive the cursor from
  the last `trex.ingest` event or the evidence, store it in a file, or make it a log line.

### M6. §11.6 resumes egress from a high-water `n`, which is wrong under reflow

- The proposal resumes from "the maximum `n` read back from Firefly's notes" and widens the
  pass only when `configRevision` moves.
- Decisions don't move `configRevision`. An `UNPAIR` or `PIN` on an old row changes its units
  at or below the high-water `n`, and a resumed pass would never see them.
- The code avoids this: `FireflyEgress.java:67` always calls `hub.units(0)`. Delete the
  high-water text from §11.6, or define the watermark over decision `n` as well.

### M7. The duplicate/restatement check compares every current fact with every other

- `Derive.java:762` compares every current fact with every other. The list is already
  sorted by `(account, date)`, but the inner loop never stops early.
- At 10⁵ rows that is about 5×10⁹ comparisons per derive. §9.9.H claims `O(k log k)`.
- Fix: group by `(account, date)` and compare only inside a group.

---

## Document structure

### D1. The two documents disagree about which one is in charge

`AGENTS.md` and `V2-SPEC.md` both say "the proposal wins", yet the code deliberately differs
from it in about ten places (SPEC §17, `docs/V2-PARITY.md`), and the proposal still carries
the old text:

- **Migration.** §15.5, §16, and the migration paragraph after the §6.7 examples describe
  a migration that the SPEC retires.
- **Roadmap.** §17 (P0–P5) and §18 (the minimum-change path) describe a plan that has been
  built differently.
- **Wrong cross-reference.** §6 and §17 say "transformed (§12.6)", but §12.6 is the
  ingest-events section.
- **Category names.** `refdata.yaml` vs `categories.yaml` (see M1).
- **Stale config list.** §5.3 lists `transfers.yaml` and `refdata.yaml` as sequencer
  config.
- **UI modes.** §10.1 has four modes; the SPEC has five (Jobs).
- **Leftover wording.** §9.5 says "the period is treated as stale", from the removed
  per-period ack model.
- **Stale values.** The §6.7 `USER_ACK` example uses `derive/2`; the code is `derive/3`.
- **Pending sign and similarity.** See M4.
- **`occ`.** §6.1 describes cross-batch claiming; the code and SPEC §4 use v1's
  within-batch rule.

Recommendation: give the two documents one owner. Either write the SPEC's §17 deltas back into
the proposal, or freeze the proposal as history and make `V2-SPEC.md` authoritative, which
needs an `AGENTS.md` edit. The current split means a reader must merge three files to learn
one rule.

### D2. The SPEC leaves out the contracts it most needs

`V2-SPEC.md` is short enough to be the everyday reference, but it doesn't state:

- the dedup key and the per-row outcome table (§6.5);
- the review-item predicates (§9.9.F);
- the `stateHash` inputs (§9.9.G);
- a consistent envelope rule. The proposal's `[A-Za-z0-9_]{1,8}` can't produce `target` =
  eight spaces; the SPEC's `[A-Za-z0-9_ ]`, exactly 8 characters, can. Use the SPEC's form
  in both.

---

## Low

- **L1.** §7.1 says `projection_state` can be rebuilt from "level 1 + config + `asOf`";
  §7.5 and §11.6 say its home is Firefly. The second is right.
- **L2.** Derive stages P2, P4 and P5 depend on each other in a circle. P2 computes chain
  roots from *all* `SUPERSEDE`s, including revoked ones; P4 resolves ids through the map
  that P5 builds from P4's output. Document how the code breaks the loop, and whether a
  revoked `SUPERSEDE` still moves a transfer's root id.
- **L3.** The envelope's `atMs` comes from the client's `ingestedAt` when it is supplied
  (`Sequencer.java:~180`), so `atMs` is not monotonic in `n`. State that nothing may order
  by `atMs`; `DISMISS` already correctly compares `n`.
- **L4.** The examples break the proposal's own rules. `c3d41f7a9b2e4061` is the pending
  fact (#2), yet it is a `PAIR` leg (#5) and a `PIN` target (#16); a pending row never enters
  the pool (§9.7). The `RETIRE` example (#11) has actor `system` with a person's reason
  ("manual entry was a double-up").
- **L5.** A replaced journal is detected only when it shrinks (`IndexRefresher.java:143`).
  A journal of the same or larger size materialised from a different source is not noticed.
  Store the `n` and a hash of the last line at the offset, and compare both.
- **L6.** In the §6.8 lifecycle diagram, `external` goes back to the pool only via `REVOKE`,
  but §6.2 and §9.8 say a later `PAIR` naming the leg also moves it (the latest decision
  wins).
- **L7.** A `DISMISS` matches the cluster's subject, which is its smallest id. Naming
  another member has no effect, and a change in cluster membership changes the subject and
  re-opens the item. Neither is written down; the UI has to know to send the subject.
- **L8.** §9.9.D, §12.3 and the settlement code borrow `transfers.yaml` settings
  (`amountTolerance`, `restatementOverlap`) for pending settlement. Moving them under their
  own key would keep a transfer retune from moving settlements.

---

## Suggested order

1. H1, H2, H3: each needs a derive fix plus a regression test named after the failure.
2. D1: pick one owner, then fold M4 and the rest of D1 into it in one pass.
3. M1 and M2, before the first new category or user edit and the first feed, respectively.
4. The rest as they are touched.
