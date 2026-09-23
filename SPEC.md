# trex — Transaction Sequencer: Java 25 Code Plan

A build spec for four components, pitched for Claude Code generation:
1. **trex** — the transaction sequencer service (the journal core)
2. **The ingress client** — one CLI, one parser per source type (starter: ING CSV) → candidates → trex
3. **Two sample egress followers** — `toArchive` and `toSQLiteWAL`
4. **The manual resolver** — an admin web service (journal follower + UI) for the HELD/REVIEW workflow
5. **The grid** — a read-only, compact, live table of the journal (not a dashboard: dashboards belong to Firefly/Grafana)

Design authority is `firefly-ingest-spec-v2.md` + the conversation's later decisions. This document is the *how to build it*; that one is the *why*. Where they conflict, the invariants in §0 win. Pre-implementation review decisions are folded into this document; `DECISIONS.md` records the rationale (B-numbers and T refer to it).

---

## 0. Non-negotiable invariants (every component honors these)

1. **`external_id` is the sole identity of a transaction.** Natural key (`accountRef|date|receipt`) ▸ else content hash `sha256(accountRef|date|amount|rawDescription|occ)[:16]` — hashes the **verbatim** `rawDescription`, never the cleaned `description`, so description cleanup stays soft and can evolve without shifting ids. **`balance` is never in identity *or* transaction semantics** — it is provenance/reconciliation data only (see §2.2 field doc and §5). An `external_id` may appear on several journal lines (versions, §3.2); it is not a line key.
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
    trex-web/         # shared web-follower plumbing: journal watcher, SSE, static serving, security headers
      pom.xml
    trex-category/    # shared consumer library: category rules (categories.yaml) + evaluator (§5.6)
      pom.xml
    trex-resolver/    # manual resolver admin service (§5.4)
      pom.xml
    trex-grid/        # read-only journal grid (§5.5)
      pom.xml
  ```
  Parent pom pins JDK 25 (`<maven.compiler.release>25</maven.compiler.release>`), lists the ten `<modules>`, and centralizes versions in `<dependencyManagement>`. Each service/adapter module is packaged as a runnable jar via `maven-shade-plugin` (or `maven-assembly-plugin`) with its `Main-Class`. Everything that reads the journal uses `trex-journal` (one framing/corruption rule set) and the fold in `trex-core` (one definition of current state, HELD and REVIEW).
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
    long balance,             // running balance (cents). PROVENANCE/RECONCILIATION ONLY — never identity, never transaction semantics
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
    long balance,             // cents. PROVENANCE/RECONCILIATION ONLY — never identity, never transaction semantics. TRANSFER: 0
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
public enum TypeHint   { WITHDRAWAL, DEPOSIT, TRANSFER }
public enum Confidence { EXACT, HIGH, REVIEW }
public enum Flag       { POTENTIAL_DUP }
public enum EventState { HELD, MATCHED, REVIEW, EXTERNAL }

// Identity tier — sealed so the id function is exhaustive & centralized
public sealed interface IdentityStrategy permits NaturalKey, ContentHash {}
public record NaturalKey(String accountRef, String receipt) implements IdentityStrategy {}
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
2. VALIDATE each (accountRef known via registry? amount/date/balance present & parseable?).
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

Separate CLI program; talks to trex over HTTP. Demonstrates the candidate contract. **All source-specific behavior (banks, feeds) lives in trex-ingress**; the sequencer has none. One module holds every source type: the CLI, HTTP client, gzip and day batching are shared (package `trex.ingress`), and each source type is one parser in its own sub-package (`trex.ingress.ing`, …). `ing-csv` was the starter; `bw-csv` (BankWest), `cba-csv` and `cba-pdf` (CommBank) followed; CDR and feed source types follow the same model in a later phase.
```
Usage: trex-ingress --source-type <type> --account <accountRef> --sequencer-url http://trex:PORT
                    [--batch-rows N] [--no-gzip] <source>
