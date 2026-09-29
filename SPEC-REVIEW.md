# SPEC.md review — improvements and gaps

**Reviewed:** `SPEC.md` (1,028 lines, as of 2026-09-29), cross-checked against
`DECISIONS.md`, `README.md`, the Maven reactor and the shipped code.
**Lens:** the SPEC is declared the single authority (`CLAUDE.md`), so anything where
the SPEC and the code disagree is a defect in one of them and must be resolved, not
left to the reader.

---

## Verdict

The SPEC is unusually strong on the things that are hard to get right: identity is
frozen and hashed from verbatim bytes, the journal is append-only and versioned with
one writer and one fsync per batch, categories are deliberately *not* journalled, and
every ambiguous transfer result parks for a human. Those decisions are argued, not
asserted, and the test list (§7) is the best part of the document.

Two problems hold it back:

1. **Amendment archaeology.** The document has been through at least ten recorded
   topology changes (`trex-grid` + `trex-resolver` → `trex-ws`, `trex-category` split
   three ways, eleven modules → six, a proxy layer added and then removed). Sections
   §5.4 and §5.7 were written on opposite sides of the last of those merges, so the
   SPEC now contradicts itself about how the web service is wired, what its flags are,
   and whether a relay exists. Some of these contradictions also disagree with the
   shipped code.
2. **Restated-in-prose facts that drift.** The category list, CLI flag lists, endpoint
   response shapes and module counts are written out longhand in more than one place.
   They have already diverged from each other and from the code.

Neither is an architecture problem. The fixes are mostly deletion, consolidation and
one-owning-section rules. The gaps (below) are smaller: data lifecycle, operations and
security are under-specified relative to the depth everywhere else.

A suggested severity legend: **H** = contract/correctness (SPEC and code disagree, or
SPEC disagrees with SPEC on a wire format); **M** = under-specified or ambiguous;
**L** = editorial/stale.

---

## 1. High-severity findings (contract drift — SPEC vs SPEC or SPEC vs code)

