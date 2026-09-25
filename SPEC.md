# trex — Transaction Sequencer: Java 25 Code Plan

A build spec, pitched for Claude Code generation. The current topology is §1; this is what each part is for:
1. **trex-sequencer** — the transaction sequencer service (the journal core, and its only writer)
2. **trex-ingress** — one CLI, one parser per source type → candidates → the sequencer
3. **Two sample egress followers** — `trex-egress-archive` and `trex-egress-sqlite`, log mirrors
4. **trex-gateway** — the consumer API: the journal folded and categorised, the rule files, the decisions gateway (§5.7)
5. **trex-web** — the pages over it: browse, categorise, and the HELD/REVIEW workflow (§5.4). Not a dashboard; dashboards belong to Firefly/Grafana
6. **trex-category** — the shared library that derives a category from a line (§5.6)

Design authority is `firefly-ingest-spec-v2.md` + the conversation's later decisions. This document is the *how to build it*; that one is the *why*. Where they conflict, the invariants in §0 win. Pre-implementation review decisions are folded into this document; `DECISIONS.md` records the rationale (B-numbers and T refer to it).

---

## 0. Non-negotiable invariants (every component honors these)

1. **`external_id` is the sole identity of a transaction.** Natural key (`accountRef|date|receipt`) ▸ else content hash `sha256(accountRef|date|amount|rawDescription|occ)[:16]` — hashes the **verbatim** `rawDescription`, never the cleaned `description`, so description cleanup stays soft and can evolve without shifting ids. **`balance` is never in identity *or* transaction semantics** — it is provenance/reconciliation data only (see §2.2 field doc and §5). An `external_id` may appear on several journal lines (versions, §3.2); it is not a line key.

   **What `balance` is.** An **observation from outside trex** about what the account held — never a figure trex derived. For a bank that observation is the statement's running balance, arriving on every row. For an account with no statement it is a person stating what they hold, arriving whenever they choose (§2.3 `ATTESTATION`). Both are evidence; neither is computed from the amounts trex already has, which is what makes asserting one downstream meaningful. **How often those observations arrive is declared per account** (`balanceSource`, §6), never guessed from the data, and an account whose observations are sparse has a chain with deliberate gaps rather than a broken one.
2. **The journal is append-only, single-writer, immutable.** No update, no delete. A state change is a new line (a re-appended version, §3.2); corrections are new events with `corrects`.
3. **`n` is the journal record sequence: a `long`, unique per journal line, strictly increasing, starting at 1.** Assigned when the line is appended, preserved forever (read back on replay). Never in identity. Every line — including a re-appended version of an existing transaction — takes the next `n`.
4. **Amounts are `long` cents (fixed ×100 — every account is AUD/USD/INR, all 2-decimal).** `BigDecimal` appears **only at the CSV parse boundary** to convert a decimal string to cents (scale-checked, never rounded); never `double`/`float`, never in the core.
5. **Projection to any sink is one-way and idempotent.** Re-delivery must be a no-op.
6. **Accuracy over recall.** Ambiguity → review, never a guess.
7. **There is no category in the journal.** A category is derived by each journal consumer from two inputs — the line, and `categories.yaml` — and is never stored on a `Candidate` or a `CanonicalEvent`, never evaluated by the sequencer, never part of identity. Even a human correction is a pin in the rules file, not a journal line (§5.6). Consequences, all intended: changing the rules recategorises all history at zero journal cost and leaves every byte untouched; "what did we call this in March?" is answered by the journal plus that commit of `categories.yaml`, which git already keeps; and downstream copies (a Firefly tag) are refreshed by re-projection, never by rewriting history. `trex-category` (§5.6) is the shared implementation so the readers agree — a consumer with different needs may derive its own.

---

## 1. Platform, build, dependencies

- **JDK 25 (LTS)**, Maven, with `maven-compiler-plugin` set to `<release>25</release>` (optionally the `maven-toolchains-plugin` to pin the JDK). Use records, sealed interfaces, pattern-matching `switch` with record patterns, Stream Gatherers, text blocks. Do **not** use preview features (structured concurrency, primitive patterns).
- **Multi-module Maven reactor:**
  ```
  trex/
    pom.xml           # parent (packaging=pom): modules, <release>25</release>, dependencyManagement
    trex-core/        # pure domain + pure journal-state fold: no HTTP, no DB, no I/O framework
      pom.xml
    trex-journal/     # shared journal read path: Json mapper config, framed JSONL reader, change signal
      pom.xml
    trex-sequencer/   # the service: journal writer + HTTP API + wiring
      pom.xml
    trex-ingress/     # ingress client: shared CLI/HTTP/batching + one package per source type
      pom.xml
    trex-egress-archive/
      pom.xml
    trex-egress-sqlite/
      pom.xml
    trex-egress-firefly/  # projects resolved units into Firefly III (§5.8)
      pom.xml
    trex-egress-hledger/  # regenerates a plain-text hledger journal (§5.9)
      pom.xml
    trex-gateway/     # the consumer API: journal fold + categories + rule writer + decisions gateway (§5.7)
      pom.xml
    trex-web/         # the web UI: pages, one proxy to trex-gateway, one SSE relay (§5.4)
      pom.xml
    trex-category/    # shared consumer library: category rules (categories.yaml) + evaluator (§5.6)
      pom.xml
  ```
  Parent pom pins JDK 25 (`<maven.compiler.release>25</maven.compiler.release>`), lists the eleven `<modules>`, and centralizes versions in `<dependencyManagement>`. Each service/adapter module is packaged as a runnable jar via `maven-shade-plugin` (or `maven-assembly-plugin`) with its `Main-Class`. Everything that reads the journal uses `trex-journal` (one framing/corruption rule set) and the fold in `trex-core` (one definition of current state, HELD and REVIEW).
- **Dependencies (minimal on purpose; coordinates are `groupId:artifactId`):**
  - `com.fasterxml.jackson.core:jackson-annotations` — in trex-core for `@JsonPropertyOrder`; no databind in core.
  - `com.fasterxml.jackson.core:jackson-databind` + `jackson-datatype-jsr310` (JSONL, records, java.time) in `trex-journal`, whose shared mapper the sequencer, ingress, followers and resolver use. Configure: `SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS` off; `WRITE_DATES_AS_TIMESTAMPS` off; **deterministic field order via an explicit `@JsonPropertyOrder` on `CanonicalEvent`** (matters for byte-stable journal lines, §3.1).
  - `org.apache.pdfbox:pdfbox` (+ `fontbox`, `pdfbox-io`, `commons-logging`; ~3.6 MB, Apache-2.0) in `trex-ingress` only — text extraction for `cba-pdf` (§4). It sits in the identity path, so the extraction and normalisation rules are frozen in §4 and pinned by a golden file; an upgrade is only safe if that test still passes.
  - `com.fasterxml.jackson.dataformat:jackson-dataformat-yaml` (+ its SnakeYAML) in `trex-journal` — all config is YAML (§6). Configure the config mapper with `FAIL_ON_UNKNOWN_PROPERTIES` **on** (an unknown key is a startup error, as before) and `STRICT_DUPLICATE_DETECTION` **on** (a duplicated key is a startup error, not last-wins). The journal stays JSONL — YAML never touches it.
  - `org.xerial:sqlite-jdbc` (egress-sqlite only).
  - `org.junit.jupiter:junit-jupiter` (tests).
  - `org.slf4j:slf4j-api` — logging facade, **every module including trex-core**. A binding (`org.slf4j:slf4j-simple`) is added at runtime scope by the six runnable modules only; libraries never bind. Pinned to 2.0.x, which also overrides the 1.7.x that `sqlite-jdbc` pulls in transitively.
    Logging must not change behaviour: no logging inside journal serialization (byte stability, §3.1), and the fold in trex-core stays deterministic — a log statement may observe, never decide. Journal lines are financial data: log `externalId`, `n`, counts and states, **never `rawDescription`, `description` or amounts**.
  - **Dependency policy:** a well-maintained library is preferred over hand-written code when it *removes* source — a standard format parser, argument parsing, and the like. Two standing exceptions, where the code stays hand-written and auditable:
    1. **The identity and journal path** — `Ids`, the JSONL framing in `JsonlJournal`, `FramedReader`. A library version bump there could shift the identity contract (§0.1).
    2. **The HTTP layer** — `com.sun.net.httpserver.HttpServer` stays (**confirmed choice; zero-dep, no Javalin**), so routing, SSE and gzip stay hand-written (§3.6).
    `info.picocli:picocli` parses the six `Main` CLIs (§4, §5). **`trex.ingress.Csv` stays hand-written**: it was compared against Apache Commons CSV and the library is more lenient in two places that matter — a bare `\r` between rows is taken as a record separator (silently splitting a row) and a stray quote inside an unquoted field is accepted as literal text. Since `rawDescription` is hashed into identity verbatim (§2.4), quietly reinterpreting a malformed file mints ids that differ from the same file once fixed, which is the duplicate the §4 whole-file validation exists to prevent. Rejecting the file and naming the line is the behaviour worth keeping.
  - **JDK-only for everything else:** `java.net.http.HttpClient` (adapters → trex), `java.util.zip.{GZIPInputStream,GZIPOutputStream}` (gzip — see §3.6), `java.math.BigDecimal` (parse boundary only), `java.time`, `java.nio.channels.FileChannel`, `java.security.MessageDigest`, `java.util.regex` (transfer allowlist §3.4, category rules §5.6).
  - **gzip note:** neither `HttpServer` nor `HttpClient` handles content-coding automatically — both directions are manual (§3.6, §4).

---

## 2. trex-core — domain types

Pure, deterministic, no I/O. This is where the Java-25 algebraic modelling lives.

### 2.1 Candidate (adapter → sequencer input; "would-be canonical")
```java
public record Candidate(
    String candidateRef,      // adapter-local id for correlating the response (e.g. "row-12")
    String accountRef,        // registry key; implies currency + firefly id
    LocalDate date,
    long amount,              // signed cents (×100); -ve = money out
    String rawDescription,    // verbatim
    long balance,             // observed balance (cents), §0.1. PROVENANCE/RECONCILIATION ONLY — never identity, never transaction semantics.
                              // Required on a `statement` account and on an ATTESTATION; otherwise 0 and meaningless (§6)
    String receipt,           // nullable; ING natural key
    String counterpartyBsb,   // nullable; CDR (phase 2)
    String counterpartyAcct,  // nullable; CDR (phase 2)
    String sourceType,        // kind of input it was read from, e.g. "ing-csv" (= --source-type, §4; copied onto the event)
    Provenance provenance     // BANK for statements; AUTHORED for portal decisions
) {}
```

### 2.2 CanonicalEvent (journal record — one JSONL line)
```java
@JsonPropertyOrder({ "n","externalId","accountRef","toAccountRef","currency","date","amount","balance",
    "description","rawDescription","typeHint","transferKey","legIds","corrects","state","confidence",
    "flags","provenance","sourceType","receipt","counterpartyBsb","counterpartyAcct",
    "foreignAmount","foreignCurrency","comment","ingestedAt" })
public record CanonicalEvent(
    long n,                   // journal line sequence (§0.3)
    String externalId,
    String accountRef,        // TRANSFER: the from (negative-amount) leg's account
    String toAccountRef,      // TRANSFER only: the to (positive-amount) leg's account; else null
    String currency,          // stamped from registry (account attribute; no conversion)
    LocalDate date,
    long amount,              // cents. Signed on transaction lines; TRANSFER: absolute value (direction = accountRef → toAccountRef)
    long balance,             // cents. PROVENANCE/RECONCILIATION ONLY — never identity, never transaction semantics.
                              // TRANSFER: 0. On a `declared` account: 0 except on an ATTESTATION line, where it is the figure stated
    String description,       // cleaned (§3.3)
    String rawDescription,
    TypeHint typeHint,
    String transferKey,       // nullable; TRANSFER line: its TRF- id; legs: set only if matched when that line was appended
    List<String> legIds,      // TRANSFER only: [fromLeg, toLeg] external_ids; else null
    String corrects,          // nullable; external_id this reverses/corrects
    EventState state,         // state of this version. Latest line (highest n) per externalId is AUTHORITATIVE (§3.2)
    Confidence confidence,    // nullable; set on TRANSFER lines
    List<Flag> flags,         // never null; empty = []
    Provenance provenance,
    String sourceType,        // e.g. "ing-csv"
    String receipt,           // nullable
    String counterpartyBsb,   // nullable
    String counterpartyAcct,  // nullable
    Long foreignAmount,       // nullable cents; superset for cross-currency (phase 2), null in phase 1
    String foreignCurrency,   // nullable; superset (phase 2)
    String comment,           // nullable; free text from a POST /decisions decision, else null. Never identity
                              // (no category field, by invariant §0.7 — it is derived, §5.6)
    Instant ingestedAt        // stamped from an injected java.time.Clock on first append; never restamped
) {}
```