```
- **`--source-type`** (required, no default) selects the parser and is stamped into every candidate's `sourceType`. It is the only binding between an input and its parser: the registry does not record one (§6), because one account may arrive through several source types. An unknown type is a usage error. Known: `ing-csv`, `bw-csv`, `cba-csv`, `cba-pdf`.
- **`<source>`** is the instance being read; for the CSV and PDF types, the statement file.
- **`--sequencer-url`** matches the resolver's flag (§5.4).
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

### 5.4 trex-resolver — manual resolver (admin service)
An admin web service for the resolution workflow. It is a journal follower (reads the journal file) and an API client (acts only through the sequencer's `POST /decisions`). It never writes the journal.
- **State:** none persisted. On startup it folds the journal from offset 0 into a `Ledger` (§2.6), then tails it, publishing a `LedgerView` snapshot. Tailing is event-driven: `JournalChanges` (§5.1) triggers a read as soon as the journal file is created/modified/deleted (events are hints, never counts; `OVERFLOW` also triggers a read). A fallback read runs every `--poll-ms` (default 10 000) for missed events and filesystems without inotify (NFS, some FUSE/bind mounts). If the journal file shrinks below the tail offset (e.g. a materialize overwrote it), it discards its state and refolds from 0. A corrupt line stops tailing and is reported on the page.
- **Loop:** user acts → resolver calls `POST /decisions` → sequencer appends lines → resolver tails them → page refreshes. The page reflects the journal, never an optimistic local change.
- **Web tech:** JDK `HttpServer`; one bundled static page (`index.html`, `app.css`, `app.js`, vanilla JS, no framework, no CDN, no build step). Compact, modern style (light/dark via `prefers-color-scheme`). **Responsive (R9):** desktop layout unchanged; below 1024px fixed column widths are released; below 720px each row becomes a record card carrying the same values (checkbox, date, account, amount, description, comment, badges, `n`, actions) — nothing is dropped. Touch input (`pointer: coarse`) gets larger hit targets and 16px form text; `prefers-reduced-motion` disables motion.
- **Refresh:** Server-Sent Events. `GET /api/events` streams `event: state` messages (same JSON as `/api/state`): one on connect, then one whenever the journal offset, `n` or error changes, plus a `: ping` comment every 15 s. The browser uses `EventSource`; while the stream is down it falls back to polling `GET /api/state` every 2 s and stops polling when the stream reconnects. At most 32 concurrent streams (`503` beyond).
- **Page:** two lists — HELD and REVIEW (same sets as §2.6; REVIEW rows labelled *ambiguous match* or *potential duplicate*). Each row: date, account, amount (cents formatted exactly), raw description, `n`, comment, badges. No pairing suggestions, no resolved-history view.
- **Actions** (only the existing decisions):
  - `MARK_EXTERNAL` on a HELD/REVIEW row.
  - `CONFIRM_TRANSFER`: select exactly two rows, then "Pair as transfer" — enabled only if amounts are equal and opposite (non-zero), accounts differ and currencies match. The sequencer remains authoritative.
  - `DISMISS_DUP` on a row flagged `POTENTIAL_DUP`.
  - (TODO: "confirm REVIEW" — meaning not yet defined.)
- **Double confirmation:** an action button opens a confirmation dialog stating the exact effect, with an optional comment. Only its Confirm sends the request. The result is shown: `Resolved` (with `n`) or the sequencer's `Rejected` reason. A repeated submit is harmless — the sequencer rejects it.
- **Resolver API:**
  - `GET /`, `/app.css`, `/app.js` — static page.
  - `GET /api/state` → `{ offset, n, updatedAt, error, held:[CanonicalEvent...], review:[CanonicalEvent...] }`.
  - `GET /api/events` → `text/event-stream` of the same state (above).
  - `POST /api/decisions` — body `{ action, externalId?, legA?, legB?, comment? }` (one decision). The resolver validates `action` ∈ {MARK_EXTERNAL, CONFIRM_TRANSFER, DISMISS_DUP}, builds the sequencer request itself (`decisionRef = "ui-" + UUID`) and returns the sequencer's response.
- **Security (no authentication, for now):**
  - Binds `127.0.0.1` by default; LAN exposure requires an explicit `--bind`.
  - `POST /api/decisions` requires `Content-Type: application/json` **and** header `X-Trex-Admin: 1`, and rejects a request whose `Origin` does not match its `Host` (`403`) — blocks cross-site form posts and simple cross-origin requests (CSRF).
  - Page served with `Content-Security-Policy: default-src 'self'`, `X-Content-Type-Options: nosniff`; API responses `Cache-Control: no-store`. The page inserts bank text with `textContent` only (never as HTML).
  - Request body cap 64 KB.
- **Category (read-only):** each row shows its derived category with the rule that produced it (§5.6). The resolver takes no category action — a correction is a pin, and the page shows the exact `categories.yaml` snippet to paste, rather than writing config from a UI.
- **Config (flags):** `--journal <path>`, `--sequencer-url <url>`, `--categories <path>` (categories.yaml, §6), `--port <n>` (default 8090), `--bind <addr>` (default 127.0.0.1), `--poll-ms <n>` (fallback journal read interval, default 10000).

### 5.5 trex-grid — read-only journal grid
A plain, compact, live table over the journal. Read-only: no actions (decisions stay in the resolver). **Responsive (G11):** the table stays a table at every width (it scrolls sideways; rows never become cards). Below 720px the header, toolbar and footer wrap and the column picker becomes a bottom sheet; touch input gets larger hit targets and 16px form text. Same model as the resolver: a journal follower with nothing persisted, event-driven tailing, SSE, JDK `HttpServer`, one bundled vanilla JS page.
- **Shared plumbing (`trex-web`, used by resolver and grid):** `JournalWatcher<V>` (fold from 0, WatchService-triggered reads + fallback poll, refold on shrink/read error, change listeners — §5.4), `EventStreams` (coalesced SSE with heartbeat and client cap), static page serving with the security headers, JSON/error helpers.
- **Views (G1):** **Transactions** (default) — the latest line per `externalId`; **Journal** — every line, every version.
- **Columns (G2):** `n`, date, account, to-account, amount, currency, description, type, state, flags, confidence, provenance, sourceType, receipt, transferKey, comment, `category`, ingestedAt, externalId. `category` is derived per row (§5.6) and shows on hover which rule produced it; a pinned row and a structural `TRANSFER` are badged as such. A compact default set is shown; the rest can be toggled on (column choice kept in the browser's `localStorage`).
- **Sort (G3):** server-side on any column, asc/desc; click a header to sort, shift-click to add a secondary key. Ties always break by `n` ascending, so paging is stable. Text columns compare case-insensitively; nulls sort last. Default: `n` descending.
- **Basic filter & search:** account (matches `accountRef` or `toAccountRef`), state, type, category (including `UNCATEGORIZED`, the worklist that drives rule writing), date from/to (inclusive), and `q` — case-insensitive substring over raw description, description, comment, externalId, receipt, transferKey.
- **Paging while data arrives (G4):** every page is computed against a pinned snapshot `asOfN`: the journal lines with `n ≤ asOfN` (Transactions = latest line per id among them). When SSE reports a newer `n`, the page shows a "N new · refresh" chip instead of moving rows. **Follow** mode (like `tail -f`): when on, and on page 1 sorted by `n` descending, the page refreshes automatically.
- **State (G5):** all journal lines held in memory (append order = `n` order). Each distinct query (view, asOfN, filters, sort) is filtered+sorted once and cached (small LRU); paging slices the cached result.
- **SSE (G6):** `GET /api/events` streams `event: head` — `{ n, offset, updatedAt, error, lines, transactions, accounts }` — never row data.
- **URL (G7):** view, sort, page, size, and filters are mirrored in the page's query string (bookmarkable).
- **Money (G8):** amounts formatted exactly from cents. Footer totals per currency over the filtered set, computed server-side in `long`: Transactions view only, and TRANSFER lines are excluded (their legs already carry the amounts). The Journal view shows counts only (versions would double-count).
- **Security (G9):** read-only, so no CSRF guard is needed; binds `127.0.0.1` by default (LAN exposure via `--bind` is lower-risk than the resolver's); CSP `default-src 'self'`; bank text via `textContent` only; non-GET → `405`.
- **API:**
  - `GET /`, `/app.css`, `/app.js` — static page.
  - `GET /api/head` — same JSON as the SSE `head` event.
  - `GET /api/rows?view=transactions|journal&sort=col:asc|desc[,col:dir…]&page=1&size=50&asOfN=&account=&state=&type=&from=&to=&q=` → `{ asOfN, view, page, size, total, rows:[CanonicalEvent...], totals:[{currency, amount, count}] }`. `size` 1–500 (default 50); `asOfN` absent or above the head → current head. Invalid parameters → `400`.
- **Config (flags):** `--journal <path>`, `--port <n>` (default 8091), `--bind <addr>` (default 127.0.0.1), `--poll-ms <n>` (fallback read interval, default 10000).

### 5.6 trex-category — master categorisation (shared consumer library)

A pure library, not a service and not a copy per consumer: the grid, the resolver and (phase 1.5) the Firefly egress all call it, so they cannot disagree about what a transaction is. Master level only — fine-grained categorisation is Firefly's job, downstream (see the Firefly note below).

**Resolution chain** (first hit wins), given the latest line for an `externalId` and the `LedgerView`:
1. **Structural `TRANSFER`** — the line is a TRANSFER line, or a leg listed in some TRANSFER's `legIds` (§2.6 `Projection`). Rules never assign or override this: it is a fact of the fold, not an opinion.
2. **A pin** — a rule whose `when` is an `externalId` leaf: the one-off human correction for a transaction no sensible rule would catch. Pins are ordinary rules, kept in a `pins` block at the top of the file so they are evaluated before the general ones and stay readable as a group.
3. **The first matching rule** in `categories.yaml`, in file order.
4. **`UNCATEGORIZED`** — nothing matched. Never a guess (§0.6). The uncategorised list is the worklist that drives rule writing, exactly as HELD/REVIEW drove transfer rules.

**Seam.** The evaluator sits behind a `Categorizer` interface, and the YAML rule set is its only implementation in this phase. That is what a different engine would plug into later; §9 records the one anticipated extension.

**Rules** (`categories.yaml`, §6) are **data, not expressions** — no embedded expression language, so a bad rule fails at startup with a pointed message instead of at row 12 000, and nothing evaluates arbitrary code from a config file. A rule is a category plus a `when` tree:
- Composition: `all`, `any`, `not` (nest freely).
- Leaves: `externalId` (a list of ids — the pin form; exact match, never a regex), `match` (regex, case-insensitive, `find` semantics as §3.4), `matchOn` (`raw` — the default, the verbatim field identity hashes — or `description`, the cleaned form, which may evolve), `direction` (`in`/`out`, from the amount's sign), `accounts` (list of refs), `amountMin`/`amountMax` (cents, inclusive, on `|amount|`).

**Result.** `Categorized(category, origin, rule)` where `origin` ∈ {`STRUCTURAL`, `PIN`, `RULE`, `NONE`} and `rule` is the index and source line of the rule that fired, so every reader can answer *why* — a rule table nobody can interrogate becomes folklore.

**Purity.** `Categorizer` is deterministic and I/O-free (like trex-core); only loading `categories.yaml` touches the filesystem. Patterns compile once at load. Load-time errors: an undeclared category in a rule, an uncompilable regex, an empty `when`, `amountMin > amountMax`. Load-time warning: a rule wholly shadowed by an earlier one.

**Dry run** (the tuning loop, and the reason this is usable in practice): categorise a whole journal or a parsed CSV and print per-category counts plus the most frequent uncategorised descriptions, highest first. Rules get tuned against real data before anything reaches Firefly — the same idea as the ING dry run in §4.

**Note on the Firefly egress (phase 1.5, not built here):** it is NOT a plain log-mirror — it projects *resolved units* (TRANSFER lines, and transactions whose latest state is EXTERNAL), needs a projection-state table (`external_id → firefly_group_id`), posts via the Firefly API with `apply_rules: true` and `error_if_duplicate_hash`, and carries trex's master category (§5.6) as the **tag** `trex-category:<name>` — never in Firefly's own `category` field, which Firefly's rules own for the fine-grained level (that is the whole point of the split: master here, fine-grained there). Because the category is derived, the projection-state table also records the category last projected, so a `categories.yaml` change re-projects exactly the affected transactions, and reconverges (nuke Firefly = clear projection table, re-project). Spec it separately when built.

---

## 6. Config

All config files are **YAML** (`.yaml`), bound to records by the Jackson YAML mapper in `trex-journal` (§1) — there is no hand-written config parser. Binding is strict in both directions: an **unknown key** is a startup error (as the hand-written parser enforced) and a **duplicate key** is a startup error rather than last-wins. Value checks (currency set, port range, required fields) are hand-written next to the records, with messages naming the file and the key.

Config is YAML; **the journal is JSONL and stays JSONL** (§3.1) — the two never meet. Three YAML properties that matter here and are checked at load: unquoted `no`/`yes`/`on`/`off` are booleans in YAML 1.1, so account refs, category names and other identifiers are validated as strings and quoted by convention in the shipped files; indentation carries structure, so a misindented block is a bind error, not a silent reparent; anchors and merge keys are allowed and are useful for sharing predicate fragments between category rules.

Separate files, not one: they change on different schedules, and the registry is edited far more often than the rest.

- `accounts.yaml` — the registry (the spine): per account `ref`, `currency` (`AUD|USD|INR`), `fireflyAccountId`. The sequencer uses it only to validate `accountRef` and stamp `currency` (and hold `fireflyAccountId`). It records no source type: that is bound per run by `--source-type` (§4).
- `transfers.yaml` — allowlist regexes (case-insensitive, matched against `rawDescription`), `windowDays` (**required**, no default).
- `sequencer.yaml` — `bindHost` (optional, default `127.0.0.1`), `bindPort` (required), `journal: {source, target}`. (No fsync option — always fsync, §3.1.)
- `categories.yaml` — read by **journal consumers only** (§5.6); the sequencer never loads it (§0.7). Two keys:
  - `categories` — the declared master categories. Starting set, tuned from dry runs: `SALARY`, `INTEREST`, `GROCERIES`, `BILLS`, `TAXES`, `SAVINGS`, `DISCRETIONARY`. Adding one is a config edit, never a code change. `TRANSFER` and `UNCATEGORIZED` are **reserved** (§5.6) and must not be declared or assigned by a rule.
  - `rules` — ordered; first match wins. Each is `category` plus a `when` tree (§5.6). Pins (one-off corrections, `when: {externalId: [...]}`) go in a `pins` block evaluated before `rules`; both are the same mechanism, separated so hand-written rules stay readable.
  ```yaml
  categories: [SALARY, INTEREST, GROCERIES, BILLS, TAXES, SAVINGS, DISCRETIONARY]
  pins:                       # one-off corrections; evaluated before rules
    - category: TAXES
      when: {externalId: ["9e546cc0260ead1e"]}
  rules:
    - category: SALARY
      when:
        all:
          - direction: in
          - match: "salary|payroll"
    - category: GROCERIES
      when:
        match: "woolworths|coles|aldi|iga"
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

