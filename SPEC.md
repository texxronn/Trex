# trex — Transaction Sequencer: Java 25 Code Plan

A build spec for three components, pitched for Claude Code generation:
1. **trex** — the transaction sequencer service (the journal core)
2. **A sample ingress adapter** — ING CSV → candidates → trex
3. **Two sample egress followers** — `toArchive` and `toSQLiteWAL`

Design authority is `firefly-ingest-spec-v2.md` + the conversation's later decisions. This document is the *how to build it*; that one is the *why*. Where they conflict, the invariants in §0 win.

---

## 0. Non-negotiable invariants (every component honors these)

1. **`external_id` is the sole identity.** Natural key (ING `Receipt`) ▸ else content hash `sha256(accountRef|date|amount|rawDescription|occ)[:16]` — hashes the **verbatim** `rawDescription`, never the cleaned `description`, so description cleanup stays soft and can evolve without shifting ids. **`balance` is never in identity *or* transaction semantics** — it is provenance/reconciliation data only (see §2.2 field doc and §5).
2. **The journal is append-only, single-writer, immutable.** No update, no delete. Corrections are new events with `corrects`.
3. **`n` is a monotonic long, assigned once at first ingest of a distinct `external_id`, then preserved forever** (read back on replay). Never in identity, referenced by nothing.
4. **Amounts are `long` cents (fixed ×100 — every account is AUD/USD/INR, all 2-decimal).** `BigDecimal` appears **only at the CSV parse boundary** to convert a decimal string to cents (scale-checked); never `double`/`float`, never in the core.
5. **Projection to any sink is one-way and idempotent.** Re-delivery must be a no-op.
6. **Accuracy over recall.** Ambiguity → review queue, never a guess.

---

## 1. Platform, build, dependencies

- **JDK 25 (LTS)**, Maven, with `maven-compiler-plugin` set to `<release>25</release>` (optionally the `maven-toolchains-plugin` to pin the JDK). Use records, sealed interfaces, pattern-matching `switch` with record patterns, Stream Gatherers, text blocks. Do **not** use preview features (structured concurrency, primitive patterns).
- **Multi-module Maven reactor:**
  ```
  trex/
    pom.xml           # parent (packaging=pom): modules, <release>25</release>, dependencyManagement
    trex-core/        # pure domain: no HTTP, no DB, no I/O framework
      pom.xml
    trex-sequencer/   # the service: journal I/O + HTTP API + wiring
      pom.xml
    trex-ingress-ing/ # sample ingress adapter (CSV → trex)
      pom.xml
    trex-egress-archive/
      pom.xml
    trex-egress-sqlite/
      pom.xml
  ```
  Parent pom pins JDK 25 (`<maven.compiler.release>25</maven.compiler.release>`), lists the five `<modules>`, and centralizes versions in `<dependencyManagement>`. Each service/adapter module is packaged as a runnable jar via `maven-shade-plugin` (or `maven-assembly-plugin`) with its `Main-Class`.
- **Dependencies (minimal on purpose; coordinates are `groupId:artifactId`):**
  - `com.fasterxml.jackson.core:jackson-databind` + `jackson-datatype-jsr310` (JSONL, records, java.time). Configure: `SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS` off; **deterministic field order via an explicit `@JsonPropertyOrder` on `CanonicalEvent`** (matters for byte-stable journal lines).
  - `org.xerial:sqlite-jdbc` (egress-sqlite only).
  - `org.junit.jupiter:junit-jupiter` (tests).
  - **JDK-only for the rest:** `java.net.http.HttpClient` (adapters → trex), `com.sun.net.httpserver.HttpServer` (trex API — **confirmed choice; zero-dep, no Javalin**), `java.util.zip.{GZIPInputStream,GZIPOutputStream}` (gzip — see §3.6), `java.math.BigDecimal` (parse boundary only), `java.time`, `java.nio.channels.FileChannel`, `java.security.MessageDigest`.
  - **gzip note:** neither `HttpServer` nor `HttpClient` handles content-coding automatically — both directions are manual (§3.6, §4).