### 2.3 Enums & sealed hierarchies (drive exhaustive `switch`)
```java
public enum Provenance { BANK, AUTHORED }
public enum TypeHint   { WITHDRAWAL, DEPOSIT, TRANSFER, ATTESTATION }
public enum BalanceSource { STATEMENT, DECLARED }   // where an account's observed balances come from (§6)
public enum Confidence { EXACT, HIGH, REVIEW }
public enum Flag       { POTENTIAL_DUP }
public enum EventState { HELD, MATCHED, REVIEW, EXTERNAL }

// Identity tier — sealed so the id function is exhaustive & centralized
public sealed interface IdentityStrategy permits NaturalKey, ContentHash {}
public record NaturalKey(String accountRef, LocalDate date, String receipt) implements IdentityStrategy {}
public record ContentHash(String accountRef, LocalDate date, long amount,
                          String rawDescription, int occ) implements IdentityStrategy {}

// Per-candidate / per-decision outcome returned in the batch response
public sealed interface CandidateResult
    permits Resolved, DroppedDuplicate, Held, Flagged, Rejected {}
public record Resolved(String candidateRef, String externalId, long n) implements CandidateResult {}
public record DroppedDuplicate(String candidateRef, String externalId) implements CandidateResult {}
public record Held(String candidateRef, String externalId) implements CandidateResult {}
public record Flagged(String candidateRef, String externalId, List<Flag> flags) implements CandidateResult {}
public record Rejected(String candidateRef, String reason) implements CandidateResult {}

// Batch-level status (see §3.3 allOrNone)
public enum BatchStatus { COMMITTED, PARTIAL, REJECTED }

// Matcher outcome — sealed so the matcher switch is exhaustive
public sealed interface MatchOutcome
    permits ExactTransfer, FuzzyTransfer, AmbiguousTransfer, HeldLeg, NotTransfer {}
public record ExactTransfer(String legA, String legB, String transferId) implements MatchOutcome {} // T1
public record FuzzyTransfer(String legA, String legB, String transferId) implements MatchOutcome {} // T3 high
public record AmbiguousTransfer(String leg, List<String> candidates)     implements MatchOutcome {} // >1 contra → REVIEW
public record HeldLeg(String leg)                                        implements MatchOutcome {} // transfer-shaped, no contra yet
public record NotTransfer(String leg)                                    implements MatchOutcome {} // ordinary → EXTERNAL

// Occurrence signature (content-hash banks)
public record Sig(String accountRef, LocalDate date, long amount, String rawDescription) {}
```

**`ATTESTATION` — the line that is not a movement.** `amount = 0`, `balance` authoritative, valid
**only** on an account whose `balanceSource` is `declared` (§6); on a `statement` account it is a
hard `Rejected`. It records no transfer of value — only what a person states they hold, on their own
authority, at a moment of their choosing.

It is named for what it is rather than what it counts: `TypeHint`'s other values name **movements**,
and this is a *claim*. That distinction is load-bearing downstream — a claim can be recorded and
reported, but it can never be verified, because nothing independent exists to contradict it (§5.9).

`TypeHint` is already what every consumer switches on to decide whether a line's `balance` means
anything: a TRANSFER line carries `0` and is skipped by the reconciliation fold (§7 test 6), by the
hledger egress and by the Firefly egress. `ATTESTATION` is the mirror image — on a `declared`
account it is the *only* line whose balance means anything.

`0` could not have served as the sentinel for "no balance here", because **$0 is a legitimate
attestation**: you spent your last cash. The distinction has to be structural, not a magic value.


### 2.4 Identity (trex-core/Ids)
```java
String externalId(IdentityStrategy s);   // sha256 of a canonical string, first 16 hex chars
String transferId(String receipt);       // "TRF-" + receipt   (T1, shared receipt)
String transferId(String idA, String idB);// "TRF-" + sha256("tr|" + min(idA,idB) + "|" + max(idA,idB))[:16]  (T3 / manual)
```
- Canonical string for `NaturalKey`: `"nk|" + accountRef + "|" + date(ISO) + "|" + receipt`. **The date is in the key** because a bank's receipt number is a rolling counter, not a permanent id: ING's are six digits and not monotonic, so over a long history one can recur within an account, and without the date the later transaction would be silently dropped as a duplicate. The cost, accepted: a bank restating a transaction's *date* mints a new id — rarer than a bank restating its text, which is what the natural key exists to survive (DECISIONS S5).
- Canonical string for `ContentHash`: `"ch|" + accountRef + "|" + date(ISO) + "|" + Long.toString(amount) + "|" + rawDescription + "|" + occ`. (`long` cents has exactly one representation — no `10.0`-vs-`10.00` hazard.)
- Hashing: canonical string encoded **UTF-8**, SHA-256, **lowercase** hex, first 16 hex chars. `min`/`max` are `String.compareTo` order.
- `rawDescription` is the CSV-unquoted field value, **untrimmed**.
- **Frozen:** the exact string format and hashing are the identity contract. Do not reorder/reformat after go-live.

### 2.5 Occurrence index (Stream Gatherer)
Compute `occ` **per batch**, only for content-hash (ref-less) candidates. Natural-key candidates skip occ.
```java
// Groups by Sig (accountRef,date,amount,rawDescription) and assigns 0,1,2... in stable order.
// Implement as a stateful Gatherer<Candidate, Map<Sig,Integer>, OccCandidate> or a two-pass helper.
List<OccCandidate> assignOcc(List<Candidate> batch);
record OccCandidate(Candidate c, int occ) {}   // occ = 0 for natural-key
```
Determinism requirement: within a batch, identical-`Sig` candidates get stable ascending `occ` in input order. **A signature-group must never span two calls.** The unit that guarantees this is `(account, day)`; since a file is one account, the rule reduces to *never split a calendar day across calls* — the ingress client (§4) may split a large file, but only on whole-day boundaries.

Input row order is not assumed to be meaningful (§4): `occ` follows the order rows arrive in. The same file always yields the same ids; a re-exported file that reorders identical-`Sig` rows may swap their `occ` — accepted, and it surfaces as `POTENTIAL_DUP` because their balances differ (§3.3).

### 2.6 Journal state fold (pure)
Pure, I/O-free types every journal consumer shares (moved here so the sequencer, followers and resolver agree exactly):
- `Ledger` — folds journal lines in order: `firstLine[externalId]`, `latest[externalId]` (highest `n`), HELD index, `highWaterN`, head offset. Rejects non-increasing `n`.
- `LedgerView` — immutable snapshot: `held()` (latest state HELD), `review()` (latest state REVIEW **or** `POTENTIAL_DUP` ∈ latest flags), both ordered by `n`.
- `Projection.projectableUnits(latestLines)` — TRANSFER lines + EXTERNAL transactions not listed in any TRANSFER's `legIds`.
- `Reconciliation.reconcile(journalLines)` — §7 test 6 algorithm.

---

## 3. trex-sequencer — the service

### 3.1 Journal (append-only, single-writer, framed JSONL)
Interface (the swap seam for JSONL↔SQLite-log later):
```java
public interface Journal {
    /** Append a committed batch atomically. Returns the new head offset. */
    long appendBatch(List<CanonicalEvent> events);
    /** Replay complete records from a byte offset. Torn tail is ignored (not returned). */
    Stream<CanonicalEvent> replayFrom(long offset);
    long headOffset();
}
```
**JSONL writer rules:**
- One event per line, `\n`-terminated, UTF-8, deterministic field order (§2.2).
- Byte format: null fields are **written** (not omitted); `flags` always an array (`[]` when empty); `legIds` `null` on non-TRANSFER lines; `LocalDate`/`Instant` as ISO-8601 strings.
- When the journal file is created (new journal, or a materialize copy), fsync the file and its parent directory, so the directory entry itself survives a crash.
- Open target with `O_APPEND`. `appendBatch` serializes all events to one byte block, writes it fully, then `force(true)` (fsync) **before** the sequencer updates in-memory state / returns success. One fsync per `appendBatch` (i.e. per API call), always — there is no fsync policy option. Crash before fsync ⇒ torn tail ⇒ truncated on next recovery ⇒ batch simply absent ⇒ adapter retries (idempotent).
- **Framing on read** (`trex-journal` `FramedReader`, shared by every reader): a record is complete iff it ends in `\n` **and** parses as a `CanonicalEvent`. A trailing partial line is *not* returned and the read offset is *not* advanced past it. A `\n`-terminated line that does not parse is corruption: readers stop with an error (recovery aborts startup) — never skip or truncate it.

### 3.2 Recovery / materialize (startup)
Params: `journal.source`, `journal.target` (both paths).
```
if source == target:
    scanToLastCompleteRecord(target)   // find last valid '\n'-framed, parseable line
    truncate any torn tail             // truncate file to that boundary
    openForAppend(target)
else:                                   // materialize (shm transport, migration, restore)
    byteCopy(source -> target)          // Files.copy / FileChannel.transferTo — NEVER re-serialize; overwrites target
    verify: sha256(source) == sha256(target)  else ABORT
    scanToLastCompleteRecord(target); truncate torn tail
    openForAppend(target)
```
When `source != target`, **source is authoritative**: target is overwritten on every startup. Keeping source current is the operator's responsibility.

Then **fold** the journal into memory (this *is* self-ingest). The journal is a log of **versions**: when a transaction's state changes, a full copy of it is re-appended with a new `n` and the new `state` (§3.3, §3.4). **The latest line (highest `n`) for an `externalId` is its authoritative current state.** Nothing is ever rewritten; restart reproduces identical state.
```
for each replayed line, in journal order:
    if externalId not in firstLine: firstLine[externalId] = line   // dedup set; balance for drift check
    latest[externalId] = line                                     // current version
    highWaterN = max(highWaterN, n)
derived views (from latest):
    held   = { id | latest[id].state == HELD }
    review = { id | latest[id].state == REVIEW  or  POTENTIAL_DUP ∈ latest[id].flags }
```
Rules are **never re-evaluated** on replay — replay uses stored `state` only, so tuning rules (§3.4) affects future ingests only.

**Re-append rules** (every re-appended version):
1. Identical to the previous line for that `externalId` except `n`, `state`, `flags` and `comment` (`ingestedAt`, `balance`, `description`, … unchanged). `comment` is the comment of the decision that caused the re-append, else `null` (not carried over).
2. Allowed state transitions: HELD→MATCHED, HELD→EXTERNAL, REVIEW→MATCHED, REVIEW→EXTERNAL. A flag-only re-append (§3.3 dedup, `DISMISS_DUP`) keeps `state` unchanged.

**Read consistency:** after each successful commit the sequencer publishes an immutable snapshot of in-memory state via an `AtomicReference`. Read endpoints use the snapshot: lock-free, never a half-applied batch.

### 3.3 Ingress pipeline (`POST /candidates`)
Validate-all-then-commit (atomic per batch):
```
1. parse body to a JSON tree; bind each batch element individually
     malformed body / wrong top-level structure -> 400, nothing appended
     malformed element                          -> Rejected(ref, reason)
   missing candidateRef -> "idx-" + zeroBasedIndex
2. VALIDATE each (accountRef known via registry? amount/date present & parseable? `balance` present
   & parseable **when the account's `balanceSource` is `statement`, or the line is an `ATTESTATION`**
   — otherwise it is written as 0 and carries no meaning; an `ATTESTATION` on a `statement` account
   is a hard `Rejected`).
   On any hard Rejected, branch on the request's `allOrNone` (default false):
     allOrNone=true  -> append NOTHING; batchStatus=REJECTED; results carry every Rejected
     allOrNone=false -> exclude the rejects; the valid subset proceeds; batchStatus=PARTIAL
   NOTE: allOrNone governs VALIDATION rejects only; it never weakens append atomicity (§3.1).
3. assignOcc(batch)                       // §2.5, over the surviving candidates
4. for each candidate -> mint externalId (NaturalKey if receipt else ContentHash)
5. DEDUP against firstLine (and earlier candidates in this batch):
       same balance                                   -> DroppedDuplicate, nothing appended
       different balance, latest already POTENTIAL_DUP -> Flagged(POTENTIAL_DUP), nothing appended
       different balance otherwise                     -> re-append latest version, state unchanged,
                                                          flags=[POTENTIAL_DUP]; Flagged(POTENTIAL_DUP)
6. MATCH (§3.4) each genuinely-new candidate, in input order -> new-leg state
       ExactTransfer / FuzzyTransfer -> MATCHED (contra: new leg in batch → MATCHED directly;
                                        existing HELD leg → re-appended MATCHED) + a TRANSFER line
       AmbiguousTransfer             -> REVIEW    (only the incoming leg; other legs untouched)
       HeldLeg                       -> HELD
       NotTransfer                   -> EXTERNAL
7. build lines to persist, in this order:
       a. new legs (input order): stamp `currency` (registry), `sourceType`, `ingestedAt` (Clock);
          `description` = clean(rawDescription); typeHint = WITHDRAWAL if amount < 0 else DEPOSIT;
          flags=[]; confidence=null
       b. re-appended versions (dedup flags, matched HELD legs)
       c. TRANSFER lines (§3.4)
   assign n = ++highWaterN to each line in that order
8. journal.appendBatch(lines)             // atomic; fsync
9. update in-memory state (firstLine, latest, highWaterN) and publish snapshot
10. respond { batchHandle, batchStatus: COMMITTED | PARTIAL | REJECTED, results:[...] }
       results per candidate: new EXTERNAL / MATCHED -> Resolved(ref, id, n); HELD -> Held;
       REVIEW -> Flagged(ref, id, []); dedup -> DroppedDuplicate / Flagged(POTENTIAL_DUP); Rejected
```
`clean(rawDescription)` is one swappable pure function: trim, collapse internal whitespace runs to a single space. It never affects identity.

`batchHandle` = server-minted per submission (UUID or a monotonic long), returned for audit, **not** supplied by caller. Under `PARTIAL`/`REJECTED` the caller **must** read `results` to learn what landed (a bare status is insufficient).

### 3.4 Transfer matcher + state rules
Deciding which state a new candidate gets (`MATCHED` / `HELD` / `REVIEW` / `EXTERNAL`) lives in trex-sequencer and is expected to evolve into a tunable rule set. **Phase 1 starts defensive:** when unsure → HELD or REVIEW, never an automatic guess; rules are loosened later based on what the review workflow shows.
- **Transfer-shaped:** `rawDescription` matches any allowlist regex from `transfers.yaml` (case-insensitive), e.g. `Internal Transfer`, `To my account`, Osko/PayID/`Fast Transfer`. A receipt alone does not make a leg transfer-shaped.
- **Match pool:** legs whose current state is HELD, plus new legs earlier in the same batch. REVIEW, EXTERNAL and MATCHED legs are never auto-matched.
- **Tiers (first match wins), as a sealed `MatchOutcome`:**
  - **T1** same `receipt` in two different accounts, opposite sign (transfer-shaped or not) → `ExactTransfer` → `TRF-<receipt>`, confidence `EXACT`. More than one T1 contra → `AmbiguousTransfer`.
  - **T3** both legs transfer-shaped, `|amount|` equal, opposite sign, dates within `windowDays`, different accounts, same currency → `FuzzyTransfer` → `transferId(idA,idB)`, confidence `HIGH`. No text corroboration in phase 1. More than one T3 contra → `AmbiguousTransfer`.
  - (T2 is not defined and not implemented.)
  - no match: transfer-shaped → `HeldLeg`; otherwise → `NotTransfer`.