| # | Location | Issue | Evidence / recommendation |
|---|---|---|---|
| H1 | §1, line 51 | "**Why `trex-core` is gone and not merely renamed.**" — it was `trex-category` that was split and removed. As written it contradicts the module list 15 lines above, which contains `trex-core`. | The paragraph then describes `trex-category`'s evaluator/loading/writing split. Rename the subject to `trex-category`. |
| H2 | §5.6 line 596, §5.8 lines 691 & 703 | Firefly category tag is documented as `trex-core:<name>`. The code and its tests use `trex-category:` (`Projection.CATEGORY_TAG_PREFIX = "trex-category:"`; `RetagTest`, `ProjectionTest`, `RecoveryTest`). | The tag is read-modify-write evidence: the egress only overwrites `category_name` when it still equals the old tag value. A SPEC/code mismatch here means the "did a human edit this?" heuristic reads a tag that never exists. Decide the prefix once and state it in one place. |
| H3 | §6 line 871, §6 YAML example line 880, DECISIONS C9 | Three different "starting category sets": SPEC prose lists 8 (`…INTEREST_PAID…`), the SPEC YAML example lists 7 (no `INTEREST_PAID`), DECISIONS C9 lists a different one (`INTEREST`). Shipped `deploy/config/categories.yaml` declares **25**. | Stop restating the set. Say "the declared set is whatever `categories.yaml` declares; the shipped starting point lists N names" and point at the file. The reserved names (`TRANSFER`, `UNCATEGORIZED`) are the only ones the SPEC needs to fix. |
| H4 | §3.5 line 352 vs §7 tests 6 & 31 | `/reconcile` is documented as returning `{ accountRef, reconcilable, balances, opening, closing, sum }`. §7 test 6/31 require a third state `DECLARED` and a `gap`; the code returns `status` and `gap` (`ReconcileReport.Account`). | Make §3.5 the one canonical response schema and include `status ∈ {RECONCILED, BROKEN, DECLARED}` and `gap`; define `ok` there (true when no account is `BROKEN`) instead of only in the test list. |
| H5 | §2.1 Candidate, §3.3 step 2 & step 7a (lines 310–311) | **No documented discriminator for `ATTESTATION`.** Step 7a says `typeHint = WITHDRAWAL if amount < 0 else DEPOSIT`, which classifies a declared-account `$0` attestation as a `DEPOSIT`. The code derives it as `balanceSource == DECLARED && amount == 0` (`Sequencer.typeHintFor`). §2.1's `Candidate` record has no field for it. | State the rule explicitly in §3.3 (and note it in §2.3). Also acknowledge the tension: §2.3 says the distinction "has to be structural, not a magic value", yet the current discriminator *is* `amount == 0` combined with an account property. Either that is acceptable (say so) or the `Candidate` contract needs a real `kind`/`typeHint` field. |
| H6 | §5.7 line 661 vs §5.4 line 554 vs code/README | `trex-ws` flag list is wrong. SPEC §5.7 gives `--port` (default 8085); SPEC §5.4 gives `--gateway-url` (8085) + `--port` (8090). Reality (`trex-ws/Main`, README): `--journal`, `--sequencer-url`, `--config`, `--admin-port` 8085, `--port` 8090, `--bind` (read listener only), `--poll-ms`. `--admin-port` is **absent from the SPEC entirely**, and `--gateway-url` describes the superseded two-process split. | One flag table for `trex-ws`, matching the two-listener design. |
| H7 | §5.4 line 534 vs §5.7 line 513 | §5.4: "At most 32 concurrent browser clients …, **fanned out from one upstream connection**." §5.7: SSE is "served **directly from the fold** — **no relay, no upstream connection to fan out**." | §5.4's Refresh paragraph is a pre-merge survivor. Delete "upstream connection"/relay language there and point at §5.7 as the single definition. |
| H8 | §5.4 line 543 vs §5.7 line 616, code | §5.4 defines `GET /api/state`; §5.7 defines `GET /api/ledger` "the same answer as `/api/ledger`". The code has only `/api/ledger` (`LedgerRoutes`); `/api/state` appears nowhere. | Delete the `/api/state` alias and the "same answer as" note; `/api/ledger` is canonical. |
| H9 | §5.6 line 596 "(phase 1.5, not built here)" and §9 line 1026 "the Firefly egress follower" | Both Firefly and hledger are **built**: they are `trex-egress` subcommands (`Main.subcommands`), covered by tests 23–27 and generation-order steps 24–25. §9 lists one of them as out of scope. | Update the scope statement (either "built" or "phase 1.5, built" — but pick one and make §9 agree). This matters because the scope line is what a reader trusts. |
| H10 | §5.4 line 529, §5.7 line 614 vs code steps | `/api/snapshot` parameter lists omit `sinceN`. It is implemented (`GridQuery`), documented only in generation order step 24, and used by the Firefly egress to page incrementally (`GatewayClient.since`). | Add `sinceN` to the canonical `/api/snapshot` parameter list with its semantics (`n > sinceN` on the latest line, for consumers that have already seen a prefix). |
| H11 | §1 line 71 | "picocli parses the **six `Main` CLIs**". The reactor has **four** top-level Mains (sequencer, ingest, ws, egress) and four egress *subcommands*; `@Command` appears 8 times. | Say "the four runnable CLIs" or "every CLI (with one subcommand per egress target)". |

---

## 2. Internal inconsistencies that are not code disagreements (H/M)

- **§7 preamble, line 908:** "Required, mapping to spec **§16** assertions". There is no
  §16; the tests are §7. Stale cross-reference. (A symptom of not having stable
  requirement IDs — see §5 below.)
- **§5.7 line 647:** "May bind another interface (**§9**)", but §9 explicitly says the
  second-listener option is **no longer deferred**. Fix the cross-reference.