---

## 2. trex-core — domain types

Pure, deterministic, no I/O. This is where the Java-25 algebraic modelling lives.

### 2.1 Candidate (adapter → sequencer input; "would-be canonical")
```java
public record Candidate(
    String candidateRef,      // adapter-local id for correlating the response (e.g. "row-12")
    String accountRef,        // registry key; implies bank format + currency + firefly id
    LocalDate date,
    long amount,              // signed cents (×100); -ve = money out
    String rawDescription,    // verbatim
    long balance,             // running balance (cents). PROVENANCE/RECONCILIATION ONLY — never identity, never transaction semantics
    String receipt,           // nullable; ING natural key
    String counterpartyBsb,   // nullable; CDR (phase 2)
    String counterpartyAcct,  // nullable; CDR (phase 2)
    String source,            // adapter channel id, e.g. "ing-csv" (copied onto the event)
    Provenance provenance     // BANK for statements; AUTHORED for portal decisions
) {}
```

### 2.2 CanonicalEvent (journal record — one JSONL line)
```java
@JsonPropertyOrder({ "n","externalId","accountRef","currency","date","amount","balance",
    "description","rawDescription","typeHint","transferKey","legIds","corrects","stateSnapshot","confidence",
    "flags","provenance","source","receipt","counterpartyBsb","counterpartyAcct",
    "foreignAmount","foreignCurrency","ingestedAt" })
public record CanonicalEvent(
    long n,
    String externalId,
    String accountRef,
    String currency,          // stamped from registry (account attribute; no conversion)
    LocalDate date,
    long amount,              // cents
    long balance,             // cents. PROVENANCE/RECONCILIATION ONLY — never identity, never transaction semantics
    String description,       // cleaned
    String rawDescription,
    TypeHint typeHint,
    String transferKey,       // nullable; set on the collapsed transfer + its two legs
    List<String> legIds,      // TRANSFER events only: the two leg external_ids collapsed; else null/[]
    String corrects,          // nullable; external_id this reverses/corrects
    EventState stateSnapshot, // append-time snapshot, AUDIT ONLY — NOT authoritative. Runtime state = derivedState(externalId), §3.2
    Confidence confidence,    // nullable
    List<Flag> flags,
    Provenance provenance,
    String source,            // e.g. "ing-csv"
    String receipt,           // nullable
    String counterpartyBsb,   // nullable
    String counterpartyAcct,  // nullable
    Long foreignAmount,       // nullable cents; superset for cross-currency (phase 2), null in phase 1
    String foreignCurrency,   // nullable; superset (phase 2)
    Instant ingestedAt
) {}
```

### 2.3 Enums & sealed hierarchies (drive exhaustive `switch`)
```java
public enum Provenance { BANK, AUTHORED }
public enum TypeHint   { WITHDRAWAL, DEPOSIT, TRANSFER }
public enum Confidence { EXACT, HIGH, REVIEW }
public enum Flag       { POTENTIAL_DUP, LATE_ARRIVAL }
public enum EventState { EMITTED, HELD, MATCHED, AGED_OUT, REVIEW, EXTERNAL }

// Identity tier — sealed so the id function is exhaustive & centralized
public sealed interface IdentityStrategy permits NaturalKey, ContentHash {}
public record NaturalKey(String accountRef, String receipt) implements IdentityStrategy {}
public record ContentHash(String accountRef, LocalDate date, long amount,
                          String rawDescription, int occ) implements IdentityStrategy {}

// Per-candidate outcome returned in the batch response
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
public record ExactTransfer(String legA, String legB, String transferId) implements MatchOutcome {} // T1/T2
public record FuzzyTransfer(String legA, String legB, String transferId) implements MatchOutcome {} // T3 high
public record AmbiguousTransfer(String leg, List<String> candidates)     implements MatchOutcome {} // T3 → review
public record HeldLeg(String leg)                                        implements MatchOutcome {} // no contra yet
public record NotTransfer(String leg)                                    implements MatchOutcome {} // ordinary → EXTERNAL

// Occurrence signature (content-hash banks)
public record Sig(String accountRef, LocalDate date, long amount, String rawDescription) {}
```