- **No aging.** A HELD leg leaves HELD only by an automatic match or a manual decision (§3.5). There are no watermarks and no time-based transitions.
- **Collapse:** keep **both leg lines in the journal** for audit. A resolved transfer produces a **TRANSFER line**, the projectable unit (what the Firefly egress posts as one transfer):
  - `externalId = transferKey = TRF-…`, `typeHint = TRANSFER`, `state = MATCHED`, `legIds = [fromLeg, toLeg]`, `n` after its legs.
  - `accountRef` = from (negative-amount) leg's account; `toAccountRef` = to (positive-amount) leg's account; `amount = |amount|`.
  - `date`, `currency`, `description`, `rawDescription`, `sourceType` copied from the from leg; `balance = 0`.
  - `receipt` = shared receipt for T1, else `null`; `confidence` = `EXACT` (T1 or manual) / `HIGH` (T3); `provenance` = `BANK` (automatic) / `AUTHORED` (manual).
  - `flags = []`; `corrects`, `counterpartyBsb`, `counterpartyAcct`, `foreignAmount`, `foreignCurrency` = `null`; `comment` from the decision (manual) else `null`.
- **Journal shape:** legs in two batches → 4 lines (leg A `HELD`, leg B `MATCHED`, leg A re-appended `MATCHED`, TRANSFER). Legs in one batch → 3 lines (two `MATCHED` legs, TRANSFER). Followers/projectors select transfers by `typeHint == TRANSFER` and skip legs listed in some TRANSFER's `legIds`, to avoid double-counting.

Use **pattern-matching `switch` over sealed `MatchOutcome`/`IdentityStrategy`** so adding a tier or state fails compilation until every site handles it.

### 3.5 HTTP API (JDK HttpServer)
- `POST /candidates` — body `{ "allOrNone": false, "batch": [Candidate...] }` (`allOrNone` optional, **default false**) → `{ batchHandle, batchStatus: COMMITTED|PARTIAL|REJECTED, results:[CandidateResult...] }`. 200 on all data-level outcomes (incl. `PARTIAL`/`REJECTED`); **non-2xx only on structural/atomicity/internal failure** (nothing appended).
- `GET /held` — latest line of every transaction whose current state is HELD, ordered by `n`.
- `GET /review` — latest line of every transaction whose current state is REVIEW **or** whose latest `flags` contain `POTENTIAL_DUP`, ordered by `n`.
- `POST /decisions` — manual resolution (below).
- `GET /head` — `{ offset, n }` (followers/resolver can poll).
- `GET /reconcile` — per-account reconciliation of the published snapshot using the §7 test 6 algorithm → `{ n, offset, ok, accounts: [ { accountRef, reconcilable, balances, opening, closing, sum } ] }`, accounts sorted by `accountRef`. Read-only and lock-free (no write lock, §3.2 snapshot); appends nothing. `balances` is `reconcilable && sum == closing − opening`; `ok` is true when every account balances (vacuously true for an empty journal). **Never guesses:** a broken chain reports `reconcilable: false` with `opening`/`closing` `0` and the account's `sum` as computed. This is the operational form of the reconciliation tripwire referred to in §4 — until it is reachable at run time, the backstop exists only in the test suite.

`GET /held`, `GET /review` and `POST /decisions` form the **resolution workflow** and are fully implemented in phase 1. The manual resolver is a separate service (a journal follower that calls this API); it is not one of the phase-1 modules.

**`POST /decisions`**
- Request: `{ "allOrNone": false, "decisions": [ { "decisionRef", "action", ... } ] }`; body binding as §3.3 step 1.
  - `MARK_EXTERNAL`: `externalId`, optional `comment`.
  - `CONFIRM_TRANSFER`: `legA`, `legB`, optional `comment`.
  - `DISMISS_DUP`: `externalId`, optional `comment`.
- Response: `{ batchHandle, batchStatus, results }` (same envelope as `/candidates`). Success → `Resolved(decisionRef, externalId, n)` (leg id for `MARK_EXTERNAL`/`DISMISS_DUP`, `TRF-…` id for `CONFIRM_TRANSFER`); failure → `Rejected(decisionRef, reason)`. `allOrNone` as in `/candidates`.
- Rejected when: unknown `externalId`; for `MARK_EXTERNAL`/`CONFIRM_TRANSFER` current state not HELD/REVIEW; for `CONFIRM_TRANSFER` also same leg twice, same account, different currency, amounts not equal-and-opposite, or a leg already used by an earlier decision in the same request (`windowDays` not enforced — human override); for `DISMISS_DUP` the latest line lacks `POTENTIAL_DUP` (any state allowed).
  **There is no category decision.** Categories are not journal data (§0.7): a correction is a pin in `categories.yaml` (§5.6), so the sequencer neither stores nor validates a category, and `POST /decisions` has no action for one.
- Output:
  - `MARK_EXTERNAL` → leg re-appended with `state = EXTERNAL`, `comment`.
  - `CONFIRM_TRANSFER` → both legs re-appended `MATCHED` with `comment` + TRANSFER line (`confidence = EXACT`, `provenance = AUTHORED`, `comment`, `transferKey = transferId(idA,idB)`).
  - `DISMISS_DUP` → re-appended with `flags = []`, state unchanged, `comment`.
- All accepted decisions in one request are one atomic `appendBatch` (line order: re-appended legs, then TRANSFER lines).
- Not in phase 1: "keep-both" and "MAN-" manual entries.

**Binding:** the API has no authentication, so it listens on `bindHost:bindPort` with `bindHost` defaulting to `127.0.0.1`; any other host must be set explicitly, and the sequencer prints a warning at startup.

**Decisions are final:** there is no undo. A mistaken decision cannot be reversed through the API; this is why the resolver double-confirms every action (§5.4). (This is one reason categories are not decisions: a mis-categorisation must be correctable, and it is — by editing `categories.yaml`, which changes no journal line, §5.6.)

**Single-writer:** guard all mutating endpoints with one write lock (or a single-threaded executor) so appends are serialized. Reads are lock-free over the published snapshot (§3.2).

### 3.6 gzip (negotiated, both directions, manual)
`HttpServer` has **no** content-coding support, so implement it once and route every handler through two helpers:
```java
byte[] readBody(HttpExchange ex);                          // transparently degzips (see rules)
void   writeJson(HttpExchange ex, int status, Object obj); // transparently gzips per Accept-Encoding
```
**Negotiation contract — four independent paths, {plain,gzip} in × {plain,gzip} out, never coupled:**
- *Request:* degzip the body **iff** the request has `Content-Encoding: gzip` (honor the header; do not sniff). Plain JSON must always be accepted, so `curl`/`jq`/non-gzip clients keep working.
- *Response:* gzip the body **iff** the request sent `Accept-Encoding: gzip`, and then set `Content-Encoding: gzip`. A client that didn't advertise it gets plain JSON. Output coding is decided by the client's `Accept-Encoding` **regardless of** the request's input coding.