---

## 8. Generation order (for Claude Code)

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
13. **Source footprint** (§1 dependency policy): picocli for the six CLIs. (The CSV library swap was evaluated and rejected — see §1.)
14. **More source types** (§4): `bw-csv` (BankWest: positive debits, pending-row skipping) and `cba-csv` (CommBank: header-less, pre-signed amounts), each with its own golden file (+ test 1 for both).
15. **`cba-pdf`** (§4): PDFBox extraction, frozen normalisation, page-furniture rule, golden file, and the cross-source test that a PDF row and the CSV row for the same transaction mint the same id.

Each component is small and single-purpose; keep trex-core free of any I/O so it stays exhaustively testable. Lean on sealed types + pattern-matching `switch` so extension (new bank, new tier, new state) surfaces every impact site at compile time.

---

## 9. Explicitly out of scope here (later phases)

Multi-currency **logic** (populating `foreignAmount`, cross-currency transfer matching, base-currency views) — the `foreignAmount`/`foreignCurrency` fields exist as nullable superset but stay null/unused in phase 1; CDR and bank-sync feed source types in trex-ingress (the CDR path is what an account with no CSV export needs); a per-account `sourceTypes` allowlist in the registry, enforced by the sequencer (add with the second source type); the Firefly egress follower; resolver TODOs (authentication, "confirm REVIEW" action, pairing suggestions, resolved history); "keep-both" and "MAN-" decisions; storing the conflicting balance of a `POTENTIAL_DUP` (revisit); tier T2 and text corroboration; an `expr` leaf inside a category rule's `when` (§5.6) if the predicate tree ever proves too weak — CEL preferred over JEXL there, because it type-checks at load and cannot side-effect, which keeps the "bad rule fails at startup" property; hot reload of `categories.yaml` (the grid already watches files); a machine-written `overrides.yaml` if pins ever outgrow hand editing (still consumer-side, still no journal line); an **immutable** `extras` map on `Candidate`/`CanonicalEvent`, stamped once at ingest and never updated, for fields trex does not model (CDR counterparty details, bank reference codes) — sorted keys for byte-stability, never in identity, and never a home for mutable annotations (§0.7); a recomputed category table in the SQLite mirror (a derived value must not be frozen into a log mirror, §0.7) and category totals in the grid footer; group commit; concurrency beyond single-writer; DuckDB/Postgres projections. All are additive at the edges and do not change trex-core's contracts.

**Startup prerequisite (phase 1):** the sequencer loads the account registry (`accounts.yaml`) at boot and uses it to (a) validate `accountRef` on every candidate, (b) stamp `currency` and hold the Firefly account id. It has no bank-specific behavior. An unknown `accountRef` is a hard `Rejected`, never an auto-created account.