### 2.4 Identity (trex-core/Ids)
```java
String externalId(IdentityStrategy s);   // sha256 of a canonical string, first 16 hex chars
String transferId(String receipt);       // "TRF-" + receipt   (ING)
String transferId(String idA, String idB);// "TRF-" + sha256(sorted(idA,idB))[:16]  (cross-bank)
```
- Canonical string for `NaturalKey`: `"nk|" + accountRef + "|" + receipt`.
- Canonical string for `ContentHash`: `"ch|" + accountRef + "|" + date(ISO) + "|" + Long.toString(amount) + "|" + rawDescription + "|" + occ`. (`long` cents has exactly one representation — no `10.0`-vs-`10.00` hazard.)
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
- Open target with `O_APPEND`. `appendBatch` serializes all events to one byte block, does a single write, then `force(true)` (fsync) **before** the sequencer updates in-memory state / returns success. Crash before fsync ⇒ torn tail ⇒ truncated on next recovery ⇒ batch simply absent ⇒ adapter retries (idempotent).
- **Framing on read:** a record is complete iff it ends in `\n` **and** parses as a `CanonicalEvent`. A trailing partial line is *not* returned and the read offset is *not* advanced past it.

### 3.2 Recovery / materialize (startup)
Params: `journal.source`, `journal.target` (both paths).
```
if source == target:
    scanToLastCompleteRecord(target)   // find last valid '\n'-framed, parseable line
    truncate any torn tail             // truncate file to that boundary
    openForAppend(target)
else:                                   // materialize (shm transport, migration, restore)
    byteCopy(source -> target)          // Files.copy / FileChannel.transferTo — NEVER re-serialize
    verify: sha256(source) == sha256(target)  else ABORT
    scanToLastCompleteRecord(target); truncate torn tail
    openForAppend(target)
```
Then **fold** the journal into memory (this *is* self-ingest). **Current lifecycle state is DERIVED by replaying, never read from the stored `stateSnapshot`** — the journal is immutable, so a HELD→MATCHED transition is never a mutation; it's implied by later events. This keeps append-only intact and makes restart reproduce identical state. The two are distinct and must not be conflated:
- `event.stateSnapshot` — the state as recorded when *that* event was appended. Audit/debug only. May be stale (e.g. a leg written `HELD` that a later TRANSFER has since matched).
- `derivedState(externalId)` — the authoritative *current* state, computed by the fold below. This is what every consumer/projector/query uses.
```
pass 1 — index every replayed event:
    seenIds.put(externalId, event)          // dedup set (+ balance for drift check)
    highWaterN = max(highWaterN, n)
    if typeHint == TRANSFER: matchedLegs.addAll(event.legIds)   // legs a transfer resolved
    if event is a WATERMARK control event: watermark[account] = date
    lastBalance[accountRef] = balance
pass 2 — derive current state per ordinary event:
    if externalId in matchedLegs:                 -> MATCHED   (not projected; its TRANSFER is)
    else if transfer-shaped and not matched:
        if date > watermark[account]:             -> HELD      (add to suspense worklist)
        else:                                     -> EXTERNAL  (aged out)
    else:                                         -> EXTERNAL  (ordinary)
    if flags∋POTENTIAL_DUP/LATE_ARRIVAL or AmbiguousTransfer -> reviewQueue
```
The stored `stateSnapshot` is append-time audit only. The authoritative accessor, computed from the fold structures above, is:
```java
// authoritative CURRENT state — computed, never read from event.stateSnapshot
EventState derivedState(String externalId);
//   in matchedLegs                         -> MATCHED
//   transfer-shaped & unmatched & date>wm  -> HELD
//   transfer-shaped & unmatched & date<=wm -> EXTERNAL (aged)
//   otherwise                              -> EXTERNAL
```
Because the fold is deterministic over (events + WATERMARK control events), a crash-restart or a from-scratch rebuild reconstructs `derivedState`, suspense, matched, review and watermarks exactly.