**JDK-server gotchas (state these so they aren't rediscovered the hard way):**
- Inbound: wrap `ex.getRequestBody()` in `GZIPInputStream` when gzip; otherwise Jackson parses gzip bytes as JSON and throws a misleading parse error.
- Outbound: set `Content-Encoding: gzip` **before** `sendResponseHeaders`, call `sendResponseHeaders(200, 0)` (0 = chunked/unknown length — correct, since compressed size isn't known up front), then stream through `GZIPOutputStream` and close. **Never** pass the uncompressed length as the response length — the classic corruption bug.

**Bounded decode (zip-bomb guard):** apply the request size limit to **decompressed** bytes, enforced *while streaming the decode* with a hard cap (e.g. 100 MB) — abort and return `413` if exceeded. A gzipped body can inflate enormously; cap it even on a trusted LAN.

**Boundary rule (correctness, not cosmetics):** gzip is a **wire/transport coding only**. It terminates at the API boundary — the instant the body is decompressed, identity minting, `external_id`, and the JSONL the sequencer writes are all over **plain** bytes. **gzip never touches the journal or the identity contract.** The on-disk journal stays plain (preserves `jq`-ability and the byte-copy-materialize contract); leave storage compression to ZFS. Followers are unaffected — they tail the journal *file*, not the API.

---

## 4. trex-ingress — ingress client (source types: `ing-csv`, `bw-csv`, `cba-csv`, `cba-pdf`)

> **`manual` is a source type but not an ingress one.** A hand-entered line (§5.7 `POST /api/cash`)
> carries `sourceType: "manual"` and `provenance: AUTHORED`, and never travels this path: trex-ingress
> parses files, and there is no file. **Every parser below still hard-rejects a row with no balance**,
> unchanged — a *source* may never drop a balance the bank published, whatever an *account* declares
> about where its balances come from (§6). That is why `cba-pdf` was admitted and the BankWest PDF
> refused, and nothing here reopens it.

Separate CLI program; talks to trex over HTTP. Demonstrates the candidate contract. **All source-specific behavior (banks, feeds) lives in trex-ingress**; the sequencer has none. One module holds every source type: the CLI, HTTP client, gzip and day batching are shared (package `trex.ingress`), and each source type is one parser in its own sub-package (`trex.ingress.ing`, …). `ing-csv` was the starter; `bw-csv` (BankWest), `cba-csv` and `cba-pdf` (CommBank) followed; CDR and feed source types follow the same model in a later phase.
```
Usage: trex-ingress --source-type <type> --account <accountRef> --sequencer-url http://trex:PORT
                    [--batch-rows N] [--no-gzip] <source>
```
- **`--source-type`** (required, no default) selects the parser and is stamped into every candidate's `sourceType`. It is the only binding between an input and its parser: the registry does not record one (§6), because one account may arrive through several source types. An unknown type is a usage error. Known: `ing-csv`, `bw-csv`, `cba-csv`, `cba-pdf`.
- **`<source>`** is the instance being read; for the CSV and PDF types, the statement file.
- **`--sequencer-url`** matches trex-gateway's flag (§5.7); trex-ingress and trex-gateway are the only things that address the sequencer directly.
- **`ing-csv` format:** header `Date,Description,Credit,Debit,Balance`; `dd/mm/yyyy`; `amount = coalesce(credit,0) + coalesce(debit,0)` (**debit already negative**); `receipt` via regex `Receipt (No )?(\d+)` on description; balance signed. `rawDescription` = verbatim column (CSV-unquoted, untrimmed).
- **`bw-csv` format (BankWest):** header `BSB Number,Account Number,Transaction Date,Narration,<cheque>,Debit,Credit,Balance,Transaction Type`, where the fifth column is labelled `Cheque` **or** `Cheque Number` — BankWest ships both, and the rest of the header is identical. `dd/mm/yyyy`. Exactly one of `Debit`/`Credit` must be set.
  - **The debit sign varies by export, so it is inferred per file, never assumed.** One BankWest export writes debits positive, another writes them already negative. The rule: if every non-empty `Debit` is positive, `amount = credit − debit`; if every one is negative, `amount = credit + debit`; **a file mixing both signs is rejected**, because there is then no sound reading of it. Assuming one convention would silently invert every debit in a file of the other kind — 1,517 rows in the export this rule was written from — and the amount is hashed into identity (§2.4), so the damage is permanent ids rather than a visible error. Whole-file validation (below) is what caught it: the first assumption was rejected loudly by the negative-debit check rather than applied. `rawDescription` = `Narration` verbatim. **No receipt**: every row is content-hash identity, so `occ` and day-atomic batching carry the weight here (§2.5). `BSB Number` and `Cheque` are always empty; `Account Number` and `Transaction Type` are read but not used — the account comes from `--account` and `typeHint` from the amount's sign.
- **`cba-csv` format (CommBank):** **no header row** — the first line is data, four columns: date, amount, description, balance. `dd/mm/yyyy`; the amount is **already signed and explicitly prefixed** (`-75.00`, `+1000.00`), so there are no credit/debit columns to combine; balance likewise (`+2202.43`). `rawDescription` = the description column verbatim. **No receipt**: content-hash identity, as for `bw-csv`. Because there is no header to check, the shape *is* the validation: exactly four fields, a parsable date and exact-cents amounts, so a file of another type fails on its first row rather than being half-read. A first row that fails to parse **and** contains `Date` is reported as "looks like a header; `cba-csv` expects none".
- **`cba-pdf` format (CommBank Transaction Summary PDF):** the same statement CommBank renders as a PDF, and for some accounts the *only* export available. Text is extracted with PDFBox (`PDFTextStripper`, `sortByPosition`), then read line by line:
  - A **transaction line** is `dd MMM yyyy` (English month names — the parser pins `Locale.ENGLISH`, never the platform default), then the description, then two `-?$n,nnn.nn` amounts: the amount and the running balance. Amounts carry `$` and thousands separators, and the minus precedes the `$`; both are stripped before the exact-cents conversion, which stays as strict as everywhere else (§0.4).
  - A **continuation line** is any other line after a transaction line; it is part of that transaction's description.
  - **Page furniture** is everything from the `Created dd/mm/yy` footer up to the next `Date Transaction details` header, plus the preamble before the first such header. This rule is not cosmetic: CommBank's footer is three lines, and a parser that skips only the first appends the other two to whichever description straddles the page break — silently corrupting exactly the rows at page boundaries and minting wrong ids for them.
  - **`rawDescription` is normalised, not verbatim** — the one place a source type departs from §2.4, because a PDF has no canonical byte string to be verbatim about. The frozen rule: trim each line, join continuations with exactly one space, collapse internal whitespace runs to one space. **Frozen** in the same sense as §2.4: changing it re-mints every id for this source type.
  - **No receipt**: content-hash identity, as for the other CommBank and BankWest types. No pending rows — the statement states that pending transactions are excluded.
  - Account metadata (BSB, account number, account type) is printed in the header and is **read but not used**: the account comes from `--account`, as for every other source type.
  - **Why this is safe enough to hash:** PDFBox and poppler's `pdftotext` were checked against the same account's CSV export for an overlapping period, and both reproduce all 63 CSV descriptions byte-for-byte. Two unrelated extractors agreeing is what makes the extraction stable enough to sit in the identity path; the golden-file test (§7 test 1) is what keeps it that way across library upgrades.
  - **Consequence worth having:** because the text matches, the same transaction ingested from the CSV and from the PDF mints the *same* `external_id`, so the two sources dedup against each other and a PDF backfill over a CSV-covered period is idempotent.
- **When a PDF earns a source type.** A bank's PDF is admitted only when **both** hold, each shown by measurement rather than assumed:
  1. **It is the only export for that account**, or it covers history no other export does. A PDF that merely restates an available CSV adds risk for nothing.
  2. **Its rows mint the same `external_id` as every other source already ingested for that account** — the same receipt, or text that extracts identically. Otherwise the same transaction is two transactions, and because dedup keys on the id, nothing flags it: only the §7 test 6 tripwire notices, afterwards.
  Three were assessed: **`cba-pdf` admitted** (only source for one account; extracted text matched its CSV byte-for-byte, 63 of 63). **ING rejected** — every account exports CSV, and ING reorders, re-pads and relabels the same fields between its exports, while its card PDF carries no receipts at all (DECISIONS S5). **BankWest rejected** — no receipts, *no per-row balance* (so §7 test 6 could never reconcile the account), the PDF dates transactions where the CSV posts them, and the CSV already covers more (DECISIONS S7).
- **Truncated descriptions (`cba-csv`):** CommBank truncates long descriptions with a trailing `...` in the export itself. That text is hashed verbatim like any other (§2.4). If CommBank ever truncates the same transaction at a different length, it mints a different id — the reconciliation tripwire (§7 test 6) is the backstop, as for any restatement.
- **Pending rows (`bw-csv`).** BankWest exports authorisations that have not settled, marked by a `Narration` starting `AUTHORISATION ONLY` (their `Transaction Type` is also blank). They are **skipped, not ingested, and reported** with the bad rows: the same purchase settles later under a different narration, which is a different content hash, and the journal is append-only — an ingested pending row could never be removed, only masked by a decision. Skipping is safe for reconciliation because pending rows sit at the newest end, so the remaining rows keep a contiguous balance chain; if one ever appears with settled rows after it, the §7 test 6 tripwire is the backstop. A row is pending or it is not — this is never a reason to reject the file.
- **Row order:** not assumed. Rows are sent in file order as-is — no sorting or reversal (§2.5).
- **Amount parsing (cents, the only place `BigDecimal` lives):** each decimal column → cents via `new BigDecimal(str).movePointRight(2).longValueExact()`. Never `Double.parseDouble(x) * 100` (reintroduces float error), never rounded. Applies to `amount` and `balance`.
- **Whole-file validation before sending:** the adapter parses and validates the entire file first. Any value with >2 decimals or otherwise not convertible to exact cents → **nothing is sent**; the adapter prints every bad row (file, line, column, value) and exits non-zero. (Dropping a single row could shift `occ` for later identical-`Sig` rows once the fixed row is re-ingested.) Such rows never reach the sequencer or review; fix the file (or parser) and re-run (idempotent).
- Build one `Candidate` per row; `candidateRef = "row-" + lineNumber`; `provenance = BANK`; `sourceType` = the `--source-type` value; leave `currency` unset (sequencer stamps it).
- **Batching (day-atomic).** Default: whole file = one `/candidates` call. For a very large file the client MAY split across calls, **but only on whole-day boundaries** — never mid-day (occ is `(account,day)`-scoped; a file is already one account, so the rule reduces to *don't split a calendar day*). Algorithm: group rows by date; pack whole day-groups into a call up to a soft size target; **if one day alone exceeds the target, send that day as its own call anyway — a day is the atom, size yields to correctness**. Each call is an ordinary independent batch; the server needs no chunk-awareness, and `allOrNone` applies per call.
- **Cross-file caveat:** the client keeps a day whole only *within one file*. Pulling whole days per statement (operator discipline) prevents a day splitting across two files; the reconciliation tripwire (§7 test 6) is the backstop if it ever does.
- Print the response: per-row status; non-zero exit if `batchStatus != COMMITTED`.
- HTTP via `java.net.http.HttpClient`; Jackson for (de)serialization.
- **gzip (mirror of §3.6; also manual — `HttpClient` does not auto-gzip):** gzip the request body and set `Content-Encoding: gzip` (candidate arrays compress ~8–12×, so this is worth it for large backfills); always send `Accept-Encoding: gzip`; and degzip the response **iff** it comes back with `Content-Encoding: gzip`. Keep it symmetric with the server helpers so a plain-mode run (no gzip) still works for quick tests.

Acceptance: parsing a slice of each source type yields the same candidates every run (golden file per type); a header-less `cba-csv` slice parses from its first line, and an `ing-csv` file passed as `cba-csv` is rejected rather than half-read; a re-run POST returns all `DroppedDuplicate` (idempotent); a gzipped POST and a plain POST of the same file produce identical journal results (gzip is wire-only); a `bw-csv` slice with a pending row sends every other row and reports that one.

---

## 5. Journal consumers (egress followers, admin services, shared libraries)

Followers **read the journal file directly** (single-writer append-only makes this safe), tail by byte offset with framing, and persist only their offset. They do **not** use the trex HTTP API. Both phase-1 followers are **journal mirrors**: one output record per journal line, keyed by `n`, with no transaction-state logic.

### 5.1 Shared follower loop (framed reading via `trex-journal`)
```
register journal change signal (JournalChanges)   // before the first pass: no change is missed
                                                  // watches the DIRECTORY, so it may be registered
                                                  // before the journal file exists
loop (wake on journal change event, or after pollSeconds as fallback):
    if journalPath does not exist:
        continue                                # nothing consumed, offset unchanged
    channel = open(journalPath, READ)
    channel.position(persistedOffset)
    buffer = read available bytes
    while buffer has a complete '\n'-terminated line:
        event = parse(line)                     // if parse fails on a NON-terminated tail -> stop
        consume(event)                          // sink-specific, MUST be idempotent
        advancedOffset = offset after this line
    persist(advancedOffset)                     // only past COMPLETE, consumed records
    # trailing partial line: not consumed, offset not advanced, retried next pass
```
Rule: **a missing journal is not an error.** A follower may be started before the sequencer has created the journal, and the file may be moved aside under a running follower (`source != target` recovery, §3.2). Either way the pass is skipped, nothing is consumed and the persisted offset is untouched; the follower waits for the next wake and picks up when the file is back. It waits indefinitely and logs the absence once, at `info` — a follower that exits because its input has not appeared yet is a restart loop, not a diagnosis. `--once` on a missing journal drains nothing and exits **0**, the same result as an empty journal.

Rule: **advance the offset only after the consume side-effect is durable.** At-least-once + idempotent consumers (never attempt exactly-once via clever offset games). A follower wakes as soon as the journal changes (`trex-journal` `JournalChanges`: a `WatchService` on the journal's directory — inotify on Linux; an `OVERFLOW` also wakes it; events are hints, never counts); `pollSeconds` is only the fallback for missed events and filesystems without inotify. Batching of follower work is never done by delaying the sequencer's fsync.

### 5.2 trex-egress-archive (log-mirror follower)
- Consume = append the line's event to an archive JSONL at `archivePath` (cold copy / second location).
- Idempotent by `n`: skip if that `n` is already archived (`n` is strictly increasing, so the highest archived `n`, loaded at start, suffices).
- Cursor: a plain offset file (`archivePath + ".offset"`). This is a pure mirror — no resolution logic, bare offset is correct.

### 5.3 trex-egress-sqlite (log-mirror follower → SQLite WAL)
- The database mirrors the **journal**, not transaction state: one row per journal line, primary key `n`, every `CanonicalEvent` field stored.
- SQLite with `PRAGMA journal_mode=WAL;`. Schema:
  ```sql
  CREATE TABLE IF NOT EXISTS journal (
    n INTEGER PRIMARY KEY,
    external_id TEXT NOT NULL,
    account_ref TEXT, to_account_ref TEXT, currency TEXT,
    date TEXT, amount INTEGER, balance INTEGER,
    description TEXT, raw_description TEXT,
    type_hint TEXT, transfer_key TEXT, leg_ids TEXT,      -- JSON array or NULL
    corrects TEXT, state TEXT, confidence TEXT, flags TEXT, -- flags: JSON array
    provenance TEXT, source_type TEXT, receipt TEXT,
    counterparty_bsb TEXT, counterparty_acct TEXT,
    foreign_amount INTEGER, foreign_currency TEXT,
    comment TEXT, ingested_at TEXT
  );
  CREATE INDEX IF NOT EXISTS journal_external_id ON journal(external_id);
  CREATE TABLE IF NOT EXISTS follower_state (k TEXT PRIMARY KEY, offset INTEGER);
  ```
- **Exactly-once into SQLite for free:** do the `INSERT ... ON CONFLICT(n) DO NOTHING` **and** the `follower_state` offset update **in one transaction**. Because SQLite is transactional, insert+offset-advance commit atomically → no at-least-once duplicate window for this sink. (The file-only archive follower can't have this, hence its `n` check.)
- `amount`/`balance` stored as `INTEGER` (cents) — exact; never bind as REAL. `balance` is provenance only (never queried for identity/semantics).
- Current state of a transaction = the row with the highest `n` for its `external_id`; the follower itself holds no state logic.
- Cursor lives in `follower_state`, not a sidecar file.

### 5.4 trex-web — the web UI (browse, resolve, categorise)

The pages and nothing else. It holds no journal state, does no folding, owns no config and knows only one address: it serves the static pages and proxies **everything** to `trex-gateway` (§5.7). **It never writes the journal and never writes a config file.**

Tabs: **Transactions** and **Journal** (the table, below), **Categorize** (§5.7) and **Resolve** (HELD/REVIEW). One origin for the browser, so no page makes a cross-service call and there is one CSRF posture rather than three.

- **Why a proxy and not a browser talking to the services:** more than one backend from one page means CORS on every mutating endpoint — the cross-origin write path §5.5 records as the reason the grid and resolver were merged in the first place — and a permissive CORS header on a service that writes config files ages badly. The browser sees one origin; trex-web forwards to one upstream. That the upstream is *also* one service is what makes trex-web a genuinely thin shell: a static server plus a proxy plus an SSE relay, with no routing decisions of its own.
- **Binding:** `127.0.0.1` by default. It can take decisions, so it carries the same posture as the sequencer's decision API; §9 keeps the second-listener option for a read-only exposure.
- **State:** none, persisted or otherwise, with one exception — the **last good snapshot and SSE frame from `trex-gateway`**, kept in memory so a `trex-gateway` restart shows a stale-but-labelled page rather than an empty one. A stale answer carries `X-Trex-Stale: 1` and the page shows it as stale, never as current. A **write is never served from cache**: one that did not reach the gateway did not happen, and returns `502`.
- **Loop:** user acts → trex-web forwards to trex-gateway → trex-gateway either amends a rule file or forwards a decision to the sequencer → the journal or the rule files change → trex-gateway re-materialises and pushes SSE → trex-web relays it → page refreshes. The page reflects the view, never an optimistic local change.
- **Plumbing:** static page serving with the security headers, JSON/error helpers, an SSE relay (one upstream stream from trex-gateway, fanned out to browser clients with the same coalescing, heartbeat and client cap as before).

**Browsing the journal (G-series, formerly §5.5)**

- **Views (G1):** **Transactions** (default) — the latest line per `externalId`; **Journal** — every line, every version.
- **Columns (G2):** `n`, date, account, to-account, amount, currency, description, type, state, flags, confidence, provenance, sourceType, receipt, transferKey, comment, `category`, ingestedAt, externalId. `category` is derived per row (§5.6) and shows on hover which rule produced it; a pinned row and a structural `TRANSFER` are badged as such. A compact default set is shown; the rest can be toggled on (column choice kept in the browser's `localStorage`).
- **Sort (G3):** server-side on any column, asc/desc; click a header to sort, shift-click to add a secondary key. Ties always break by `n` ascending, so paging is stable. Text columns compare case-insensitively; nulls sort last. Default: `n` descending.
- **Basic filter & search:** account (matches `accountRef` or `toAccountRef`), state, type, category (including `UNCATEGORIZED`, the worklist that drives rule writing), date from/to (inclusive), and `q` — case-insensitive substring over raw description, description, comment, externalId, receipt, transferKey.
- **Paging while data arrives (G4):** every page is computed against a pinned snapshot `asOfN`: the journal lines with `n ≤ asOfN` (Transactions = latest line per id among them). When SSE reports a newer `n`, the page shows a "N new · refresh" chip instead of moving rows. **Follow** mode (like `tail -f`): when on, and on page 1 sorted by `n` descending, the page refreshes automatically.
- **State (G5):** all journal lines held in memory (append order = `n` order). Each distinct query (view, asOfN, filters, sort) is filtered+sorted once and cached (small LRU); paging slices the cached result.
- **SSE (G6):** `GET /api/events` streams `event: head` — `{ n, offset, rulesRevision, updatedAt, error, lines, transactions, accounts }` — never row data.
- **URL (G7):** view, sort, page, size, and filters are mirrored in the page's query string (bookmarkable).
- **Money (G8):** amounts formatted exactly from cents. Footer totals per currency over the filtered set, computed server-side in `long`: Transactions view only, and TRANSFER lines are excluded (their legs already carry the amounts). The Journal view shows counts only (versions would double-count).
- **API:** the page's own endpoints are the static files. Everything under `/api/` is **proxied verbatim to trex-gateway** (§5.7) — one rule, no path rewriting, no per-endpoint routing table to drift out of date. trex-web adds no query semantics of its own: a filter or sort it understood differently from the gateway would be a second answer to the same question.
  - `GET /`, `/app.css`, `/app.js` — static page.
  - `GET /api/head` — same JSON as the SSE `head` event.
  - `GET /api/snapshot?view=transactions|journal&sort=col:asc|desc[,col:dir…]&page=1&size=50&asOfN=&account=&state=&type=&from=&to=&q=` → `{ asOfN, rulesRevision, view, page, size, total, rows:[CanonicalEvent...], categories:{n → {category, origin, why, comment}}, totals:[{currency, amount, count}] }`. `size` 1–500 (default 50); `asOfN` absent or above the head → current head. Invalid parameters → `400`.

**Resolution workflow (R-series)**

- **Web tech:** JDK `HttpServer`; one bundled static page (`index.html`, `app.css`, `app.js`, vanilla JS, no framework, no CDN, no build step). Compact, modern style (light/dark via `prefers-color-scheme`). **Responsive (R9):** desktop layout unchanged; below 1024px fixed column widths are released; below 720px each row becomes a record card carrying the same values (checkbox, date, account, amount, description, comment, badges, `n`, actions) — nothing is dropped. Touch input (`pointer: coarse`) gets larger hit targets and 16px form text; `prefers-reduced-motion` disables motion.
- **Refresh:** Server-Sent Events, and there is exactly one stream for the whole service — `GET /api/events`, `event: head`, the payload above (§5.7). It says what moved and never carries rows, so this page treats a frame as a prompt to refetch `/api/ledger`. One frame on connect, then one whenever `n`, the offset, `rulesRevision` or the error changes, plus a `: ping` comment every 15 s. The browser uses `EventSource`; while the stream is down it falls back to polling every 2 s and stops when the stream reconnects. At most 32 concurrent browser clients (`503` beyond), fanned out from one upstream connection.
- **Page:** two lists — HELD and REVIEW (same sets as §2.6; REVIEW rows labelled *ambiguous match* or *potential duplicate*). Each row: date, account, amount (cents formatted exactly), raw description, `n`, comment, badges. No pairing suggestions, no resolved-history view.
- **Actions** (only the existing decisions):
  - `MARK_EXTERNAL` on a HELD/REVIEW row.
  - `CONFIRM_TRANSFER`: select exactly two rows, then "Pair as transfer" — enabled only if amounts are equal and opposite (non-zero), accounts differ and currencies match. The sequencer remains authoritative.
  - `DISMISS_DUP` on a row flagged `POTENTIAL_DUP`.
  - (TODO: "confirm REVIEW" — meaning not yet defined.)
- **Double confirmation:** an action button opens a confirmation dialog stating the exact effect, with an optional comment. Only its Confirm sends the request. The result is shown: `Resolved` (with `n`) or the sequencer's `Rejected` reason. A repeated submit is harmless — the sequencer rejects it.
- **API (resolution half; the browsing endpoints are listed above):**
  - `GET /api/state` → `{ offset, n, updatedAt, error, rulesRevision, held:[CanonicalEvent...], review:[CanonicalEvent...] }` — proxied to trex-gateway's `/api/ledger`.
  - `GET /api/events` → `text/event-stream`, relayed from trex-gateway's stream (§5.7): one upstream connection, fanned out to browser clients.
  - `POST /api/decisions` — proxied to trex-gateway's `/api/decisions` (§5.7), which checks the action against the ledger and forwards to the sequencer. The precondition that used to be enforced only by the page's JavaScript is enforced there, for every consumer.
  - `POST /api/pins`, `POST /api/rules`, `GET /api/proposal` — proxied to trex-gateway (§5.7), which owns the files. trex-web forwards the body unchanged and relays the response, including the `409` when the on-disk revision has moved.
- **Security (no authentication, for now):**
  - Binds `127.0.0.1`. Browsing and deciding now share a process, so the whole service carries the stricter posture: there is no read-only exposure to be had by loosening the bind, and §9 keeps the second-listener option for when there is.
  - Every mutating endpoint — `POST /api/decisions`, `POST /api/pins`, `POST /api/rules` — requires `Content-Type: application/json` **and** header `X-Trex-Admin: 1`, and rejects a request whose `Origin` does not match its `Host` (`403`) — blocks cross-site form posts and simple cross-origin requests (CSRF).
  - **`Origin` is checked here and nowhere else in the chain, and is not forwarded.** It describes the browser's relationship to *this* service; the hop to trex-gateway is a different service on a different port, where a forwarded `Origin` can only ever mismatch. Forwarding it 403s every genuine browser write while proving nothing — and does so invisibly, because `curl` sends no `Origin` and therefore works. `X-Trex-Admin` *is* forwarded, because the gateway requires it of every caller.
  - Page served with `Content-Security-Policy: default-src 'self'`, `X-Content-Type-Options: nosniff`; API responses `Cache-Control: no-store`. The page inserts bank text with `textContent` only (never as HTML).
  - Request body cap 64 KB.
- **Categories:** every row shows its derived category and, on hover, the rule or pin that produced it together with that entry's `comment` (§5.6). Both a pin and a rule are **proposed here and written by trex-gateway** (§5.7) — no snippet is ever handed to the clipboard. The page's job is to show the proposal and its blast radius; the write, its validation and its ordering belong to the file's owner.
- **Config (flags):** `--gateway-url <url>` (default `http://127.0.0.1:8085`), `--port <n>` (default 8090), `--bind <addr>` (default 127.0.0.1). No `--journal`, no `--config` and **no `--sequencer-url`**: it reads no file and knows no other service.

### 5.5 — merged into §5.4

`trex-grid` and `trex-resolver` were one read-only service and one action-taking service so the
table could be exposed differently from the decisions (DECISIONS G10). Both run on loopback in
practice, so that boundary bought nothing and cost a cross-origin write path once the table
needed to write a pin. They are now one service (§5.4). The number is kept rather than
renumbering, so every §5.6 reference in the code and the docs stays valid.

### 5.6 trex-category — master categorisation (shared consumer library)

A pure library, not a service and not a copy per consumer: `trex-gateway` (§5.7) and (phase 1.5) the Firefly egress both call it, so they cannot disagree about what a transaction is. The library stays the one implementation; §5.7 is the one *owner* of the rule files it loads — a distinction that only started to matter when those files became writable. Master level only — fine-grained categorisation is Firefly's job, downstream (see the Firefly note below).

**Resolution chain** (first hit wins), given the latest line for an `externalId` and the `LedgerView`:
1. **Structural `TRANSFER`** — the line is a TRANSFER line, or a leg listed in some TRANSFER's `legIds` (§2.6 `Projection`). Rules never assign or override this: it is a fact of the fold, not an opinion.
2. **A pin** — a rule whose `when` is an `externalId` leaf: the one-off correction for a transaction no sensible rule would catch, and the override when a rule is right about 30 rows and wrong about one. Pins live in their own file, `pins.yaml` (§6), because they are machine-written and order-independent while rules are hand-written and ordered.
3. **The first matching rule** in `categories.yaml`, in file order.
4. **`UNCATEGORIZED`** — nothing matched. Never a guess (§0.6). The uncategorised list is the worklist that drives rule writing, exactly as HELD/REVIEW drove transfer rules.

**Seam.** The evaluator sits behind a `Categorizer` interface, and the YAML rule set is its only implementation in this phase. That is what a different engine would plug into later; §9 records the one anticipated extension.

**`comment` is a field on every rule and every pin**, not a YAML `#` comment. It survives a machine rewrite of `pins.yaml`, which a `#` comment would not, and it reaches the person looking at the transaction: the UI shows it beside the category it explains (§5.4). Rationale that only a maintainer reading the file can see is rationale the person asking "why is this GROCERIES?" never gets.

**Rules** (`categories.yaml`, §6) are **data, not expressions** — no embedded expression language, so a bad rule fails at startup with a pointed message instead of at row 12 000, and nothing evaluates arbitrary code from a config file. A rule is a category plus a `when` tree:
- Composition: `all`, `any`, `not` (nest freely).
- Leaves: `externalId` (a list of ids — the pin form; exact match, never a regex), `match` (regex, case-insensitive, `find` semantics as §3.4), `matchOn` (`raw` — the default, the verbatim field identity hashes — or `description`, the cleaned form, which may evolve), `direction` (`in`/`out`, from the amount's sign), `accounts` (list of refs), `amountMin`/`amountMax` (cents, inclusive, on `|amount|`).

**Result.** `Categorized(category, origin, rule)` where `origin` ∈ {`STRUCTURAL`, `PIN`, `RULE`, `NONE`} and `rule` carries which entry fired, from which file, with its `comment`, so every reader can answer *why* — a rule table nobody can interrogate becomes folklore.

**Purity.** `Categorizer` is deterministic and I/O-free (like trex-core); only loading `categories.yaml` touches the filesystem. Patterns compile once at load. Load-time errors: an undeclared category in a rule, an uncompilable regex, an empty `when`, `amountMin > amountMax`. Load-time warning: a rule wholly shadowed by an earlier one.

**Dry run** (the tuning loop, and the reason this is usable in practice): categorise a whole journal or a parsed CSV and print per-category counts plus the most frequent uncategorised descriptions, highest first. Rules get tuned against real data before anything reaches Firefly — the same idea as the ING dry run in §4.

**Amending the rules is a supported operation, not a hand edit.** `trex-gateway` (§5.7) is the single writer of both `categories.yaml` and `pins.yaml`, and the rules that make an automated write safe live here because they are properties of the rule set, not of the UI:

- **Splice, never serialise.** An amendment edits the file as *text* at a computed line span. The file is not parsed and re-emitted, so `#` comments, blank lines and ordering survive untouched and the git diff is the change itself rather than a reformat. This is what makes machine writes compatible with a file a human also edits.
- **Validate before swap.** The amended text is written to a temp file, loaded through `CategoryRules.load`, and only then renamed over the original. A rule set that would fail at load never replaces one that works — the sequencer's validate-all-then-commit (§3.3), applied to config.
- **The revision is checked first**, before the dry run. A write composed against a rule set that has since moved is answered `409` and nothing else: any coverage number computed for it would describe a state the caller has never seen, and "matches nothing" against rules they do not have is worse than no answer at all.
- **Revision stamp.** `rulesRevision` is a hash of both files' bytes. Every snapshot and every SSE frame carries it, and a write names the revision it was composed against; if the file has moved since, the write is refused (`409`) rather than clobbering a concurrent hand edit.
- **Placement is computed, not guessed.** Because the view holds every line and every compiled rule, a proposed rule's match set is known exactly, along with which existing rule currently owns each row. No collision → append. Collision the new rule should win → insert before the rule it takes from. Collision it should not win → it is redundant and is refused with the reason. First-match-wins (§5.6 chain, step 3) is unchanged; what was judgement becomes arithmetic once the data is in hand.

**Note on the Firefly egress (phase 1.5, not built here):** it is NOT a plain log-mirror — it projects *resolved units* (TRANSFER lines, and transactions whose latest state is EXTERNAL), needs a projection-state table (`external_id → firefly_group_id`), posts via the Firefly API with `apply_rules: true` and `error_if_duplicate_hash`, and carries trex's master category (§5.6) as the **tag** `trex-category:<name>` — never in Firefly's own `category` field, which Firefly's rules own for the fine-grained level (that is the whole point of the split: master here, fine-grained there). Because the category is derived, the projection-state table also records the category last projected, so a `categories.yaml` change re-projects exactly the affected transactions, and reconverges (nuke Firefly = clear projection table, re-project). Spec it separately when built.

### 5.7 trex-gateway — the consumer API (materialized view, rule writer, decisions gateway)

The journal turned into something a reader can use: one fold, categories applied, served as a snapshot and a stream — and the one API a consumer needs. It is a journal follower (reads the journal file), the single writer of `categories.yaml` and `pins.yaml`, and the **decisions gateway** to the sequencer.

**It never writes the journal.** Only the sequencer does, and it remains authoritative over every decision (§3.5): trex-gateway forwards, it does not decide, and a decision the sequencer rejects is rejected. What trex-gateway adds at that seam is the precondition check that has nowhere else to live — it holds the ledger, so it alone can verify a decision against current state *for every consumer*, not just for the one that happens to be a browser running our JavaScript.

It exists because rules became *writable*. While rules were hand-edited, categorisation was a pure function every consumer could run for itself (§5.6), and a service would have added a failure mode to something that had none (DECISIONS W4). A writable rule set changes the question: with two consumers loading the same files on their own schedules, "what is the current rule set" has two answers, and the one that wrote last cannot tell the other. One owner, one reload point, one `rulesRevision` — that is what the service buys, and it is not buyable in-process once the second consumer exists.

- **Owns the fold.** Both of them: the ordered line list that the table pages over, and the latest-line-per-`externalId` ledger that yields HELD/REVIEW. One pass over the journal feeds both, so a reader switching tabs always sees one instant of the journal. Splitting them across processes would restore the double fold §5.5 exists to have removed.
- **Owns the rule files** and applies them, holding a compiled `Categorizer` (§5.6) beside the fold. A category is still never written to the journal (§0.7); materialising means computing it on the way out.
- **Tailing:** as §5.4 previously specified — fold from offset 0 at startup, then `JournalChanges` (§5.1) triggers a read on create/modify/delete (events are hints, never counts; `OVERFLOW` also triggers), with a fallback read every `--poll-ms` (default 10 000) for missed events and filesystems without inotify. A journal that shrinks below the tail offset discards state and refolds from 0. A corrupt line stops tailing and is reported in the snapshot's `error`.
- **Hot reload of the rule files** is required here, not deferred: an amendment that needs a restart is not an amendment. The same `JournalChanges` watcher covers a config directory, so a file edited by hand in an editor takes effect exactly as one written through the API. A reload that fails to load is logged and **discarded** — the running rule set stays in place, because serving the previous categories is always better than serving none.
- **Re-materialising is cheap and total.** A rule change can move any row, so there is no incremental path worth having: recompute categories over the fold and bump `rulesRevision`. At the current scale (≈1 850 lines) this is milliseconds.

**API** (all read endpoints also accept the browsing parameters of §5.4, which are defined once, here):

- `GET /api/snapshot?…` — the paged, sorted, filtered rows with their categories; the payload §5.4 lists under `/api/snapshot`.
- `GET /api/head` — `{ n, offset, updatedAt, error, rulesRevision, lines, transactions, accounts }`.
- `GET /api/ledger` — `{ offset, n, updatedAt, error, rulesRevision, held:[…], review:[…], categories:{…} }` (what the Resolve tab renders).
- `GET /api/events` — SSE. Frames carry **`{ n, offset, rulesRevision, updatedAt, error }` and never row data**: a rule change can touch every row, and pushing ~1.2 MiB per edit to every client to say "something moved" is the wrong trade. Clients refetch what they are showing. One frame on connect, then on any change, plus a heartbeat; at most 32 streams (`503` beyond).
- `GET /api/worklist` — uncategorised grouped by merchant stem, ranked by count and total: `{ stem, count, total, firstSeen, lastSeen, accounts, sampleIds }`. The list that drives rule writing (§5.6 step 4).
- `GET /api/rules` — the rule set as the service holds it: the declared categories, and each rule and pin with its index, category and `comment`. What a client shows before it changes anything.
- `GET /api/proposal?…` — dry-run a candidate rule or pin **without writing**: its match set, the total, how many rows are currently `UNCATEGORIZED`, which existing rules it would take rows from (by index and category), the computed insertion point, and the exact text that would be spliced in. This is what the Categorize tab previews.
- `POST /api/rules` — body `{ category, comment, when, rulesRevision, at? }`. Composes, validates, splices and swaps per §5.6. Returns the stored entry, its final position and the new `rulesRevision`; `409` if the revision has moved, `422` if the rule is redundant.
- `POST /api/pins` — body `{ category, comment, externalIds, rulesRevision }`. Same contract against `pins.yaml`, where placement is trivial because pins match exact ids.
- `DELETE`/`PATCH` on a rule or pin by index, same validate-before-swap discipline. These are what "full edit rights" means: the service may rewrite and remove entries, not only add them.
- `POST /api/decisions` — body `{ action, externalId?, legA?, legB?, comment?, decisionRef? }`. Checks the action against the **current ledger** before forwarding: the target is actually HELD or REVIEW; `DISMISS_DUP` targets a row flagged `POTENTIAL_DUP`; `CONFIRM_TRANSFER` names two distinct rows whose amounts are equal and opposite and non-zero, whose accounts differ and whose currencies match. A failure returns `422` naming the precondition, and nothing reaches the sequencer. Otherwise it builds the sequencer request and relays the response verbatim, including `Rejected` — the sequencer is still the authority, and this check is a courtesy that fails fast, never a second opinion that could disagree with it.

- `GET /api/accounts` — `[{ ref, currency, balanceSource }]`, read from `accounts.yaml` in `--config`. The **one owner** of this fact: the egresses need it to decide whether an account's balances mean anything, and copying it into `hledger.yaml` and `firefly.yaml` would give two files that can disagree about the same account. Same argument as for categories (DECISIONS V1).
- `POST /api/cash` — a hand-entered line on a `declared` account. Exactly one of two shapes, one line per call:
  - a purchase — `{ ref, accountRef, date, amount, description }`, where `amount` is non-zero signed cents;
  - an attestation — `{ ref, accountRef, date, attestedBalance }`, with no `amount` and no `description`.

  **`ref` is the identity.** It is minted by the client once per entry (`MAN-<ULID>`) and becomes the line's `receipt`, so identity is the natural key `nk|accountRef|date|receipt` and no FROZEN code in §2.4 changes. This is forced, not preferred: `occ` is assigned **per batch** (§2.5), so a hand-entered line is always `occ = 0`, and two genuinely different cash purchases sharing account, date, amount and description — entered on different days, therefore different batches — would mint the same content hash and the second would be silently dropped as a duplicate (§3.3 step 5). Losing a real entry without saying so violates §0.6. A re-submitted `ref` is an idempotent retry, exactly as `decisionRef` already is.

  Checked before forwarding, `422` naming the precondition and nothing reaching the sequencer: the account exists and its `balanceSource` is `declared` — **a hand-entered line into a `statement` account is refused**, because it would corrupt a chain the bank is the authority for; a purchase has a non-zero amount and a non-blank description; an attestation has neither. The sequencer stays the authority and this is a fast failure, never a second opinion.
  - `decisionRef` is passed through when supplied and minted as `ui-<UUID>` when not. A caller that supplies its own gets idempotent retries; the page, which has nothing to retry with, gets the old behaviour.
  - After a `Resolved`, trex-gateway reads the journal immediately rather than waiting for the watcher tick, so the SSE frame announcing the new `n` follows the decision instead of trailing it by up to `--poll-ms`.

**Security:** binds `127.0.0.1` only, always. It has no authentication and writes files that decide how every consumer reads the journal; the read-only exposure question belongs to trex-web (§5.4) and §9. Mutating endpoints require `Content-Type: application/json` and `X-Trex-Admin: 1` of every caller, including trex-web. An `Origin` that does not match this service's own `Host` is rejected, which guards the case of a browser reaching the gateway directly; a proxied call arrives with **no** `Origin` at all (§5.4) and is judged on the admin header and the loopback bind alone. The browser-facing CSRF decision belongs to the hop that faces the browser. Request body cap 64 KB.

**Config (flags):** `--journal <path>`, `--config <dir>` (holds `categories.yaml` and `pins.yaml`, §6), `--sequencer-url <url>`, `--port <n>` (default 8085), `--bind <addr>` (default 127.0.0.1), `--poll-ms <n>` (default 10000).

**Consumers:** trex-web (§5.4) today; the Firefly egress (§5.6 note) next, which is the second consumer this service is for — it can project against a named `rulesRevision` instead of loading the files itself and hoping they match what the UI showed. Because reading, deciding and categorising all arrive here, a consumer needs exactly one address and one posture: a script, a CLI or a future app talks to trex-gateway and gets the same precondition checks, the same categories and the same revision the page sees. That is the property that would be lost by letting any consumer reach the sequencer directly — not authority, which the sequencer keeps, but *consistency of the checks on the way in*.

### 5.8 trex-egress-firefly — projecting resolved units into Firefly III

**Not a log mirror.** §5.2 and §5.3 copy journal *lines*; this projects *resolved units*, which is a
different unit and a different failure mode:

| | mirrors (§5.2, §5.3) | projects (§5.8, §5.9) |
|---|---|---|
| unit | one journal line | one resolved transaction |
| TRANSFER legs | copied, for audit | **skipped** — the TRANSFER line replaces them |
| HELD / REVIEW | copied | **withheld** until resolved (§5.8) / posted to suspense (§5.9) |
| categories | absent (§0.7) | attached, and refreshed when rules change |

Emitting a TRANSFER line *and* its two legs double-counts every internal movement, silently — a
wrong total rather than an error. It is the most expensive mistake available in an egress, so the
leg exclusion is asserted against a real-shaped fixture and not left to reasoning.

**A trex-gateway client, not a journal follower.** Categories and `rulesRevision` come from the one
owner (§5.7), so the egress can never project under a rule set the UI never showed.

**Firefly's data shape is Firefly's concern.** Where its model differs from trex's, the egress
adapts; trex does not bend. Two consequences, both load-bearing:

- **Firefly's vocabulary lives only in this module.** `Projection` is the one place that knows what
  an expense account is. trex-core, the journal, trex-category and the gateway never learn the word.
- **The projection is one-way.** Nothing Firefly computes — its `category` field, its rules, its
  auto-created accounts — ever flows back into trex (§0.7). Reading back our own `external_id`, our
  own `trex-category:` tag and our own notes is recovering our own state, not importing an opinion,
  and the line stays drawn exactly there.

**The transaction type is decided by the two accounts' Firefly types, not by trex's
TRANSFER/EXTERNAL classification** (verified against 6.7.3): `asset↔asset` and
`liability↔liability` are a `transfer`, `asset→liability` a `withdrawal` (paying a card or a loan),
`liability→asset` a `deposit`. Firefly rejects a `transfer` that crosses the asset/liability line.
`destination_name` for an ordinary spend is the merchant stem (§5.6), so Firefly auto-creates one
expense account per merchant and its own rules have something to match on.

**The projection cache is an accelerator, not a record.** Everything it holds is recoverable from
Firefly itself — the group id from `external_id`, the category last projected from the
`trex-category:` tag, the journal `n` from `notes`. Deleting it costs requests, never a fact, and
the durability rigour (transactional cursor, backup, migration) goes away with it.

**Re-tagging is read-modify-write, per split.** `PUT` takes the complete transactions array, so a
body built from scratch collapses a group the human split in the UI and destroys the work silently.
The tag is always overwritten; `category_name` only when it still equals the old tag value — when
the evidence is unclear the human wins. A correction made in Firefly is a symptom, not a workflow:
trex already has a pin, which fixes it for every consumer at once.

**Reconvergence has a stated limit.** "Nuke Firefly, clear the cache, re-project" restores
everything *trex* knows. It cannot restore what only Firefly knows — manual categories, splits,
budgets, bills. Those are Firefly's data and Firefly's backup protects them.

**Retry the transient, stop on everything else.** A dropped connection, a 5xx or a 429 is retried
with exponential backoff and jitter (`--retries`, `--retry-base-ms`, `--retry-max-ms`), honouring
`Retry-After` when the instance sends one — a container restart or a rate limit must not end a run
that takes tens of minutes. A **4xx is never retried**: Firefly is saying the request is wrong, and
repeating it changes nothing but the clock.

**A refusal stops the pass, loudly, where it happened.** Counting failures and carrying on turns the
one legible error into a number in a summary, surrounded by hundreds of lines of progress output —
and leaves a half-projected Firefly whose state nobody has stated. The refusal names the
transaction, its account and its date, goes to stderr, and exits non-zero so a scheduled run cannot
report success. Stopping is cheap because the cache records each write as it lands: a rerun resumes
rather than repeats.

**Every account is checked before the first write.** Startup reconciles `firefly.yaml` against the
instance, but the journal moves underneath it — ingesting a statement for a new account gives the
next pass a ref the file has never heard of. Found at transaction 900 that aborts with 899 already
posted; found in a preflight over the whole unit list it costs nothing, and it names every unmapped
ref at once rather than one per run.

**`external_id` is part of Firefly's duplicate hash** (measured on 6.7.3: two transactions identical
in every other field, differing only in `external_id`, are both accepted). So a duplicate rejection
can only ever name the group holding *that same trex row* — two distinct rows cannot collide,
because `external_id` is trex's identity, and a transaction created by hand in the UI carries none
of ours. That is what makes parsing the group id straight out of
`422 {"message":"Duplicate of transaction #N."}` safe rather than a guess, and it is why the
rejection is a recovery path (the one a lost cache takes) instead of an error.

**Config:** `--gateway-url`, `--firefly-url`, `--accounts <firefly.yaml>` (§6), `--once`,
`--dry-run`, `--verify`, `--retries`, `--retry-base-ms`, `--retry-max-ms`. Token from
`FIREFLY_TOKEN` in the environment only — a flag lands in `ps` and in shell history, and §6 already
bars it from config.

---

### 5.9 trex-egress-hledger — regenerating a plain-text ledger

The same projection aimed at a file instead of a service, and the difference in target removes
almost all of §5.8's machinery: **the file is regenerated whole on every run**, so there is no
cache, no idempotency, no cursor and no drift. A correction made in the output is overwritten on
the next run, which is the point — it forces every correction back into trex, where the other
consumers can see it. Written through a temp file and renamed, so a reader never sees half a
journal and a failed run leaves the previous one intact.

**It projects completeness, not resolved units.** This is the one place it departs from §5.8, and
the reason is balance assertions: the bank's running balance accounts for every movement, so a file
that omits the HELD rows cannot assert against it. Nothing is withheld — an undecided row posts its
known side and balances against `assets:unresolved`, and `hledger balance assets:unresolved` is a
live list of what still needs deciding, returning to zero when the last one is resolved.

**Balance assertions are the reason this egress is worth having.** trex carries the bank's own
running balance on every line, which §0.1 reserves for provenance and *reconciliation*; asserting it
is exactly that. hledger then verifies every account against what the bank said and fails at the
transaction where it first stops being true — a check no other consumer performs, at the cost of one
file rewrite.

**Only a day's closing balance is asserted, and finding it is not trivial.** A journal line's `n` is
ingest order, and ingest order is CSV row order, which is the bank's choice: BankWest exports newest
first, ING exports newest first *and* splits deposits from withdrawals into separate files, so one
account arrives as two interleaved runs. Nothing in the journal records any of this, and taking the
highest `n` on a day as that day's close is therefore wrong for most accounts — wrong invisibly,
because it picks a real balance off a real row, just not the last one.

The balance column settles it with no per-bank configuration, because it is a running balance for
the *account* and not for the file: a row's balance minus its own amount is the balance of whatever
came immediately before it. Exactly one row on a day is a **tail** — its balance is no other row's
"before" — and that tail is the day's close. Two failure shapes, with different costs:

- **More than one tail:** the day's lines fall into disconnected runs, so trex is missing a line the
  bank counted. The close is not knowable and **nothing is asserted** — a guess would assert a
  figure wrong by the amount of the line we do not have. The count is reported per account instead.
- **A unique tail but an ambiguous walk back from it** (two rows on the day happen to share a
  balance): the close is still known and still asserted. Only the printed order of that day falls
  back to `n`. Intra-day order was never knowable from a statement; the closing balance always was.

**Opening balances are derived forward, the opposite of §5.8's, on purpose.** trex's history starts
mid-life, so without an opening every assertion fails at the first transaction and the file says
nothing about the data. §5.8 anchors backward, from the most recent balance, so that *today's*
figure is right even when history is missing. Here the job is the opposite — to *find* the missing
history — so the anchor is the earliest day whose close is knowable, less everything that moved up
to it. That makes the early assertions hold and the first one after a gap fail, which names the gap;
anchoring backward would instead fail every assertion before it, which names nothing. The
disagreement between the two anchors is reported as the account's `gap`.

**The account tree is the reporting model**, which is why `hledger.yaml` (§6) is the whole
configuration and carries no type field: `hledger balance liabilities` works because the name says
so. The category becomes part of the contra account (`expenses:groceries:woolworths`) rather than a
tag, so a category report needs no extra machinery. **The top level follows the category, not the
sign:** a refund is money coming in but is not income, and belongs in the expense account it
reverses as a negative amount. Deciding by sign files every returned purchase under `income:` and
overstates both sides of every report, so the income categories are declared.

**A `declared` account: the plug, and deliberately no assertion.** Recorded cash purchases render
as ordinary transactions. At each `ATTESTATION` the egress emits one more — the difference between
the running total it derived and the figure that was stated — posted to that account's `cashPlug`
target (§6):

```
2026-09-12 * Market stall - vegetables
    assets:cash:ron                       -$40.00
    expenses:groceries:market-stall        $40.00

2026-09-25 * Cash attestation
    assets:cash:ron                       $160.00
    expenses:cash-withdraw:ron           -$160.00
```

**No balance assertion is written, at the attestation or anywhere else on the account**, and the
reason is the whole point of this egress. The plug is computed *as* `attested − derived`, so
appending `= attested` would be satisfied by construction — `X + (A − X) == A` for every input. It
could never fail. A bank assertion is worth running because the balance column and the amounts are
reported independently, so a missing row breaks it; a `declared` account has no independent second
source, so **no assertion on it can ever verify anything**. Emitting one would dilute the only claim
this egress makes that is worth making: that a green `hledger check` means the banks agree. The
assertion set stays entirely bank-backed.

The plug keeps all of its value regardless — it is the *number* that was wanted, not the checkmark.
Pointed at the cash-withdraw node, itemising part of a withdrawal **re-labels** it rather than
adding to it, so `$500` out of the bank with `$40` itemised reports `$440` of cash-withdraw and
`$40` of groceries rather than `$540` of spending, and the remainder reads as **cash spent but never
itemised**. Pointing `cashPlug` anywhere else double-counts every cash purchase against the
withdrawal that funded it.

**Config:** `--gateway-url`, `--out <file>`, `--accounts <hledger.yaml>` (§6), `--no-assert`
(for diagnosing a file that will not load), `--stdout`.

---

---

## 6. Config

All config files are **YAML** (`.yaml`), bound to records by the Jackson YAML mapper in `trex-journal` (§1) — there is no hand-written config parser. Binding is strict in both directions: an **unknown key** is a startup error (as the hand-written parser enforced) and a **duplicate key** is a startup error rather than last-wins. Value checks (currency set, port range, required fields) are hand-written next to the records, with messages naming the file and the key.

Config is YAML; **the journal is JSONL and stays JSONL** (§3.1) — the two never meet. Three YAML properties that matter here and are checked at load: unquoted `no`/`yes`/`on`/`off` are booleans in YAML 1.1, so account refs, category names and other identifiers are validated as strings and quoted by convention in the shipped files; indentation carries structure, so a misindented block is a bind error, not a silent reparent; anchors and merge keys are allowed and are useful for sharing predicate fragments between category rules.

Separate files, not one: they change on different schedules, and the registry is edited far more often than the rest.

- `accounts.yaml` — the registry (the spine): per account `ref`, `currency` (`AUD|USD|INR`) and **`balanceSource`** (`statement|declared`, required — a missing or unknown value is a startup error). The sequencer uses it to validate `accountRef`, stamp `currency`, and decide whether a candidate must carry a `balance` (§3.3 step 2).

  `balanceSource` is a fact about **the account itself**, which is why it belongs here beside `currency` and not in a consumer's file: it says where this account's observed balances come from (§0.1), and therefore whether a gap in the chain is a fault or the normal shape of the data. `statement` means every movement is accompanied by an observation and `Σ amount == closing − opening` must hold. `declared` means observations arrive only when a person supplies one (§2.3 `ATTESTATION`), so the chain has deliberate gaps and the tripwire reports them instead of failing (§7 test 6).

  It still records no source type — that is bound per run by `--source-type` (§4) — and **no egress fields**: what a `ref` is called in Firefly belongs to `firefly.yaml` (§5.8), because the sequencer has no business knowing what a Firefly account is, and a second egress would otherwise add another column to the spine. `balanceSource` does not reopen that rule, and the test for whether a future field belongs here is the same one it passes: **is this true of the account regardless of who is reading?** A currency is. Where balances come from is. A Firefly name is not.
- `firefly.yaml` — read by the Firefly egress and nothing else (§5.8): per `ref`, the Firefly account `name` and `type` (`asset|liability`). Keyed by **name, not by Firefly's numeric id** — a rebuilt instance changes every id and no name, so the mapping survives a restore; the id is resolved at startup and held only in the ephemeral cache. `type` is not decoration: Firefly rejects a `transfer` that crosses between asset and liability, so the type decides whether a movement is a `transfer`, a `withdrawal` or a `deposit`. A rename in Firefly is a hard stop at startup, with the unmapped account suggested by name, never a silent post into the wrong account.
- `hledger.yaml` — read by the hledger egress and nothing else (§5.9), and optional: per `ref`, the
  full hledger account name (`liabilities:bankwest:credit-card`). No type field, because in
  plain-text accounting the name *is* the type. Also `income:` — which master categories mean money
  earned, so a refund stays a negative expense instead of becoming income — plus `unresolved:` and
  `equity:` account names. Separate from `firefly.yaml` because that one exists so Firefly can pick
  a transaction *type* and this one exists because hledger needs a *name*; sharing them would put
  one target's vocabulary in another target's config. An unmapped ref defaults to `assets:<ref>`,
  which is right for most accounts and wrong for every card and loan, so the run warns.
  It also carries **`cashPlug:`** — per `declared` account, the account the unrecorded remainder is posted to when an `ATTESTATION` is projected (§5.9). Normally the cash-withdraw expense node, so that itemising part of a withdrawal *re-labels* it rather than adding to it; pointing it anywhere else double-counts every cash purchase against the withdrawal that funded it.
- `transfers.yaml` — allowlist regexes (case-insensitive, matched against `rawDescription`), `windowDays` (**required**, no default).
- `sequencer.yaml` — `bindHost` (optional, default `127.0.0.1`), `bindPort` (required), `journal: {source, target}`. (No fsync option — always fsync, §3.1.)
- `categories.yaml` — read by **journal consumers only** (§5.6); the sequencer never loads it (§0.7). Two keys:
  - `categories` — the declared master categories. Starting set, tuned from dry runs: `SALARY`, `INTEREST_EARNED`, `INTEREST_PAID`, `GROCERIES`, `BILLS`, `TAXES`, `SAVINGS`, `DISCRETIONARY`. Adding one is a config edit, never a code change. `TRANSFER` and `UNCATEGORIZED` are **reserved** (§5.6) and must not be declared or assigned by a rule.
  - `rules` — ordered; first match wins. Each is `category`, an optional `comment` (a field, not a `#` comment) and a `when` tree (§5.6).
- `pins.yaml` — the same mechanism, different owner: `pins:` entries of `{category, comment, when: {externalId: […]}}`, evaluated before every rule (§5.6).
- **Both files have one writer: `trex-gateway` (§5.7)** — and you, in an editor. They remain two files because they are two different things: `categories.yaml` is ordered and general, `pins.yaml` is unordered and exact. But the split is no longer a fence against machine writes.
  - The two facts that once forced that fence still hold and are simply handled: the YAML mapper cannot round-trip `#` comments, so an amendment **splices text** rather than re-serialising, leaving the notes explaining why `\bfees?\b` is anchored or why `INSURANCE` precedes `BILLS` byte-identical; and rule order is a decision, so the insertion point is **computed from the candidate's match set against the loaded rules** (§5.6) rather than assumed. Appending blindly was never safe; appending is simply no longer the only thing on offer.
  - `comment` is a field on every rule and every pin (§5.6), so rationale written by the service survives in data rather than in a `#` line no writer can reproduce.
  - Both files are git-tracked: the diff is the review — every over-broad pattern found so far (`coffee` contains "fee", `Gregory Hill` contains "rego") was caught by reading one — and the pair of them at a commit is the as-of answer to "what did we call this in March?" (§0.7). An automated write makes the diff *more* important, not less, which is why nothing is written without the preview that shows it first.
  ```yaml
  # categories.yaml — yours
  categories: [SALARY, INTEREST_EARNED, GROCERIES, BILLS, TAXES, SAVINGS, DISCRETIONARY]
  rules:
    - category: SALARY
      comment: "employer deposits; direction guards against a salary reversal"
      when:
        all:
          - direction: in
          - match: "salary|payroll"
    - category: GROCERIES
      when:
        match: "woolworths|coles|aldi|iga"

  # pins.yaml — written by §5.7 (and by you), one entry per correction
  pins:
    - category: TAXES
      comment: "2026-09-24 from the worklist: ATO instalment, not a bank fee"
      when: {externalId: ["9e546cc0260ead1e"]}
  ```
- Follower config — `journalPath`, sink path, `pollSeconds` = fallback wake interval (command-line flags: `--journal`, `--archive`/`--db`, `--poll-seconds`, `--once`).
- Resolver config — command-line flags (§5.4).
- Firefly API token: env var / systemd credential, **never** in config or repo.

A leftover `*.toml` beside its `.yaml` replacement is a startup error naming both files: the old file is silently ignored otherwise, and a stale registry is the kind of thing that is only noticed after an ingest.

---

## 7. Testing (generate alongside code)

Golden-file harness + JUnit 5. The sequencer takes an injected `java.time.Clock`; tests that compare journal bytes use a fixed `Clock` and compare full bytes, `ingestedAt` included. Required, mapping to spec §16 assertions:
1. **Determinism:** identical `Candidate`/`CanonicalEvent` output every run (exclude `n`, `ingestedAt` from comparison — they're processing artifacts). Stage 1: hand-built `Candidate` fixtures; stage 5: ING slice golden file; stage 14: BankWest and CommBank slice golden files; stage 15: the CommBank PDF slice, which must also produce ids identical to the CSV rows it overlaps. (The feed types when they exist.)
2. **Identity uniqueness:** distinct `external_id` == row count on natural-key data; unique within occurrence groups on content-hash data.
3. **Sign correctness:** known debit/credit (stage 1 fixtures; stage 5 ING columns).
4. **Batch idempotency:** POST batch A, then A again → second all `DroppedDuplicate`; nothing new appended.
5. **Split-batch transfer:** legs in two separate batches → resolve to one `TRF-` id, no duplicate.
6. **Reconciliation:** per account, order-independent, over leg lines (TRANSFER lines excluded), first line per `external_id`: each leg links `prev = balance − amount` → `balance`; opening = the `prev` that is no leg's `balance`; closing = the `balance` that is no leg's `prev`; exactly one opening and one closing required (else "unreconcilable" — never guess); assert `Σ amount == closing − opening`, exact `long`-cent equality, no epsilon/scale fuzz.
   **Scope: `statement` accounts only.** A `declared` account (§6) has deliberate gaps, so it reconciles **between consecutive `ATTESTATION` lines** and reports the **total gap** instead of a pass/fail — that figure is the value spent and never recorded, which is information rather than a fault. The report distinguishes three states, `RECONCILED | BROKEN | DECLARED`, and `ok` means **no account is BROKEN**. Conflating "declared" with "broken" would leave the tripwire permanently red and therefore useless, which is the one outcome this test exists to prevent; a test asserts `ok` still goes false when a `statement` account genuinely breaks.
7. **Recovery fold:** append N, restart (fold), in-memory state (firstLine/latest/held/review/highWaterN) matches pre-restart.
8. **Follower resume:** kill follower mid-stream, restart → resumes at persisted offset, no gap/dup.
9. **Materialize bit-identity:** `source != target` → target byte-identical (hash match), offsets still valid, torn tail truncated.
10. **gzip transparency:** the four paths ({plain,gzip} in × {plain,gzip} out) all round-trip; a gzipped POST and a plain POST of the same file yield identical journal bytes (gzip is wire-only; fixed `Clock`); an over-cap decompressed body returns `413`.
11. **Transfer projection unit:** legs in two batches → 4 journal lines (leg A `HELD`, leg B `MATCHED`, leg A re-appended `MATCHED`, TRANSFER); legs in one batch → 3 lines. The projectable-unit selector returns only the TRANSFER, never the legs (no double-count).
12. **allOrNone:** a batch with one bad row → `allOrNone:true` appends nothing (`REJECTED`); `allOrNone:false` commits the good rows (`PARTIAL`) and reports the bad one. Neither half-writes; a fixed resubmit re-dedups (no doubles).
13. **Day-atomic client:** a large ING file split by the client (whole-day calls) yields a byte-identical journal (fixed `Clock`) to a single-call ingest of the same file; a deliberately mid-day split is shown to mis-number occ (guard/negative test).
14. **State re-fold:** after a HELD leg is matched and another is resolved by decision, restart-fold reproduces the latest state for every `external_id` and identical held/review sets — from journal lines alone.
15. **rawDescription hashing:** changing the `description` *cleaning* logic leaves every `external_id` unchanged (identity hashes `rawDescription`, not the cleaned form).
16. **Categorisation is derived:** with a journal fixed, changing `categories.yaml` changes what the readers report and leaves the journal byte-identical (sha256 before/after, same `n`). Structural `TRANSFER` wins over any rule; an override wins over the rules; no match yields `UNCATEGORIZED`; first-match-wins follows file order; the same input always yields the same result and the reported firing rule.
17. **A category costs no journal line:** categorising, recategorising and pinning leave the journal byte-identical and `n` unchanged; a pin beats a general rule; structural `TRANSFER` beats a pin; `POST /decisions` has no category action.
18. **An amendment preserves the file:** splicing a rule into a `categories.yaml` carrying `#` comments, blank lines and a deliberate order leaves every other byte identical (diff is exactly the inserted span) and the comments intact. A rewrite and a delete are held to the same standard on the lines they do not touch.
19. **Validate before swap:** an amendment that would not load — undeclared category, uncompilable regex, empty `when` — leaves the file on disk unchanged and the running rule set in place, and returns the load error naming the entry. A write composed against a stale `rulesRevision` returns `409` and changes nothing.
20. **Placement is computed:** a rule colliding with nothing is appended; one that must beat rule *k* lands before *k* and the fold proves it now wins those rows; one wholly shadowed by an earlier rule is refused as redundant with that rule named. Against the shipped rule set, a `COSTCO GAS` rule and an `amazon` rule each land where the collision analysis says.
21. **The view is the single owner:** with trex-gateway running, a rule written through the API and the same rule hand-edited into the file produce identical materialisations and the same `rulesRevision`; a failed reload keeps serving the previous rule set; an SSE frame carries no row data.
22. **The decisions gateway checks before it forwards:** `CONFIRM_TRANSFER` on two rows with mismatched currencies, equal-signed amounts, the same account, or a row that is not HELD/REVIEW returns `422` and the journal is byte-identical — the sequencer is never called. A valid decision is relayed and its `Rejected` response is passed through unchanged, proving the check is a fast failure and not a second authority. A supplied `decisionRef` reaches the sequencer verbatim, so a retried call is idempotent.
23. **The projection excludes legs:** on a real-shaped journal, no row whose id appears in some
    TRANSFER's `legIds` is projected, and the unit count is the TRANSFER count plus the EXTERNAL
    count. The guard against silently double-counting every internal movement (§5.8, §5.9).
24. **The type follows the accounts, not the classification** (§5.8): `asset→asset` and
    `liability→liability` project as a `transfer`, `asset→liability` as a `withdrawal`,
    `liability→asset` as a `deposit`. A re-tag that changes the category clears the stale
    `category_id` as well as the tag, because Firefly keeps the old category otherwise and the
    disagreement then looks exactly like a hand edit.
25. **The day closes where the bank says it does** (§5.9): with an account ingested newest-first,
    the asserted balance is the day's chronological close and not the highest `n`; with one
    ingested oldest-first, the same code reaches the same answer with no configuration. A day whose
    lines fall into disconnected runs carries **no** assertion; a day with a unique tail but an
    ambiguous middle still carries one.
26. **Transient is retried, refusal is terminal** (§5.8): two 503s then a 200 is one transaction
    posted in three attempts; a 503 on every attempt raises after exactly `--retries` tries; a 422
    is posted **once** and stops the pass with the transaction, its account and Firefly's own
    message in the error. An unmapped account stops the pass with **nothing written** — asserted
    with the unmapped row second, so a preflight is distinguishable from a crash on the first post
    — and names every unmapped ref, not just the first.
27. **The generated ledger validates itself** (§5.9): `hledger check accounts ordereddates
    assertions` passes over a file generated from a real journal. This is the one test where the
    checker is not ours, and it is checking trex's own numbers against the banks'.
28. **`balanceSource` is declared, never inferred** (§6): an `accounts.yaml` entry missing it, or
    carrying an unknown value, is a **startup error naming the account** — the same posture as an
    unsupported currency. Nothing falls back to a default, because a wrong guess here decides
    whether a broken chain is reported as a fault or as normal.
29. **Balance is required exactly where it means something** (§3.3 step 2): a purchase on a
    `declared` account is accepted with no `balance` and writes `0`; the identical candidate on a
    `statement` account is `Rejected`. An `ATTESTATION` is accepted on a `declared` account and
    `Rejected` on a `statement` one. An `ATTESTATION` of **$0** round-trips and is not confused with
    "no balance" — the case that rules out a sentinel value.
30. **A hand-entered line cannot be silently lost** (§5.7): two purchases identical in account, date,
    amount and description but with different `ref`s, submitted in **separate batches**, produce two
    `external_id`s and two lines. The same `ref` twice produces one. A re-submitted `ATTESTATION`
    with a *different* stated balance raises `POTENTIAL_DUP` rather than being dropped.
31. **The tripwire keeps working** (§7 test 6): `/reconcile` reports `DECLARED` with the correct gap
    for a cash account while `ok` stays **true**, and `ok` goes **false** the moment a `statement`
    account genuinely breaks. The third state must not become a way to hide a real break.
32. **No assertion is ever written for a `declared` account** (§5.9): the generated file's assertion
    count equals the count contributed by `statement` accounts alone, including at an attestation,
    where one would pass by construction. The plug lands in the configured `cashPlug` account, and
    `$500` withdrawn with `$40` itemised reports `$440` + `$40`, not `$540`.

---

## 8. Generation order (for Claude Code)

> **Historical order, not the current topology.** Steps 1–16 record how this was built, including
> stages that have since been merged or split away (`trex-grid` and `trex-resolver` became §5.4 at
> step 16; §5.7 split out at step 19). For what exists now, read §1 and §5. This list is kept
> because the order of discovery explains several decisions that would otherwise look arbitrary.

Build bottom-up; each stage compiles and tests green before the next.
1. **trex-core:** records, enums, sealed types, `Ids`, `assignOcc` (+ tests 1–3 on hand-built fixtures).
2. **Journal** (JSONL writer/reader, framing, materialize/recover, fold) in trex-sequencer (+ tests 7, 9).
3. **Ingress pipeline + matcher/state rules + decision logic** (+ tests 4, 5, 6, 11, 14).
4. **HTTP API** (`/candidates` with `allOrNone`, `/head`, `/held`, `/review`, `/decisions` — all fully implemented; gzip §3.6) (+ tests 10, 12).
5. **trex-ingress** (`ing-csv`) against a real ING slice (whole-file validation, day-atomic batching, gzip) (+ ING parts of tests 1, 3; test 13).
6. **trex-egress-archive**, then **trex-egress-sqlite** (+ test 8).
7. **Extract shared components:** pure fold (`Ledger`, `LedgerView`, `Projection`, `Reconciliation`) → trex-core; `Json` mapper + `FramedReader` → trex-journal; sequencer, ingress and followers switch to them (no behavior change; all existing tests stay green).
8. **trex-resolver** (§5.4) (+ tail/refold, API contract, CSRF guard, end-to-end decision round trip through an in-process sequencer); then extract `trex-web` from it.
9. **trex-grid** (§5.5) (+ views, filters, sort/tie-break, pinned paging, totals, SSE head events).
10. **Config to YAML** (§6): Jackson YAML mapper in trex-journal, records + strict binding, delete the hand-written TOML parser, convert the shipped config files. No behaviour change — the existing config tests carry over to the renamed files.
11. **trex-category** (§5.6): rule records, `Categorizer` + evaluator, loader with its load-time errors, dry run (+ test 16).
12. **Consumer surfaces:** grid category column/filter, resolver category display and pin snippet (§5.4, §5.5) (+ test 17).
13. **Source footprint** (§1 dependency policy): picocli for the CLIs. (The CSV library swap was evaluated and rejected — see §1.)
14. **More source types** (§4): `bw-csv` (BankWest: positive debits, pending-row skipping) and `cba-csv` (CommBank: header-less, pre-signed amounts), each with its own golden file (+ test 1 for both).
15. **`cba-pdf`** (§4): PDFBox extraction, frozen normalisation, page-furniture rule, golden file, and the cross-source test that a PDF row and the CSV row for the same transaction mint the same id.
16. **Merge the web services** (§5.4): `trex-grid` + `trex-resolver` + the `trex-web` library become one `trex-web` service — no new features, every existing test green against the merged service.
17. **Pins in their own file** (§5.6, §6): `pins.yaml`, `comment` as a field on rules and pins, split loading validated against the declared categories. Prerequisite for everything below, and Firefly-independent.
18. **`Merchant.stem`** (§5.6): lift the merchant-stem logic out of `DryRunTest` into `trex-category`, with tests for the real truncated and padded shapes (`COM*HolyFamilyCatho`, `SQ *CAMPBELLTOWN INDOO`, `AMAZON AU RETAIL   SYDNEY`). The worklist cannot group without it.
19. **trex-gateway** (§5.7): new module. Move the fold, the browsing query engine (`GridIndex`/`GridQuery`), the ledger view and the `Categorizer` out of trex-web into it; expose `snapshot`/`head`/`ledger`/`events`. No new features — the same answers, computed one hop away (+ every existing grid and resolver test, re-pointed).
20. **The decisions gateway** (§5.7): `POST /api/decisions` with its ledger preconditions — the checks lifted out of the page's JavaScript, where they only ever protected one consumer — plus `decisionRef` pass-through and the immediate re-read after a `Resolved` (+ test 22).
21. **trex-web becomes a client** (§5.4): delete its fold, its config loading and its sequencer client; proxy every data and action endpoint to trex-gateway and relay the SSE. Last-good-snapshot cache so a trex-gateway restart degrades to stale-and-labelled, not blank.
22. **The rule writer** (§5.6, §5.7): splice, validate-before-swap, `rulesRevision`, computed placement, hot reload, and the `proposal` dry-run endpoint. Write path before UI, so the contract is pinned by tests rather than by a button.
23. **The Categorize tab** (§5.4): worklist with its evidence columns, coverage and collision preview, and one-click apply for both rules and pins. No clipboard anywhere in the loop.
24. **The Firefly egress** (§5.8): `sinceN` on the gateway, the pure projection, the account map, the ephemeral cache, and the read-modify-write re-tag. Verified against a live 6.7.3 — the transaction-type matrix is not derivable from the docs (+ tests 23, 24).
25. **The hledger egress** (§5.9): the pure renderer, the balance-column chronology, forward-anchored openings, and `hledger.yaml`. Deliberately after §5.8, because it is the cheap sidekick that checks the expensive one: a file rewrite that any bank statement can be held up against (+ tests 25, 26).
26. **Cash accounts** (§0.1, §2.3, §5.7, §5.9): `balanceSource` in the registry, the `ATTESTATION` line kind, the `/reconcile` third state, `GET /api/accounts`, `POST /api/cash`, the hledger plug, and the entry form. Deliberately last: it is the first data in the journal that no bank can corroborate, so everything that *can* be corroborated is built and proven first (+ tests 28–32).

Each component is small and single-purpose; keep trex-core free of any I/O so it stays exhaustively testable. Lean on sealed types + pattern-matching `switch` so extension (new bank, new tier, new state) surfaces every impact site at compile time.

---

## 9. Explicitly out of scope here (later phases)

Multi-currency **logic** (populating `foreignAmount`, cross-currency transfer matching, base-currency views) — the `foreignAmount`/`foreignCurrency` fields exist as nullable superset but stay null/unused in phase 1; CDR and bank-sync feed source types in trex-ingress (the CDR path is what an account with no CSV export needs); a per-account `sourceTypes` allowlist in the registry, enforced by the sequencer (add with the second source type); the Firefly egress follower; resolver TODOs (authentication, "confirm REVIEW" action, pairing suggestions, resolved history); "keep-both" and "MAN-" decisions; storing the conflicting balance of a `POTENTIAL_DUP` (revisit); tier T2 and text corroboration; an `expr` leaf inside a category rule's `when` (§5.6) if the predicate tree ever proves too weak — CEL preferred over JEXL there, because it type-checks at load and cannot side-effect, which keeps the "bad rule fails at startup" property; a **second listener** on §5.4 so the read-only table can be exposed on another interface while decisions and config writes stay on loopback — the boundary the grid/resolver split used to provide (DECISIONS G10, W1) — note that trex-gateway (§5.7) is now a genuinely read-only-to-the-journal service, so this is a smaller step than it was; rule-health reports (hit count per rule, rules that never fire, pins matching nothing, merchants pinned often enough to deserve a rule); a machine-written `overrides.yaml` if pins ever outgrow hand editing (still consumer-side, still no journal line); an **immutable** `extras` map on `Candidate`/`CanonicalEvent`, stamped once at ingest and never updated, for fields trex does not model (CDR counterparty details, bank reference codes) — sorted keys for byte-stability, never in identity, and never a home for mutable annotations (§0.7); a recomputed category table in the SQLite mirror (a derived value must not be frozen into a log mirror, §0.7) and category totals in the grid footer; group commit; concurrency beyond single-writer; DuckDB/Postgres projections. All are additive at the edges and do not change trex-core's contracts.

**Startup prerequisite (phase 1):** the sequencer loads the account registry (`accounts.yaml`) at boot and uses it to (a) validate `accountRef` on every candidate, (b) stamp `currency` and hold the Firefly account id. It has no bank-specific behavior. An unknown `accountRef` is a hard `Rejected`, never an auto-created account.
