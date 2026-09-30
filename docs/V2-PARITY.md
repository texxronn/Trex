# V2 ↔ V1 parity

**Why this exists.** `V2-IMPLEMENTATION-PLAN.md` §1.1 says v1 is *the executable record of behaviour
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
| Firefly re-tag: read-modify-write, CAS on the tag, clear a stale `category_id` | `trex.egress.firefly.FireflyEgress.retag` | `FireflyEgress.retag` | Equivalent — `FireflyEgressTest` (hand edit preserved; split/title survive; stale id cleared) |
| Byte mirror: append + SHA-256 verify | `trex.egress.archive.ArchiveFollower` | `trex.v2.egress.archive.ArchiveMirror` | Equivalent — `ArchiveMirrorTest` |
| Transfer matcher | `trex.sequencer.ingest.Matcher` | `Derive` P7 | **Deliberate divergence** — see below |

## Deliberate divergences

### Transfer matcher requires an equal `transferStem`

v1's matcher paired two shaped legs on **amount, sign, different account, currency and date window
alone** — no merchant-text comparison. v2 (`V2-PROPOSAL.md` §9.9.C) additionally requires an equal
`transferStem` (the merchant stem with the transfer vocabulary and digit runs removed) at T2 and T3.

Consequence: v2 withholds some pairs v1 would have made, leaving both legs `HELD` for review rather
than pairing them on amount and date alone. It is a change we chose — the stem is what keeps a
same-amount coincidence from pairing two unrelated movements — and it is pinned by
`DeriveTest.theMatcherRequiresEqualTransferStemUnlikeV1`. A migrated journal is unaffected: v1's
pairs arrive as `PAIR` decisions, which win over the matcher.

## v2 additions with no v1 counterpart

These are new and deterministic, not restatements; they affect matching and review but never
identity, so tuning them is a reflow.

- `MerchantStem.transferStem` — the stem minus transfer vocabulary and digits, for T2/T3.
- `MerchantStem.tokens` / `MerchantStem.similar` and the payment-noise word list — the RESTATEMENT
  text comparison (§8.4); v1 had no equivalent.
- Pending settlement, `SETTLE`, `AMBIGUOUS_SETTLEMENT`, `STALE_PENDING` — v1 skipped pending rows
  entirely (`BwCsv` → `SkippedRow`), so there is no precedent; the sign is the same-sign reading of
  §12.3 (the adapters normalise the export's convention per file).
- The uniform envelope, namespaced kinds and `trex.ingest` events, and `trex runner` (its Jobs view,
  staging inbox and journal snapshots) are post-proposal additions with no v1 counterpart; the
  envelope is a MAJOR line-format change (`V2-SPEC.md` §16).