### 3.3 Ingress pipeline (`POST /candidates`)
Validate-all-then-commit (atomic per batch):
```
1. parse body -> List<Candidate>; assign candidateRefs if absent
2. VALIDATE each (accountRef known via registry? amount/date/balance present & parseable?).
   On any hard Rejected, branch on the request's `allOrNone` (default false):
     allOrNone=true  -> append NOTHING; batchStatus=REJECTED; results carry every Rejected
     allOrNone=false -> exclude the rejects; the valid subset proceeds; batchStatus=PARTIAL
   NOTE: allOrNone governs VALIDATION rejects only; it never weakens append atomicity (§3.1).
3. assignOcc(batch)                       // §2.5, over the surviving candidates
4. for each candidate -> mint externalId (NaturalKey if receipt else ContentHash)
5. DEDUP: if externalId in seenIds:
       same balance      -> DroppedDuplicate
       different balance  -> Flagged(POTENTIAL_DUP) -> reviewQueue  (occ drift / collision)
6. MATCH (transfer state machine, §3.4) on the surviving new events
7. assign n = ++highWaterN for each genuinely-new externalId (preserve for held re-presentation)
8. build List<CanonicalEvent> to persist: stamp `currency` (registry), `source`, `ingestedAt`;
   set `typeHint` (TRANSFER for a collapsed transfer, else DEPOSIT/WITHDRAWAL by amount sign);
   set `stateSnapshot` (append-time); a collapsed TRANSFER carries `legIds=[idA,idB]`
9. journal.appendBatch(events)            // atomic; fsync
10. update in-memory state (seenIds, suspense, reviewQueue, matchedLegs, lastBalance)
11. respond { batchHandle, batchStatus: COMMITTED | PARTIAL, results:[...] }
```
`batchHandle` = server-minted per submission (UUID or a monotonic long), returned for audit, **not** supplied by caller. Under `PARTIAL`/`REJECTED` the caller **must** read `results` to learn what landed (a bare status is insufficient).

### 3.4 Transfer matcher + state machine
Default `EXTERNAL`; only allowlisted transfer-shaped legs are held.
- **Allowlist (config, `transfers.toml`):** shared-receipt shape, `Internal Transfer`, `To my account`, Osko/PayID/`Fast Transfer`.
- **Tiers (first match wins), as a sealed `MatchOutcome`:**
  - **T1** same `receipt` in two in-scope accounts, opposite sign → `EXACT` → collapse → `TRF-<receipt>`.
  - **T3** `|amount|` equal + opposite sign + date within `windowDays` + plausible account pair (+ text corroboration) → `HIGH` → collapse; multiple candidates → `REVIEW`.
  - else `EXTERNAL`.
- **State transitions** (`EventState`): NEW → {MATCHED | HELD | REVIEW | EXTERNAL}; HELD → MATCHED (contra arrives later) / REVIEW / AGED_OUT.
- **Aging is watermark-driven, not wall-clock:** `POST /period-complete {date}` advances a per-account (or global) completeness watermark; only then do still-HELD legs below it become AGED_OUT → EXTERNAL. A `late_arrival` (event dated ≤ a per-account high-water) is `Flagged(LATE_ARRIVAL)` → review. **The watermark advance is itself journaled as a `WATERMARK` control event**, so the fold recomputes aging deterministically on restart (never a wall-clock or in-memory-only decision).
- **Collapse (confirmed):** keep **both leg events in the journal, marked `MATCHED`**, for audit — never suppress them, never re-append them. Emit an additional collapsed `TRANSFER` `CanonicalEvent` carrying `transferKey` (`TRF-…`) **and `legIds = [idA, idB]`**; a leg's MATCHED status is *derived* from `legIds` membership at fold time, so the immutable legs are never rewritten. That collapsed record is the **projectable unit** (what the Firefly egress posts as one transfer). So a resolved transfer is three journal records — two `MATCHED` legs (audit, not projected) + one `TRANSFER` (projected). Followers/projectors select by `typeHint == TRANSFER` and skip any leg appearing in some TRANSFER's `legIds`, to avoid double-counting.