- **§5.8 line 688:** "trex-core, the journal, **trex-core** and the gateway never learn
  the word." Duplicated `trex-core`; "the gateway" is now `trex-ws`. Stale module names.
- **§5.4 line 554 vs §5.7 line 661:** both claim to be `trex-ws`'s flags, with different
  defaults and different flag sets (H6). §5.4 also still calls the read side "the page
  server" as if it were separate.
- **§5.6 line 566 vs §1 line 36:** §5.6 says the evaluator "in trex-core", §1's module
  map agrees, but §8 step 11 still says "**trex-core** (§5.6): rule records, `Categorizer`…"
  as if it introduced the module. §8 is flagged historical, so this is tolerated — but
  §8 is where a reader goes to learn build order and it no longer names the current six.
- **§2.3 line 190:** "on a `declared` account it is the *only* line whose balance means
  anything" vs §3.3 step 2, where a `declared` purchase is written with `balance = 0`.
  Consistent in effect, but the "only line whose balance means anything" phrasing reads
  as if purchases carry a meaningful balance. Tighten wording.
- **§7 test 1 (line 909)** excludes `n`/`ingestedAt` from comparison, while tests 9, 10
  and 13 compare **full journal bytes** with a fixed `Clock`. Both are defensible; the
  SPEC should say which comparison a new test should use and why.

---

## 3. Under-specified and ambiguous (M)

1. **Single-writer enforcement (§0.2, §3.1).** The invariant is strong, but nothing
   prevents two sequencer processes from opening the same journal and both `O_APPEND`-
   writing. The code uses an in-process `ReentrantLock` only (`Sequencer.writeLock`);
   there is no `FileLock`. Compose gets a mount-level guarantee, systemd does not.
   Recommend an advisory exclusive `FileLock` on the journal at startup, documented as
   part of recovery, and note the constraint for network filesystems.
2. **Read-listener binding.** `--bind` applies to the read listener only; the admin
   listener is always loopback. The SPEC states the principle (§5.7) but not the flag
   mechanics, and the `--admin-port`/`--port` split is absent (H6). State that there are
   two ports and that only the read one is bind-configurable.
3. **`--config` contents.** §5.7 says `--config` holds `categories.yaml` and
   `pins.yaml`; the code also loads `accounts.yaml` from it (for `/api/accounts` and the
   `/api/cash` guard). Say so. Also state what happens when `--config` is omitted
   (code: every row reads `UNCATEGORIZED`, and `/api/cash` reports it cannot see
   accounts).
4. **Decision transition table.** §3.5 and §3.2 state allowed transitions in prose
   across two sections. A single table — action × current state → produced lines — would
   remove the need to cross-read, and would make the `DISMISS_DUP` "state unchanged, any
   state allowed" subtlety explicit.
5. **Batch and response size limits.** The only stated cap is 100 MB *decompressed*
   (§3.6). There is no documented maximum candidate count per `/candidates` call, no cap
   on `GET /held` / `GET /review` payloads, and no statement of what happens at the cap.
6. **`--batch-rows` semantics.** §4 calls it a "soft size target" and says a single day
   over the target is sent alone; the README flag table just lists it. Define once, in
   §4, and have the README point there.
7. **`external_id` length.** Truncation to 16 hex chars (64 bits) is frozen and correct,
   but a collision would surface as `DroppedDuplicate` — a silently lost transaction,
   which is exactly what §0.6 exists to prevent. State the accepted risk and the scale
   bound it assumes (birthday bound at the expected journal size), so the trade is
   explicit rather than accidental.
8. **Time and timezone.** `date` is a `LocalDate` taken verbatim from the source;
   `ingestedAt` is an `Instant` from an injected `Clock`. There is no statement about the
   server timezone used for date parsing, nor about date-filter semantics (`from`/`to`
   inclusive) across timezones. Add a short "time is source-local; the server clock is
   only for `ingestedAt`" note.
