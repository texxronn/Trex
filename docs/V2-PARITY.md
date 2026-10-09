# V2 ↔ V1 parity

**Why this exists.** `docs/plans/V2-IMPLEMENTATION-PLAN.md` §1.1 says v1 is *the executable record of behaviour
the proposal describes but does not spell out*: read the v1 implementation, restate the rule, then
implement it. The proposal's prose is sometimes looser than the code it was written from, so where
the two disagree the default is **v1 is the contract**; a divergence is a bug until it is a stated
choice. This file is that ledger: one row per reimplemented rule, its v1 source, and either the test
that pins equivalence or the deliberate divergence and its home in the proposal.

A v2 module must not import a v1 class, so v1's behaviour is pinned by **restated golden cases**
(its own tests and canonical strings), not by calling v1. When the real journal is available, the P0
fixture comparison is the end-to-end check behind all of this.

| Rule | v1 source | v2 | Verdict |
|---|---|---|---|
| Identity: `nk\|account\|date\|receipt`, `ch\|account\|date\|amount\|raw\|occ`, SHA-256 first 16 hex | `trex.core.Ids` | `trex.v2.core.Ids` | Equivalent — `V1ParityTest.identityMatchesV1sCanonicalStrings` (golden hex) |
| Transfer id: `TRF-<receipt>`, else order-independent hash of the two ids | `trex.core.Ids.transferId` | `Ids.transferId` | Equivalent — `V1ParityTest.transferIdMatchesV1sRules` |
| `occ`: identical-content rows get `0,1,2` in batch order; receipt rows `0`; distinct rows each `0` | `trex.core.Occurrence` | `Sequencer.assignOcc`, `Reparse.mint` | Equivalent — `SequencerTest.distinctContentRowsOnADayEachGetOccZeroLikeV1`, `V1ImporterTest.distinctContentRowsOnADayKeepTheirV1Ids` |
| Merchant stem (tail cut, padded column, upper-case) | `trex.category.Merchant` (+ `MerchantTest`) | `trex.v2.core.MerchantStem.stem` | Equivalent — `V1ParityTest.merchantStemMatchesV1` (v1's own cases) |
| Description cleaning (strip, collapse whitespace) | `trex.sequencer.ingest.DescriptionCleaner` | `Clean.clean` | Equivalent — `V1ParityTest.cleanMatchesV1` |
| `ATTESTATION` = declared account ∧ amount 0 | `trex.sequencer.ingest.Sequencer.typeHintFor` | `Derive` (units/opening/reconciliation) | Equivalent — `HubUnitsTest` (never a unit), `ReconciliationTest`, `OpeningTest` |
| Category precedence: structural, pin, first rule, `UNCATEGORIZED` | `trex.category.RuleCategorizer` | `Derive` P9 | Equivalent; pins are `PIN` decisions instead of `pins.yaml` (`DeriveTest.decisionsWinOverRules`) |
| Reconciliation, including `DECLARED` | `trex.core.state.Reconciliation` | `trex.v2.core.derive.Reconciliation` | Equivalent — `ReconciliationTest` |
| bw-csv debit sign inferred per file; mixed rejected | `trex.ingest.bw.BwCsv` | `trex.v2.ingest.bw.BwCsv` | Equivalent — `BwCsvTest` |
| Firefly type from the two account kinds (the 21-of-30 matrix) | `trex.egress.firefly.Projection.transferType` | `Projection.transferType` | Equivalent — `ProjectionTest.theTypeMatrixFollowsTheAccounts` |
| Firefly amounts: cents to fixed decimals, sign separate | `Projection.amount`/`signedAmount` | `Projection.amount`/`signedAmount` | Equivalent — `ProjectionFormatParityTest` |
| Firefly convergence: read-modify-write, CAS on the tag, clear a stale `category_id`; a Firefly-side edit is classified by `--validate`, and recreate is only the explicit `--verify` then `--apply` recovery (D9) | `trex.egress.firefly.FireflyEgress.retag` | `FireflyEgress.converge` | Equivalent, with a deliberate divergence — `FireflyEgressTest` (hand edit preserved; split/title survive; stale id cleared); no automatic recreate (`Validate`, D9) |
| Byte mirror: append + SHA-256 verify | `trex.egress.archive.ArchiveFollower` | `trex.v2.egress.archive.ArchiveMirror` | Equivalent — `ArchiveMirrorTest` |
| Transfer matcher: pool pairs on amount/sign/account/currency/date | `trex.sequencer.ingest.Matcher` | `Derive` P7 (§9.9.C.3) | Equivalent, with two deliberate guards — see below |

## Deliberate divergences

### Transfer matcher: the shape pre-filter and mutual-unique ties

v1's matcher paired two shaped legs on **amount, sign, different account, currency and date window
alone**. v2 (`V2-PROPOSAL.md` §9.9.C) keeps that pool but bolts on two guards that make it safe
rather than lucky, and retires the interim stem tier (an earlier v2 draft required an equal
`transferStem` at T2/T3; that is gone — `MerchantStem.transferStem` leaves the matching path):

- **Shape is the pre-filter.** Only a leg whose account's ordered `transferPatterns` first-match says
  `shape: true`, or which shares a receipt with a *plausible counterpart*, enters the pool. Ordinary
  rows never reach amount/date comparison, whatever the coincidence.
- **A tie is never picked.** At a tier the counterpart must be unique **both ways** — this leg has one
  candidate and that candidate has no other suitor. More than one candidate opens
  `AMBIGUOUS_TRANSFER` naming every one, and emits no pair.
- **Receipts are guarded too.** T1 (a shared non-null receipt) also requires opposite sign, equal
  `|amount|`, same currency and dates within `windowDays`, because receipt numbers recur across
  accounts and years (a measured 1,498-day payroll/card collision). Every genuine receipt pair is
  same-day and equal-amount, so the guard costs nothing.

Pinned by `DeriveTest.theMatcherNeverComparesTextAcrossAccounts`,
`DeriveTest.automaticMatcherPairsSameDayOppositeAmounts` and the private-fixture acceptance (the
PayID rows, the $600 BankWest↔BPAY case, the receipt collisions). A migrated journal is unaffected:
v1's pairs arrive as `PAIR` decisions, which win over the matcher.

### Identity: a receipt shared by different rows on one day

v1 (and v2 until 2026-10-09) keyed every receipt row by `nk|account|date|receipt`. ING prints one
receipt on several different rows of a day — a purchase, its international fee and the fee rebate;
a loan's fee line and the transfer that settles it — so those rows minted one id, the later rows
landed as `Flagged` re-observations, and derive kept only the newest. On the private fixture: 58
ids, **112 transactions hidden** (54 three-row groups on `ing-credit-card`, 4 two-row groups on
`ing-variable-rate`). v2 now mints such rows like receipt-less rows (`Ids.mint`); unique receipts
keep their natural key, so no existing id moves, and history is repaired by `ingest --reparse
--apply` (`SUPERSEDE` from the old id to the row matching its latest observation; the rest
`Appended`).

Pinned by `IdsMintTest`, `SequencerTest.aReceiptSharedOnOneDayByDifferentRowsMintsDistinctIds`,
`SequencerTest.aUniqueReceiptKeepsItsNaturalKey`, `SequencerTest.reIngestOfACollidedDayIsAllDuplicate`,
`ReparseTest.aCollidedReceiptIdIsSupersededByItsMatchingRowAndTheRestAreNew` and the private-fixture
E2E (current facts 6,029 → 6,141; review counts unchanged once the loan's settling `Transfer` line
joins its fee line as a `noop` reference in `profiles.yaml`).

## v2 additions with no v1 counterpart

These are new and deterministic, not restatements; they affect matching and review but never
identity, so tuning them is a reflow.

- Per-account ordered `transferPatterns` and the derived **rail** (`OSKO`/`PAYID`/`BPAY`/
  `BANK_TRANSFER` method, direction from the sign) — v1 had a flat allowlist and no rail; a
  `shape: false` pattern is rail-only (§9.9.C.2, §9.9.C.4).
- `MerchantStem.tokens` / `MerchantStem.similar` and the payment-noise word list — the RESTATEMENT
  text comparison (§8.4); v1 had no equivalent.
- Pending settlement, `SETTLE`, `AMBIGUOUS_SETTLEMENT`, `STALE_PENDING` — v1 skipped pending rows
  entirely (`BwCsv` → `SkippedRow`), so there is no precedent; the sign is the same-sign reading of
  §12.3 (the adapters normalise the export's convention per file).
- The uniform envelope, namespaced kinds and `trex.ingest` events, and `trex runner` (its Jobs view,
  staging inbox and journal snapshots) are post-proposal additions with no v1 counterpart; the
  envelope is a MAJOR line-format change (`V2-SPEC.md` §16).