Use **pattern-matching `switch` over sealed `MatchOutcome`/`IdentityStrategy`** so adding a tier or state fails compilation until every site handles it.

### 3.5 HTTP API (JDK HttpServer)
- `POST /candidates` — body `{ "allOrNone": false, "batch": [Candidate...] }` (`allOrNone` optional, **default false**) → `{ batchHandle, batchStatus: COMMITTED|PARTIAL|REJECTED, results:[CandidateResult...] }`. 200 on all data-level outcomes (incl. `PARTIAL`/`REJECTED`); **non-2xx only on structural/atomicity/internal failure** (nothing appended).
- `POST /period-complete` — `{ "date": "YYYY-MM-DD", "accountRef": "..."? }` → advances watermark (**appends a `WATERMARK` control event**), returns aged-out events.
- `POST /decisions` — portal authored events (confirm transfer / keep-both / mark-external / MAN- entry); routed through the same ingress write path with `provenance = AUTHORED`.
- `GET /review` — pending review items (portal).
- `GET /held` — HELD legs as the upload worklist (portal).
- `GET /head` — `{ offset, n }` (followers/portal can poll).
- **Single-writer:** guard all mutating endpoints with one write lock (or a single-threaded executor) so appends are serialized. Reads are lock-free over the immutable journal.

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

## 4. trex-ingress-ing — sample ingress adapter (ING CSV)