9. **`hledger.yaml` requirements.** `cashPlug` is described as needed per `declared`
   account; it is not stated whether a `declared` account missing `cashPlug` is a
   startup error, a warning, or a default. Given the file's strict-binding posture, say.
10. **Multiple writers to the rule files.** `rulesRevision` correctly rejects an API
    write composed against a stale revision (409). It does not cover a *manual* editor
    writing while trex-ws is mid-write. State that the 409 guards API-vs-API and
    API-vs-file races only, and that a hand editor is expected to be the single human
    writer.

---

## 4. Missing topics (gaps)

These are topics the SPEC does not treat at all, or treats only in passing. Given how
deep the SPEC goes elsewhere, each deserves either a short section or an explicit
"accepted, not addressed in phase 1".

**G1 — Journal lifecycle and growth.** One file, append-only, never rotated, never
compacted; every `trex-ws` start folds it from offset 0 (§5.7). Nothing bounds growth or
describes what happens at 10 years / 10 GB. Options to state: accept unbounded growth
with a measured ceiling, or define a materialize/snapshot procedure (the byte-copy
materialize in §3.2 already exists but is not framed as compaction). Also state whether
the archive mirror is a *backup* or an *audit copy* — currently it reads as both.

**G2 — Operations runbook.** Missing: a health/readiness endpoint (there is `/api/head`
and `/head`, but no stated liveness contract), metrics, disk-full and I/O-error
behaviour (does the sequencer reject the batch, stop, or half-write?), and a documented
backup/restore/verify procedure. `deploy/` covers installation well; the SPEC covers
runtime failure badly.

**G3 — Security posture beyond "no auth".** The loopback defaults, `X-Trex-Admin`, CSP
and Origin checks are documented well. Missing: filesystem permissions/umask for the
journal and config on a shared host (this is financial data), the secret-handling story
for `FIREFLY_TOKEN` (env/credential is stated, no rotation/leak guidance), TLS for the
read listener if it is ever bound off-loopback, and a one-paragraph threat model that
says what an attacker on the same host can do.

**G4 — Failure-modes table.** Scattered across §3.1, §3.2, §5.1 and DECISIONS F, but
never gathered. A table (crash before/after fsync; torn tail; unparseable complete line;
journal deleted/replaced mid-run; follower offset past EOF after a shrink — DECISIONS F3
explicitly leaves this **open**; clock skew; two sequencers) would be high value and
would surface the F3 gap in the SPEC rather than only in the decision log.

**G5 — Versioning and compatibility policy.** "Frozen" is used for identity, the JSONL
byte format and the PDF normalisation, but there is no policy for how a breaking change
is introduced (new journal version field? migration tool? dual-write?). Same for the
HTTP API and the config schema. At minimum, state that phase 1 has no migration story
because there is one deployment and one operator.

**G6 — Non-functional requirements.** The only numbers are anecdotal ("≈1 850 lines",
"milliseconds", "32 SSE clients", "64 KB body", "100 MB decompressed"). State the design
targets (max journal size, ingest throughput expectation, snapshot latency budget) so
the "current scale" caveats elsewhere can be checked.

**G7 — Currency list as a fixed enum.** `AUD|USD|INR` is hard-coded in several places
(§0.4, §6, config validation). Fine for now; say that adding a currency is a config +
code change, or make it purely config-driven.

**G8 — Test coverage of the new edge cases.** §7 is strong, but it has no test for the
`ATTESTATION` discriminator (H5), none for two sequencers / journal locking (M1), and
none for the `/reconcile` `DECLARED` shape as documented (H4 — test 31 implies it, but
the response schema is not pinned by a test).

---

## 5. Structural and editorial improvements

The SPEC fights its own length. Concrete, low-risk changes:

1. **One canonical API table.** §5.4 and §5.7 both enumerate `/api/...` endpoints and
   have already drifted (`/api/state` vs `/api/ledger`, missing `sinceN`). Move the whole
   surface into §5.7 as a single table — method, path, listener (`ADMIN`/`READ`), auth
   requirement, parameters, response — and have §5.4 only reference it. This is the
   highest-leverage edit for preventing future drift.
2. **Stable requirement IDs.** Give every normative requirement an anchor (e.g.
   `R-IDENT-1`, `R-JOURNAL-4`, `R-WS-9`). Tests and DECISIONS currently cite "SPEC §7
   test 6" and "§16", which is how "$16" survived. IDs make references checkable.
3. **Separate normative text from rationale.** Large stretches of §5.4–§5.9 are essays
   ("the reason the file's owner must be…"), which are excellent in `DECISIONS.md` and
   hard to scan in a build spec. Adopt a convention: normative statements use **MUST /
   SHOULD / MAY**; rationale goes indented, italicised, or to DECISIONS.
4. **A glossary.** `n`, `occ`, leg, resolved unit, projectable unit, mirror vs
   projection, pin, HELD/REVIEW/EXTERNAL, transfer-shaped, `statement`/`declared`,
   `rulesRevision`, `asOfN`. Several of these are defined only where first used, which is
   fine until a reader joins at §5.8.
5. **A field dictionary.** One table for every `Candidate` and `CanonicalEvent` field:
   type, nullable, in identity?, who writes it (adapter/sequencer/decision), derived or
   stored. §2.1/§2.2 carry this in comments, but the same fact is also restated in §3.4,
   §5.3 and §5.6.
6. **A config reference table.** File → owner → required keys → defaults → validation
   errors. §6 is prose; a table would make the "unknown key is a startup error" promise
   auditable.
7. **Fix the scope banner.** §1, §5.6 and §9 currently disagree about whether Firefly
   and hledger are in phase 1 (H9). Add a one-line status block at the top: what is
   built, what is spec'd-ahead, and what is deferred.
8. **A "documents that must agree" list.** SPEC, README, DECISIONS and the code each
   restate flags, categories and response shapes. Name the single owner for each fact
   (e.g. "flags: SPEC §5.7 owns them; README quotes them") so a change has one place to
   land.
9. **Drop or date the historical sections.** §8 is labelled historical but is also the
   build-order guide; §5.5 is a merge note. Consider moving the archaeology to
   `DECISIONS.md` and leaving §8 as the current six-module order.

---

## 6. Suggested shape of the fix

A minimal, ordered plan (highest value first, each independently reviewable):

1. **Reconcile H1–H11** — one pass where SPEC and code are compared for the specific
   facts called out above. Most are one-line edits or deletions.
2. **Add the ATTESTATION discriminator** (H5) and the `/reconcile` schema (H4) — these
   are the only two that touch a contract a consumer could get wrong.
3. **Collapse the API surface into one §5.7 table** (improvement 1) and delete the
   duplicated endpoint prose in §5.4 — this removes the class of bug H6–H8 belong to.
4. **Add the missing sections** in this order: failure-modes table (G4), scope/status
   banner (improvement 7), journal lifecycle (G1), versioning policy (G5).
5. **Add requirement IDs** (improvement 2) as a mechanical pass, then repoint the tests
   and DECISIONS at them.
6. **Move rationale out** (improvement 3) as the last, largest edit — do it once the
   facts above are stable, so the move does not carry stale text forward.

---

## 7. What to keep exactly as it is

Worth saying explicitly, because a review this long can read as dissatisfaction:

- §0's invariants, §2.4's frozen identity strings and §3.1/§3.2's append/fsync/recovery
  rules are precise, testable and correct.
- The "no category in the journal" decision (§0.7, §5.6) and the rule-file splice/
  validate-before-swap design are the strongest parts of the document.
- §7's test list is genuinely good, and the fact that each test names the invariant it
  guards is why the drift above is findable at all.