Separate CLI program; talks to trex over HTTP. Demonstrates the candidate contract.
```
Usage: trex-ingress-ing --account <accountRef> --url http://trex:PORT <file.csv>
```
- **ING format:** header `Date,Description,Credit,Debit,Balance`; `dd/mm/yyyy`; `amount = coalesce(credit,0) + coalesce(debit,0)` (**debit already negative**); `receipt` via regex `Receipt (No )?(\d+)` on description; balance signed. `rawDescription` = verbatim column.
- **Amount parsing (cents, the only place `BigDecimal` lives):** each decimal column → cents via `new BigDecimal(str).movePointRight(2).longValueExact()`; **reject** any value with >2 decimals as bad data (`Rejected`). Never `Double.parseDouble(x) * 100` (reintroduces float error). Applies to `amount` and `balance`.
- Build one `Candidate` per row; `candidateRef = "row-" + lineNumber`; `provenance = BANK`; leave `currency` unset (sequencer stamps it).
- **Batching (day-atomic).** Default: whole file = one `/candidates` call. For a very large file the client MAY split across calls, **but only on whole-day boundaries** — never mid-day (occ is `(account,day)`-scoped; a file is already one account, so the rule reduces to *don't split a calendar day*). Algorithm: group rows by date; pack whole day-groups into a call up to a soft size target; **if one day alone exceeds the target, send that day as its own call anyway — a day is the atom, size yields to correctness**. Each call is an ordinary independent batch; the server needs no chunk-awareness, and `allOrNone` applies per call.
- **Cross-file caveat:** the client keeps a day whole only *within one file*. Pulling whole days per statement (operator discipline) prevents a day splitting across two files; the reconciliation tripwire (Σ ≠ Δbalance) is the backstop if it ever does.
- Print the response: per-row status; non-zero exit if `batchStatus != COMMITTED`.
- HTTP via `java.net.http.HttpClient`; Jackson for (de)serialization.
- **gzip (mirror of §3.6; also manual — `HttpClient` does not auto-gzip):** gzip the request body and set `Content-Encoding: gzip` (candidate arrays compress ~8–12×, so this is worth it for large backfills); always send `Accept-Encoding: gzip`; and degzip the response **iff** it comes back with `Content-Encoding: gzip`. Keep it symmetric with the server helpers so a plain-mode run (no gzip) still works for quick tests.

Acceptance: parsing an ING slice yields the same candidates every run (golden file); a re-run POST returns all `DroppedDuplicate` (idempotent); a gzipped POST and a plain POST of the same file produce identical journal results (gzip is wire-only).

---

## 5. Egress followers (tail the journal file directly)

Followers **read the journal file directly** (single-writer append-only makes this safe), tail by byte offset with framing, and persist only their offset. They do **not** use the trex HTTP API.

### 5.1 Shared follower loop (put in a small `trex-egress-common` or duplicate minimally)
```
loop (cron / poll interval):
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
Rule: **advance the offset only after the consume side-effect is durable.** At-least-once + idempotent consumers (never attempt exactly-once via clever offset games).

### 5.2 trex-egress-archive (log-mirror follower)
- Consume = append the event to an archive JSONL at `archivePath` (cold copy / second location).
- Idempotent: skip if `externalId` already archived (keep a small seen-set, or dedup by scanning — at volume, a `HashSet` loaded at start is fine).
- Cursor: a plain offset file (`archivePath + ".offset"`). This is a pure mirror — no resolution logic, bare offset is correct.

### 5.3 trex-egress-sqlite (log-mirror follower → SQLite WAL projection)
- SQLite with `PRAGMA journal_mode=WAL;`. Schema:
  ```sql
  CREATE TABLE IF NOT EXISTS events (
    external_id TEXT PRIMARY KEY, n INTEGER, account_ref TEXT, currency TEXT,
    date TEXT, amount INTEGER, balance INTEGER, description TEXT, type_hint TEXT,
    transfer_key TEXT, corrects TEXT, state_snapshot TEXT, provenance TEXT, ingested_at TEXT
  );
  CREATE TABLE IF NOT EXISTS follower_state (k TEXT PRIMARY KEY, offset INTEGER);
  ```
- **Exactly-once into SQLite for free:** do the `INSERT ... ON CONFLICT(external_id) DO NOTHING` **and** the `follower_state` offset update **in one transaction**. Because SQLite is transactional, insert+offset-advance commit atomically → no at-least-once duplicate window for this sink. (This is the model the file-only archive follower can't have, hence the ON CONFLICT there.)
- `amount`/`balance` stored as `INTEGER` (cents) — exact and simpler than TEXT; never bind as REAL. `balance` is provenance only (never queried for identity/semantics).
- The `state_snapshot` column stores `event.stateSnapshot` verbatim (audit). A consumer needing *current* lifecycle state must derive it (fold), **not** read this column — a plain mirror does not track `derivedState`.
- Cursor lives in `follower_state`, not a sidecar file.

**Note on the Firefly egress (phase 1.5, not built here):** it is NOT a plain log-mirror — it projects *resolved units* (collapsed transfers, terminal-state events), needs a projection-state table (`external_id → firefly_group_id`), posts via the Firefly API with `apply_rules: true` (Firefly categorizes) and `error_if_duplicate_hash`, and reconverges (nuke Firefly = clear projection table, re-project). Spec it separately when built.

---

## 6. Config

- `accounts.toml` — the registry (the spine): per account `ref`, `format` (`ing|cba|bw`), `currency` (`AUD|USD|INR`), `fireflyAccountId`. Sequencer uses it to stamp `currency` and pick behavior; adapters use it only for `format`.
- `transfers.toml` — allowlist patterns, `windowDays`.
- `sequencer.toml` — `journal.source`, `journal.target`, `apiPort`, `fsync` policy.
- Follower config — `journalPath`, sink path, `pollSeconds`.
- Firefly API token: env var / systemd credential, **never** in config or repo.

---

## 7. Testing (generate alongside code)

Golden-file harness + JUnit 5. Required, mapping to spec §16 assertions:
1. **Determinism:** ING/CBA/BW slices → identical `Candidate`/`CanonicalEvent` output every run (exclude `n`, `ingestedAt` from comparison — they're processing artifacts).
2. **Identity uniqueness:** distinct `external_id` == row count on natural-key data; unique within occurrence groups on content-hash data.
3. **Sign correctness:** known debit/credit per bank.
4. **Batch idempotency:** POST batch A, then A again → second all `DroppedDuplicate`; nothing new appended.
5. **Split-batch transfer:** legs in two separate batches → resolve to one `TRF-` id, no duplicate.
6. **Reconciliation:** Σ(accounted amounts) == Δbalance per account — exact `long`-cent equality, no epsilon/scale fuzz.
7. **Recovery fold:** append N, restart (fold), in-memory state (seen/held/review/highWaterN) matches pre-restart.
8. **Follower resume:** kill follower mid-stream, restart → resumes at persisted offset, no gap/dup.
9. **Materialize bit-identity:** `source != target` → target byte-identical (hash match), offsets still valid, torn tail truncated.
10. **gzip transparency:** the four paths ({plain,gzip} in × {plain,gzip} out) all round-trip; a gzipped POST and a plain POST of the same file yield identical journal bytes (gzip is wire-only); an over-cap decompressed body returns `413`.
11. **Transfer projection unit:** a resolved transfer leaves 3 journal records (2 `MATCHED` legs + 1 `TRANSFER`); the projectable-unit selector returns only the `TRANSFER`, never the legs (no double-count).
12. **allOrNone:** a batch with one bad row → `allOrNone:true` appends nothing (`REJECTED`); `allOrNone:false` commits the good rows (`PARTIAL`) and reports the bad one. Neither half-writes; a fixed resubmit re-dedups (no doubles).
13. **Day-atomic client:** a large ING file split by the client (whole-day calls) yields a byte-identical journal to a single-call ingest of the same file; a deliberately mid-day split is shown to mis-number occ (guard/negative test).
14. **State re-fold:** after a HELD leg is matched and a watermark advanced, restart-fold reproduces identical suspense/matched/review sets and per-account watermarks — from events + WATERMARK control events alone.
15. **rawDescription hashing:** changing the `description` *cleaning* logic leaves every `external_id` unchanged (identity hashes `rawDescription`, not the cleaned form).

---

## 8. Generation order (for Claude Code)

Build bottom-up; each stage compiles and tests green before the next.
1. **trex-core:** records, enums, sealed types, `Ids`, `assignOcc` (+ tests 1–3).
2. **Journal** (JSONL writer/reader, framing, materialize/recover) in trex-sequencer (+ tests 7, 9).
3. **Ingress pipeline + matcher/state machine** (+ tests 4, 5, 6).
4. **HTTP API** (`/candidates` with `allOrNone`, `/head`, `/period-complete`+`WATERMARK`; gzip §3.6; `/review`,`/held`,`/decisions` as stubs).
5. **trex-ingress-ing** against a real ING slice (day-atomic batching, gzip).
6. **trex-egress-archive**, then **trex-egress-sqlite** (+ test 8).

Each component is small and single-purpose; keep trex-core free of any I/O so it stays exhaustively testable. Lean on sealed types + pattern-matching `switch` so extension (new bank, new tier, new state) surfaces every impact site at compile time.

---

## 9. Explicitly out of scope here (later phases)

Multi-currency **logic** (populating `foreignAmount`, cross-currency transfer matching, base-currency views) — the `foreignAmount`/`foreignCurrency` fields exist as nullable superset but stay null/unused in phase 1; CDR ingress adapter; the Firefly egress follower; the review portal; concurrency beyond single-writer; DuckDB/Postgres projections. All are additive at the edges and do not change trex-core's contracts.

**Startup prerequisite (phase 1):** the sequencer loads the account registry (`accounts.toml`) at boot and uses it to (a) validate `accountRef` on every candidate, (b) stamp `currency` and resolve the Firefly account id, (c) select bank-specific behavior. An unknown `accountRef` is a hard `Rejected`, never an auto-created account.
