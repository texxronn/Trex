# Sequencer kernel — separating the writer from trex

**Status.** Proposal, for tuning. No code has been written and nothing here is decided.
`V2-PROPOSAL.md` remains authoritative; adopting this means amending its §5.2 (modules), §6 (the
envelope's ownership) and §6.5 (the write path), and adding a stage to
`V2-IMPLEMENTATION-PLAN.md`. Until then this document changes nothing.

**Scope.** The v2 sequencer (`trex-v2-sequencer`) and the parts of `trex-v2-log` it writes through.
v1 is not touched. `derive()`, the index, the hub and ingest are affected only where they import a
type that moves.

---

## 0. One page

Today's sequencer is two things in one class. One is a small, careful, entirely generic machine:
take the single-writer lock, recover the journal, stamp an envelope, assign `n`, append a batch
atomically, fsync once, answer per row. The other is trex: what a fact is, how `occ` and
`externalId` are minted, what counts as the same observation, which thirteen decision actions
exist and which fields each requires, which accounts, users and categories are real.

The proposal is to cut along that line:

- A **kernel** that owns the log's mechanics and the envelope, and treats every line's body as
  opaque. It has no notion of fact, decision, account, user or category. It cannot interpret,
  because it cannot read.
- A **domain** — trex is the first — that declares the *kinds* of line, the *command types* a
  client may submit, a *record* for each, the *keys* that identify a line, and per command type
  a few *checks* and one *factory* that turn a command into a line, and
  the HTTP *routes*.

The kernel calls the domain; the domain never touches the file, the lock, `n` or the clock.

The test of success is blunt: **trex running on the kernel writes a byte-identical journal and
returns the same responses as today's sequencer for the same inputs.** No log-format change, no
`externalId` change, no migration. If that does not hold, the extraction is wrong.

What this buys: a writer you can lift into the next project without carrying trex with it, and a
sharper version of the invariant "the writer never interprets" — the generic half is structurally
incapable of it, and the trex half is a short list of pure functions that can be audited on their
own.

What it costs: one indirection, a module split, and the standing risk of abstracting from a single
example (§11).

### Scope of the first cut

The goal of the first cut is one thing: **trex running on the kernel.**

| In | Out, for now |
|---|---|
| the kernel/application split (§3, §4) with trex as the application (§7) | the passive sequencer and promotion (§5.6) |
| byte-identical journal, same responses (§10) | the UDP / multicast publisher, replay by `n` (§5.5) |
| lifecycle states, heartbeat, `GET /stream`, `GET /status` (§5.4) | plugin lookup by name (§6 B) |
| the hub subscribing to the stream (§7.1) | a journal digest, application-defined ephemeral events, pause/activation routes |

The publisher seam (§5.5) is built with its one SSE publisher; nothing else is built on it yet.

---

## 1. What is generic and what is trex, today

Read from the code as it stands (`Sequencer`, `SequencerService`, `SequencerState`, `HttpApi`,
`Maintenance`, `JournalLock`, `SourceRegistry`, `api/*`, and `trex-v2-log`).

| Concern | Where it lives now | Generic or trex |
|---|---|---|
| Single-writer file lock | `JournalLock` | generic |
| Recovery: materialise source → target, SHA-256 verify, truncate torn tail | `Recovery` | generic |
| Framing: `\n`-terminated UTF-8 records, partial tail unread, corrupt line throws | `FramedReader`, `JsonlJournal` | generic, but typed to `LogLine` |
| One batch = one write + one fsync; rollback on failed append | `JsonlJournal` | generic |
| Envelope `n, kind, v, atMs, env, source, target` and its stamping | `Envelope`, `Sequencer` | generic shape; the 8-char padded code rule is a policy |
| `n` assignment, gapless, in append order | `Sequencer` | generic |
| `source` validation against `sources.yaml` | `Sequencer.source`, `SourceRegistry` | generic |
| `env` from `TREX_ENV` | `SequencerService.env` | generic; only the variable name is trex |
| `allOrNone`, per-row results, `COMMITTED / PARTIAL / REJECTED` | `Sequencer.submitFacts/Decisions` | generic, written twice |
| HTTP transport: exact routes, gzip declared-only, body cap, 404/405/413/415/400/500 | `HttpApi` | generic |
| `GET /head`, journal snapshot | `Sequencer.head`, `Maintenance` | generic; the `trex-` file prefix is not |
| Line kinds `trex.fact`, `trex.decision`, `trex.ingest` | `Fact`, `Decision`, `IngestEvent`, sealed `LogLine` | trex |
| Body field order and names | `LogCodec` | trex |
| Request shapes `FactBatch/FactDraft`, `DecisionBatch/DecisionDraft`, `IngestBatch` | `api/*` | trex |
| Routes `/facts`, `/decisions`, `/ingest` | `HttpApi` | trex |
| Required-field checks; `accountRef` exists | `validateDraft` | trex |
| `occ` assignment, `externalId` minting | `assignOcc`, `Ids.externalId` | trex — the identity contract |
| Whole-observation dedup, `Duplicate` vs `Flagged` | `ObsKey`, `submitFacts` | trex |
| `atMs` echoing a client `ingestedAt` / decision `at` | `submitFacts`, `buildDecision` | trex policy over a generic hook |
| Decision action set, per-action required fields, `actor`/`user` rule | `buildDecision` | trex |
| Reference checks: fact exists, decision `n` exists, category declared and not reserved, review kind valid | `requireFact`, `requireCategory`, `requireItem`, `REVOKE` | trex |
| Writer state: observation set, latest fact per id, decision `n`s | `SequencerState` | trex content, generic idea (a fold of the log) |
| Row outcomes `Appended / Duplicate / Flagged / Resolved` | `RowResult` | trex vocabulary over a generic three-way disposition |
| Loading `Registry` and `RuleSet` | `SequencerService.start` via `ConfigLoader` | trex |

Three observations from that table drive the design:

1. **The batch loop is written twice** (`submitFacts`, `submitDecisions`) and a third path
   (`submitIngest`) bypasses it altogether and returns a different response type. The generic
   machine is already there; it has just not been named.
2. **The trex half never needs the file.** Every trex rule is "given this command, the writer
   state and what I have already seen in this batch: append these lines, skip, or reject".
3. **The writer state is already a pure fold.** It is rebuilt from the log on start and updated
   after each append. That is exactly the contract a kernel can demand of any domain.

Noticed while reading, independent of this proposal: `SequencerState.factsByDay`, `dayFacts`,
`usedOcc` and `latestById()` have no callers (`occ` is batch-local); `FactBatch`/`DecisionBatch`
carry test-source convenience constructors (`TST_0001`) in the production wire type; and
`REVOKE` cannot name a decision submitted earlier in the same batch. None needs fixing to proceed,
but the extraction should not carry the first two across.

---

## 2. Principles

1. **The kernel cannot read a body.** It stamps the envelope, orders, appends, fsyncs. Anything
   that needs a body field is domain code. This turns "the writer never interprets" from a
   discipline into a property of the generic half.
2. **The kernel authors no lines of its own.** No batch markers, no heartbeat, no schema lines.
   Everything in the log was asked for by an application's command. (Trex's `trex.ingest`
   start/complete markers stay a trex kind.) What the kernel does say for itself — that it is
   alive — it says on a separate, unlogged channel (§5.4).
3. **The domain never touches mechanics.** No file, no lock, no `n`, no fsync, no ambient clock,
   no I/O inside admission. The kernel hands in `now`; a check or factory is a pure function of
   the command and a read-only context.
4. **Writer state is a fold of the log, and only that.** The same `apply(line)` runs at recovery
   and after each append. No sidecar, no state that cannot be rebuilt.
5. **Extract by moving, not by inventing.** Every kernel feature must already exist in today's
   sequencer. Anything new (idempotency keys, schema negotiation, auth) is listed in §12 as an
   open question and is out of the first cut.
6. **Byte compatibility is the acceptance test** (§10).
7. **JDK-only for the kernel's contract.** The SPI module depends on nothing. Jackson stays where
   it is today, behind the codec.

---

## 3. The model

The kernel owns the envelope and the mechanics. The application supplies **records** (its command
and event types), **keys**, and per command type a short list of **checks** and one **factory**.
All of it is hard-coded in the application, not read from config: a field list is log format and
must version with the code that reads it. Config carries reference data and deployment settings
only.

### 3.1 Line — mandatory envelope plus the event's fields

```
line     = envelope + body
envelope = n, kind, v, atMs, env, source, target        (kernel, mandatory, always first)
body     = the event record's components, in declaration order         (application)
```

`n`, `atMs` (by default), `env`, `source` and `target` are stamped by the kernel. `kind` and `v`
come from the kind's declaration. `v` is the version of that kind's body, one number per kind; the
envelope's own shape is frozen by the kernel and only grows additively. Readers keep today's rule:
an unknown kind is skipped, a known kind at a higher `v` is refused.

### 3.2 Records are the declaration

Commands and events are plain Java records. The record *is* the schema; there is no second
description of it to keep in step.

| What the kernel needs | Where it reads it from the record |
|---|---|
| field names and wire order | the components, in declaration order |
| field types | the component types — string, integer, boolean, date, instant, enum, list of string, **and nothing else; no nested records** |
| variants | a sealed interface and its permitted records (`Decision` permits `Pair`, `Unpair`, …) |
| the contract | the compact constructor: it throws, the record does not exist |
| the envelope | an event record's first component is the kernel's `Envelope` |

Four things a record cannot say, carried by small annotations in the interfaces jar:

| Annotation says | Default without it |
|---|---|
| this component is optional | required |
| when absent, omit the field rather than write `null` | write `null` |
| the discriminator's field name, and each permitted record's value (`action` = `PAIR`) | — (needed only on a sealed type) |
| a blank string is a real value here | blank counts as absent |

The kernel derives everything else by reflection over the record, once, at registration: the JSON
codec, the structural checks, the collision check against envelope field names, and the content of
`GET /describe`. Reflection over records is JDK-only. The type list is capped on purpose; a record
that needs a nested object is a sign the event should be two kinds.

The write-`null`-or-omit annotation exists because byte compatibility needs it: trex writes
`receipt: null` but omits `user`.

Enums travel by a wire name, not the Java constant name: trex's `Observation.POSTED` is `posted`
on the wire. The convention is an enum that exposes its wire name; the kernel uses it both ways.

### 3.3 Event kinds

A kind is: a namespaced name, a `v`, an event record (or sealed family), and zero or more **keys**
(§3.5). Trex has three: `trex.fact` → `Fact`, `trex.decision` → the sealed `Decision`,
`trex.ingest` → `IngestEvent`. All three exist today and keep their shape.

**The envelope sits inside the event record**, as its first component, as trex's records have it
now. The kernel stamps it and hands it to the factory (§3.6). The alternative — body-only records
wrapped by the kernel — is purer but would change every trex reader that calls `fact.n()`.

### 3.4 Command types

A command is what a client submits. It is its own record, distinct from any event record.

| Part | Owner |
|---|---|
| command envelope: `source`, `target`, `allOrNone` | kernel, mandatory |
| the list field's name (`facts`, `decisions`) and whether the type is a batch or a single | declared by the application |
| each command's fields | the command record |
| the events it produces | whatever its factory returns: one or several, of any registered kinds (§3.6) |
| checks and factory | the application (§3.6) |

`FactDraft` is a command record; `Fact` is an event record. They overlap but are not equal: the
command has `ingestedAt` and no `externalId` or `occ`; the event is the reverse.

**From request to command object.** The kernel does this; it is the first of three factories in
the write path, and the only one the application normally never writes.

1. Parse the body as JSON. Malformed JSON fails the request (400).
2. Lift out the command envelope (`source`, `target`, `allOrNone`) and validate it.
3. Find the commands: for a *batch* type, the elements of the declared list field; for a *single*
   type (`/ingest`), the remaining fields of the request object itself.
4. For each, bind JSON to the command record: match fields to components by name, convert types
   (text to date, instant, enum by wire name), refuse a field the record does not have, then call
   the canonical constructor.
5. A binding failure or a constructor that throws rejects **that row**, naming the field.

So for `/ingest`: the request `{ source, target, phase, batch, evidence, … }` loses `source` and
`target` to the envelope, and the rest is bound to an `IngestCommand` record — today's
`IngestBatch` minus those two fields.

An application may **replace the default binder** for a command type with its own function from
the raw JSON object to the command record. That is the hook for a request that does not map field
for field: a legacy shape, renamed fields, a computed default. Trex needs none.

A command type with variants has a choice. It can be a sealed family like the events, so the
kernel picks the record from the discriminator and each variant's constructor enforces its own
required fields. Or it can stay one flat record with every variant's fields optional, as
`DecisionDraft` is today, leaving the per-action requirements to the event records the factory
builds. Trex keeps the flat form at first, because it is what exists and the rejection messages
stay the same; a new application should prefer the sealed form.

### 3.5 Keys

A key is a **named function from an event to a value**, declared on a kind. The kernel keeps, per
key, the set of values seen so far, rebuilds it from the log at start, and updates it as events
are admitted.

The function takes the **event, not the command**. That is a constraint, not a preference: the key
set must be rebuildable by replaying the log, and the log holds events. Anything a key needs must
therefore be in the event. In practice the factory computes it from the command and stores it —
which is what trex already does with `externalId`.

A kind may have several keys, each with a policy for what happens when an incoming event's value
is already present:

| Policy on hit | Effect | Trex use |
|---|---|---|
| skip | row answered with a label, nothing written | the observation key (`externalId` + content + `balance`) → `Duplicate` |
| label | event is written, the row's outcome label changes | the identity key (`externalId`) → `Flagged` instead of `Appended` |
| reject | row rejected | — (a domain with strict uniqueness) |
| none | index only, for reference checks | decisions by `n`, for `REVOKE` |

The envelope's `n` is available as a built-in key on every kind. Keys also serve **reference
checks**: "this field must be an existing value of that key" is a stock check (§3.6). That covers
trex's `requireFact` and the `REVOKE` target check.

### 3.6 Admission — checks, then one factory

**Admission** decides whether a command enters the log and builds the event that does. It is not
called validation because checking is the smaller part. A command is *admitted* (as an event),
*skipped* or *rejected*.

Records are immutable, so there is no "event being built" to pass along a chain. Admission is two
things per command type:

- **Checks** — an ordered list of functions of `(command, context)`, each answering ok, skip (with
  a label) or reject (with a reason). The first that is not ok ends the row.
- **One factory** — a function of `(command, context)` that returns **an ordered list of events**,
  of any registered kinds. Computed fields are computed here. For each event it builds, the
  factory asks the context for the next envelope, which arrives already stamped with the `n` that
  event will have.

The whole path for one command is a chain of three factories with checks between them:

| # | Step | Who | Fails as |
|---|---|---|---|
| 1 | request → command record (§3.4) | kernel, by reflection; replaceable | row rejected |
| 2 | checks | application; stock checks from the kernel | row skipped or rejected |
| 3 | command → event records (one or more) | application's factory | constructor throws → **request fails** (§3.8) |
| 4 | keys evaluated on each event in order, policies applied | kernel | row skipped, relabelled or rejected |
| 5 | event records → lines | kernel, by reflection | — |

And on the read side the mirror of step 5: line → event record, by the kernel, the same way.

**One command, many events.** A command is often one event, but need not be: "place order" may
record an order line and a stock reservation; a transfer command may record both legs. The
factory returns them in the order they are to be logged, and they may be of different kinds. The
rules that keep this simple:

- **A row is atomic.** All of a command's events are written, or none. They take consecutive `n`
  and are never interleaved with another command's.
- **Key policies apply to the row.** Each event's keys are evaluated in order, each seeing the
  ones before it through the overlay. A *reject* hit on any event rejects the row; a *skip* hit
  on any event skips the row; a *label* hit relabels the row. Nothing is half-written. If a
  command should emit an event only when something does not exist yet, the factory asks the
  context and leaves that event out — it does not rely on a skip policy to drop it.
- **An empty list is a skip.** A factory that returns no events has decided there is nothing to
  record; the row answers as skipped, with the factory's label.
- **Envelopes handed out for a row that is then skipped or rejected are returned.** Their `n`
  live in the overlay like everything else, so the log stays gapless.
- **Any event failing its contract fails the request** (§3.8), as with one.

Trex uses this nowhere today: each of its commands is exactly one line, and the log's rule —
facts, decisions and ingest events only — is unchanged. It is a capability of the kernel, and a
place to be careful: a second event that records a *conclusion* drawn from the first is
interpretation in the writer under another name.

**When no factory is written.** If every required component of the event record, apart from the
envelope, has a same-named, same-typed component on the command record, the kernel builds the
event itself. A kind with no computed fields needs a command record, an event record, and nothing
else. `trex.ingest` is that case.

**Stock checks** ship with the kernel so the common ones are one line: a field must exist in a key
set; a field must be one of a given set; a field must be present when another has a given value.

Trex's fact admission then reads: one check — `accountRef` is a known account — and a factory that
normalises a blank `receipt` to absent, takes `occ` from a batch counter (0 when a receipt is
present), mints `externalId` with `Ids.externalId`, defaults `observation` and `provenance`, and
asks for the envelope at `ingestedAt`. The two keys do the rest.

**Event time.** The envelope defaults to the kernel's clock. A factory may ask for the same
envelope at a different instant; nothing else in the envelope can be changed. That is the hook for
`ingestedAt` and a decision's `at`, and it is why `atMs` is not monotonic in `n`.

What checks and factories may use is the **context** (§3.7), and nothing else.

### 3.7 The context — all writer state, in one place

Every piece of state the writer has lives in the context, and the context is the only thing a
check or factory is given besides the command. There is no separate state object to wire.

| In the context | Maintained by | Lifetime |
|---|---|---|
| the key sets of every kind (§3.5) | kernel, from events | the log |
| application folds, if any (below) | kernel calls the application's `apply(event)` | the log |
| reference data (accounts, users, categories) | application, loaded at start | the process |
| per-batch counters | kernel | one batch |
| `now`, the command envelope (`source`, `target`) | kernel | one batch |

Two rules make this safe:

- **Admission reads; only events write.** A check or factory cannot mutate the context. State
  changes in exactly one way: an admitted event is applied to it. The same application runs at
  recovery, so state is always a fold of the log and nothing else.
- **The context shows the log as if the rows admitted so far in this batch were already
  appended.** The kernel lays a batch overlay over the committed state: each admitted event is
  applied to the overlay at once, and the overlay is merged on commit or thrown away if the batch
  is rejected. There is one view; nothing needs to ask "committed, or earlier in this batch?".

The overlay is a small **behaviour change for trex**, in responses only, never in journal bytes:
a `REVOKE` can name a decision admitted earlier in the same batch, and two different observations
of one `externalId` in one batch answer `Appended` then `Flagged` rather than `Appended` twice.
Accepted (§12.3).

**Per-batch counters** are how trex's `occ` is expressed: "how many earlier rows in this batch had
the same `(account, date, amount, rawDescription)`". A counter lives in the overlay, so an
increment made for a row that is later rejected is discarded with it and never consumes an
occurrence — the property today's code gets by validating before assigning.

**Application folds** are the escape hatch for state that keys cannot express. A fold is an
**immutable value** with one operation: apply an event, returning the *next* fold and leaving the
old one untouched. That shape is what lets the kernel roll it back (§4.1). Nothing in trex needs
one once keys exist.

The context offers no file, no network, no ambient clock.

### 3.8 The contract — what the kernel enforces on every line

A record is a contract, and the kernel enforces it; the application never has to remember to. No
line reaches the log unless its event record could be constructed: every required component
present, every one of its declared type, every enum value a declared one. The envelope is
mandatory on the same terms and is the kernel's own.

The same contract is met at three places, and a failure means something different at each:

| Where | What failed | Whose fault | Result |
|---|---|---|---|
| command, at binding | the client's request does not fit the command record | the client | that row is `Rejected`, with the field named |
| event, in the factory | the factory could not construct a valid event record | the application (a bug) | the **whole request fails** (500), nothing is written, the overlay is dropped |
| line, on replay | a logged line of a known kind and `v` does not fit that version's record | the log is corrupt, or was written by something else | refuse to start, as `JournalCorruptException` does today |

The middle row is the important one. A client can only ever cause a row rejection. An event that
fails its own contract is a programming error, and it must not be reported as the client's bad
row, nor partially committed.

Details that make the contract precise:

- **Required is the default.** A component is mandatory unless annotated optional. The kernel
  checks presence before calling the constructor, so the rejection names the field; the compact
  constructor then adds whatever the type system cannot say.
- **Blank is absent.** A string that is empty or whitespace counts as missing — today's
  `isBlank()` behaviour — unless the component opts out.
- **Unknown fields are refused on the way in, ignored on the way out.** A command carrying a field
  its record does not have is rejected, so a misspelt `recipt` cannot vanish silently. A *reader*
  ignores fields it does not know, which is what keeps an additive field from being a version
  bump.
- **Records are versioned with the kind.** The contract is per `(kind, v)`. Adding an optional
  component changes nothing. Adding a required one, removing one, or changing a type is a new `v`
  and a new record; the kind keeps the record of every `v` it has ever written, with a function
  from each old one to the newest, so old lines still replay under the contract they were written
  to. The writer writes only the newest.
- **Conditional required-ness lives in variants**, not in checks: "`PIN` requires `category`" is
  the `Pin` record. A check is needed only for a rule that depends on *values* — "a `user`
  decision must name a user".
- **Required-ness is structure, not meaning.** The contract says a field is there and well-typed.
  Whether the value refers to something real is a reference check; whether it is sensible is
  `derive()`'s business.

---

### 3.9 What the application hands the kernel

The whole interface between the two jars is four objects. An application is complete when it has
supplied them; the kernel needs nothing else from it.

| Object | Answers | Called |
|---|---|---|
| **command factory** | command type → the command object for this request item | once per submitted command |
| **kind registry** | `(kind, v)` → the event record, and its keys | at registration; on replay; on every read |
| **context factory** | the application's part of the context | once, at start |
| **admission factory** | command → its checks and its event factory (command → one or more events) | once per submitted command |

**Closed world.** The command factory and the kind registry are the complete lists. A command type
the factory does not know is not a valid request (404 on its route, 400 on `/commands/{type}`); a
kind the registry does not know can never be written, and is skipped on read.

**Sealed families, application-side.** The kernel defines two plain interfaces, `Command` and
`Event`; an event exposes its `Envelope`. The application declares one sealed family under each:
trex's `LogLine` (permitting `Fact`, `Decision`, `IngestEvent`) is already the event family, and a
`TrexCommand` permitting `FactDraft`, `DecisionDraft`, `IngestCommand` would be the command one.
Two Java facts shape this: a record cannot extend a class, so the base is an interface and the
envelope is *held*, not inherited; and a sealed type must name its permitted types, so the sealing
is the application's, not the kernel's.

What sealing buys is in the admission factory: it is a `switch` over the command family, and the
compiler refuses to build an application that has a command with no admission. The same holds for
any reader switching over the event family. `Unknown` leaves trex's event family — the kernel
skips unknown kinds before they reach application types.

**The command envelope belongs to the submission, not to each command.** `source`, `target` and
`allOrNone` are stated once per request and the kernel holds them; a command record carries only
its own fields. Admission sees them through the context. (An event is different: every line
carries its own envelope, because every line stands alone in the log.)

**The command factory is usually a table.** In the common case it is a map from type name to
record class, and the kernel's binder (§3.4) does the rest. It is a factory rather than a bare map
so that one type can be built by hand when the request does not map field for field.

**The context factory builds only the application's part**, once: reference data, and any
application fold. The kernel wraps it with what it owns — key sets, the batch overlay, counters,
`now`, the command envelope. If the application built the whole context, or built it per batch,
the rule "admission reads; only events write" (§3.7) could not be kept. The context is typed by
the application's part, so a check reaches `accounts` without a cast.

---

## 4. The kernel's batch algorithm

One loop, replacing `submitFacts`, `submitDecisions` and `submitIngest`:

1. **Parse the body as JSON.** A body that is not valid JSON fails the request (400); no row can
   be identified in it. Then lift out the command envelope and validate `source` and `target`
   against the source policy. A failure here also fails the request (400), not the rows.
2. **Bind every command to its record before admitting anything** (§3.4). A command that does not
   bind is a rejected row. If `allOrNone` and any row failed to bind, answer now: the context was
   never touched and no admission code ran.
3. Open a batch: an empty overlay over the context (§3.7).
4. For each command that bound, in order, run admission (§3.6). The result is an admitted event,
   a skip or a reject. Each admitted event takes the next `n` and is applied to the overlay at
   once; a command's events are admitted together or not at all.
5. If `allOrNone` and any row was rejected: write nothing; answer every row `Rejected` (the faulty
   ones with their reason, the rest with "batch rejected (allOrNone)"); status `REJECTED`.
6. Otherwise encode and append the whole batch with one write and one fsync. If nothing is to be
   appended, do not touch the file.
7. Merge the overlay into the context; advance the head. On a failed append or a rejected batch,
   the overlay is dropped and the context is untouched.
8. Answer: `COMMITTED` if no row was rejected, `PARTIAL` if some were and something was written,
   `REJECTED` if some were and nothing was written.

So admission is only ever handed a well-formed, fully typed command: checks and factories never
parse, never see a missing mandatory field, and never see a malformed row.

Every failure falls into exactly one class, and the class decides who is told what:

| Failure | Found in step | Result |
|---|---|---|
| body is not valid JSON | 1 | request fails, 400 |
| bad `source` or `target` | 1 | request fails, 400 |
| a row does not fit its command record | 2 | that row `Rejected`, field named |
| a row fails a check or a key's reject policy | 4 | that row `Rejected`, with the reason |
| a row is a no-op (key skip policy, or the factory returns nothing) | 4 | that row skipped, with its label |
| a factory builds an invalid event | 4 | request fails, 500; nothing written (§3.8) |
| the append or fsync fails | 6 | request fails, 500; nothing written, journal rolled back |

`n` is assigned when a row is admitted, as today; rejected and skipped rows consume none, and a
rejected batch consumes none at all. A response never shows an `n` whose line is not on disk: rows
that were admitted before a batch was rejected answer without one. The
single-writer section is the kernel's, so checks and factories always run serially and
need no locking.

### 4.1 Commit semantics — what "the overlay" actually has to do

The loop above is a small transaction, and it is worth being exact about it, because the context
changes *during* a batch (each admitted event is applied so later rows can see it) and that change
may have to be undone.

**Undo is needed at two levels, and not only for `allOrNone`:**

| Level | Undone when | Applies to |
|---|---|---|
| **row** | the row is rejected or skipped after some of its effects were applied — a key hit on its second event, a counter it had already advanced, envelopes it had taken | every batch |
| **batch** | `allOrNone` with a rejected row; an event failing its contract (§3.8); the append or fsync failing | every batch |

So the kernel always runs three layers: **committed** state, a **batch** layer over it, and a
**row** layer over that. A row that is admitted folds into the batch layer; a row that is not is
dropped. A batch that commits folds into committed state; one that does not is dropped. The
admitted events themselves are buffered in the batch layer until the single write — the request
body cap bounds that buffer.

**Commit order.** Write and fsync first; only then fold the batch layer into committed state and
publish the new head. If the write fails, the journal rolls back as it does today and the batch
layer is dropped: committed state never saw it. If the process dies between the fsync and the
fold, nothing is lost — state is a cache of the log, and recovery refolds it.

**How each part of the context is made undoable:**

| Part | Mechanism | Cost |
|---|---|---|
| key sets | a delta set per layer; a lookup asks row, then batch, then committed | proportional to the batch |
| counters, next `n` | live in the layers only | proportional to the batch |
| application fold | the kernel holds a reference per layer; apply returns a new value; commit is replacing the committed reference, undo is dropping one | whatever the fold's apply costs |
| reference data | immutable; nothing to undo | none |

Two alternatives were considered for the key sets:

- **Deep-clone the whole state per batch and swap it in on commit.** Correct by construction and
  the simplest to explain, but it costs the size of the *state* per batch, not the size of the
  batch. Trex's observation set holds every observation ever logged, and ingest sends one small
  batch per calendar day; a row-level undo would need a clone per row. Rejected for the key sets.
- **Persistent (structurally shared) collections**, where a "clone" is free. The right tool, but
  the JDK has none and the kernel is JDK-only.

The delta works because of a property worth stating: **key sets only ever grow.** The log is
append-only, so an event can add a key value and never remove one. A layer is therefore just "the
values added since", and folding it in is a union.

For an **application fold** the clone-and-swap idea is exactly what is used, pushed to where it is
cheap: the fold is immutable, so taking a "copy" is keeping a reference, and commit is replacing
one reference with another. An application whose fold is small can implement apply by copying; one
whose fold is large is responsible for making apply cheap. The kernel asks only that apply never
mutates.

**No compare-and-swap is needed** to commit. There is one writer and the whole loop runs inside
its lock, so nothing else can have changed committed state in the meantime. What does need care is
*publication*: `GET /head` and the snapshot read the head from other threads, so the new head and
committed state become visible together, after the fsync, never before.

---

## 5. The API surface

### 5.1 What the kernel serves on its own

| Route | Purpose |
|---|---|
| `GET /head` | `{ n, offset }`, unchanged |
| `POST /maintenance/snapshot` | unchanged; the file prefix becomes a config value |
| `POST /commands/{type}` | the canonical submit route for any registered command type |
| `GET /status` | the long form of the heartbeat: per-key-set sizes, recovery statistics, registered kinds (§5.4) |
| `GET /stream` | ephemeral events: heartbeat and head-moved frames (§5.4); the SSE publisher (§5.5) |
| `GET /describe` | the registered kinds (name, `v`), command types, routes and the source policy — read-only, for clients and for debugging a deployment |

Transport behaviour is the kernel's and does not change: exact path and method matching,
declared-only gzip in both directions, the decompressed-body cap, 404 / 405 / 413 / 415, 400 for a
malformed body or a bad `source`, 500 for the unexpected.

### 5.2 What the domain declares

A domain may bind **route aliases** to its command types, so the existing API does not move:

| Alias | Command type | Shape | List field |
|---|---|---|---|
| `POST /facts` | `fact` | batch | `facts` |
| `POST /decisions` | `decision` | batch | `decisions` |
| `POST /ingest` | `ingest` | single | — |

So "API structure" is configuration: a domain picks its paths, its list-field names and whether a
type is a batch or a single. What a domain cannot change is the transport posture and the
command-envelope fields the kernel owns (`source`, `target`, `allOrNone`).

### 5.3 The response

One response shape for every command type:

```
{ batchHandle, batchStatus, head: { n, offset }, results: [ row… ] }
row = { ref, outcome, n, reason, …domain result fields }
      + lines: [ { n, kind }… ]      only when the command produced more than one event
```

`n` is always the row's first line, so a one-event command answers exactly as today.

- `batchStatus` and the row's presence/absence of `n` come from the kernel's disposition.
- `outcome` is the domain's label; the domain's result fields are flattened into the row, so trex
  keeps `externalId` exactly where it is.
- `head` is **new** on batch responses and is the reconciliation of `/ingest`, which today returns
  a bare `HeadResponse`. With `head` present on every response, the ingest client reads the same
  `n` it reads now. This is the one visible API change in the proposal (additive for `/facts` and
  `/decisions`; a reshaping for `/ingest`). Alternative: let a *single* command type answer with
  the head only, preserving `/ingest` byte-for-byte at the cost of two response shapes. See §12.1.

`ref` stays kernel-generated (`<type>[<index>]`) unless the command supplies one.

### 5.4 Ephemeral events and the heartbeat

Some things are worth telling a listener and not worth keeping. The first is a **heartbeat**: the
sequencer saying, on a fixed interval, that it is alive and where its head is.

An **ephemeral event** is the kernel's name for that class of message:

| | Log line | Ephemeral event |
|---|---|---|
| written to the journal | yes | **never** |
| has its own `n` | yes | no — it *reports* the head `n`, it does not take one |
| replayed on recovery or rebuild | yes | no |
| may influence derived state | yes | **never** |
| delivered to | anyone who reads the log, whenever | whoever is connected at that moment |

It carries the envelope fields that still make sense — `kind`, `v`, `atMs`, `env`, `source` — plus
the head it was emitted at.

**Why not in the log.** A heartbeat a minute is over half a million lines a year that say nothing,
each consuming an `n`. The log would never be quiet, so every follower would wake every minute;
the archive mirror would never be idle; "re-delivery appends nothing" would stop meaning the log
is unchanged; and since nothing is ever deleted, they could never be removed. For trex it would
also break a standing invariant: the log holds facts, decisions and ingest events, and nothing
else.

**The channel.** One long-lived stream from the kernel, `GET /stream`, as server-sent events:

| Frame | Sent | Carries |
|---|---|---|
| `heartbeat` | every interval, from a kernel timer | `atMs`, `env`, `source`, head `n` and `offset` |
| `head` | after each committed batch | the new head `n` and `offset` |

Besides the head, a heartbeat carries what a listener needs to know the sequencer's **state**
and **health**:

| Field | Meaning |
|---|---|
| `session`, `startedAtMs` | an id minted at each start, and when; a changed `session` means the sequencer restarted |
| `state` | the lifecycle state, below |
| `n`, `offset` | the durable head |
| `lastCommitAtMs` | when the head last moved |
| `heapUsed`, `heapMax` | memory, in bytes |
| `keys` | total entries across the key sets — what the writer's memory is mostly made of |
| `admitted`, `rejected` | counts since start |
| `subscribers` | listeners on the stream |

**Lifecycle states.**

| State | Meaning | Commands |
|---|---|---|
| `recovering` | materialising, truncating a torn tail, folding the log into the context | refused, 503 |
| `recovered` | the fold is complete and verified; not yet accepting | refused, 503 |
| `active` | accepting commands | accepted |
| `passive` | following another sequencer's journal into its own (§5.6) | refused, 503 |
| `inactive` | up, readable, deliberately not accepting — shutting down, or paused | refused, 503 |
| `broken` | the journal refused further appends after a failed rollback; needs a restart | refused, 503 |

```
recovering → recovered → active ⇄ inactive
                 │         ↑  ↓
                 └→ passive ┘ broken          (passive → active is promotion, §5.6)
```

Three things this implies:

- **A heartbeat is sent on every state change**, not only on the timer. Otherwise `recovered`,
  which may last milliseconds, would rarely be seen, and a listener would wait up to an interval
  to learn the writer went `broken`.
- **The listener comes up before recovery.** Today the HTTP server starts only after the journal
  is recovered and folded, so there is nothing to ask while that happens. To report `recovering`
  the kernel binds first, serves `GET /head` and `GET /stream`, and refuses commands until
  `active`. On a large log that is the difference between "starting" and "dead" to an observer.
- **`recovered` → `active` is automatic** unless configured otherwise. Keeping them as two states
  leaves room for an explicit activation step — an operator, or one day a standby — without
  changing the vocabulary. `inactive` by pause is likewise named here and not built in the first
  cut; graceful shutdown is its only entry at first.

**Keep it small.** The heartbeat must fit one datagram on the future UDP feed (§5.5), so its field
set is fixed and short. Anything richer — per-key-set sizes, recovery statistics (lines folded,
bytes truncated, duration), the registered kinds — belongs on a `GET /status` route that a
listener calls when a heartbeat makes it curious.

Memory on the heartbeat also answers a standing concern (§12.11): the context is an in-memory fold
that only grows. `heapUsed` against `heapMax` and the `keys` count make that growth visible long
before it is a problem.

The interval is kernel config, default 60 seconds, 0 to disable. A listener that has heard nothing
for a few intervals knows the sequencer is gone rather than merely idle; one that sees a heartbeat
whose head equals its own cursor knows it is caught up, not stalled.

The `head` frame comes almost free and is useful on its own: a follower can be told the log moved
instead of watching the file for changes. Followers still *read* lines from the journal; the
stream only says when to look.

**The rule that keeps this safe.** Nothing reproducible may depend on an ephemeral event. They are
not in the log, so a rebuild never sees them; anything computed from one would differ after
`index --rebuild`. They are for liveness, lag and operations — never an input to `derive()`.
`asOf` stays an explicit input and is not taken from a heartbeat.

`GET /head` stays as the poll-based equivalent for a client that does not hold a stream open.

### 5.5 Publishers — one outbound seam, several transports

The stream of §5.4 is the first instance of something more general, and it is worth shaping the
seam now because a second transport is intended: a **UDP / multicast event feed**.

The kernel gets one outbound concept, a **publisher**, told two things and nothing else:

| Told | When | Content |
|---|---|---|
| *committed* | after a batch's fsync has returned, never before | the batch's lines, exactly the bytes written to the journal, in `n` order |
| *heartbeat* | on the timer, and on every state change | the head `n` and `offset`, `atMs`, `env`, `source`, `session`, `state`, and the health fields of §5.4 |

Zero or more publishers are configured. Each decides what it puts on its wire:

| Publisher | Cut | On *committed* | On *heartbeat* |
|---|---|---|---|
| SSE (`GET /stream`) | first | a `head` frame (the lines themselves stay in the journal) | a `heartbeat` frame |
| UDP / multicast | later | one datagram per line | one datagram |

Rules that hold for every publisher, stated now so the first one does not violate them:

- **Publish only what is durable.** A line is published after its fsync. A subscriber can never
  hold a line that a crash then removes.
- **A publisher can never slow or fail the writer.** It is called after the commit and outside the
  write path's critical work; a slow, full or broken publisher drops or disconnects — it does not
  block an append, and its failure is never the client's error.
- **The journal is the only source of truth; a feed is a fast copy.** Every subscriber must be
  able to fall back to reading the log.

**Why the heartbeat matters more on a lossy feed.** On UDP a lost datagram is silent. A subscriber
notices a gap in the middle of a burst when the next `n` arrives — `n` is gapless, so detection is
a comparison. But if the *last* line of a burst is lost, nothing follows to reveal it. The
heartbeat does: it carries the head `n`, and a subscriber whose own `n` is lower knows it missed
something and by how much. That is the classic job of a heartbeat on a sequenced feed, and it is
the reason to put the head in the heartbeat from the start.

**What the multicast feed will need, and the first cut deliberately does not build:**

- **Gap fill by `n`.** A subscriber that detects a gap needs the missing lines. Today a reader
  addresses the log by byte offset; replay "from `n`" needs either a small `n` → offset index in
  the kernel or a `GET /lines?from=n` route over it. A subscriber with the journal file on hand
  can simply read it.
- **Datagram size.** One line per datagram works while a line fits the network's packet size. A
  long `rawDescription` may not. The feed needs a rule: fragment, or send a "line too large, fetch
  `n`" marker.
- **Late join.** A subscriber starting mid-stream reads the log to the head, then follows the
  feed; the heartbeat tells it where the head is.
- **Exposure.** A multicast feed puts every line on the network in clear text for anything that
  joins the group. For trex those lines are bank transactions. The publisher is off unless
  configured, and its scope (interface, TTL) is explicit config; trex may never enable it beyond
  loopback.

None of this changes the log, the envelope, admission or the context. The publisher is strictly
downstream of the commit.

### 5.6 The passive sequencer — deferred

> **Not in the first cut.** Recorded so the design is not lost and so nothing built earlier rules
> it out. No work on it until trex is running on the kernel.

The same kernel can run in a second role. A **passive** sequencer accepts no commands. It follows
another sequencer's journal, appends every line it reads to **its own journal** at a different
path, and applies each one to its own context — so it holds a verified copy of the log and a warm
copy of the writer's state.

```
clients ──► ACTIVE ──► journal A ──(follow)──► PASSIVE ──► journal B
              │                                   │
           context                             context          (same fold, same result)
```

**What it does with a line.** It does not run admission. The line was already admitted; deciding
again could only disagree. The passive path is the recovery path, run continuously:

1. read the next complete line from upstream;
2. check it continues the log: its `n` is exactly the passive head plus one;
3. append the **same bytes** to its own journal; one fsync per burst read;
4. apply the event to its context (key sets, application folds).

So journal B is always a byte-identical prefix of journal A, and the passive context equals what a
fresh fold of B would give. This is not new machinery: `Recovery` already materialises a source
journal over a target at start and verifies it by hash. Passive is that, kept running.

**It reads no further than the durable head.** This is the one subtle rule. The active sequencer
writes a batch and then fsyncs it; if the fsync fails it truncates the batch away. A follower that
reads to the end of the file could copy complete lines that the active then removes — and journal
B would hold lines that never existed in A. So a passive sequencer reads only up to the offset the
active has **published** as durable (the `head` frame, the heartbeat, or `GET /head`). "Publish
only what is durable" (§5.5) is what makes following safe.

(The same hazard exists today for any reader that follows the file to its end, the hub's indexer
included. The window is a failed fsync, so it is narrow, but the rule is worth adopting for every
follower once a published head exists.)

**Two grades.**

| Grade | Needs the application jar | Holds | Good for |
|---|---|---|---|
| **mirror** | no — kernel only | journal B | an off-disk or off-host copy of the log; this is what trex's archive mirror does by other means |
| **warm** | yes — kind registry, keys, folds (no admission needed) | journal B and a live context | promotion without a refold; serving `GET /status`, a feed, or reads |

**What it serves.** `GET /head`, `GET /status`, `GET /stream` and its own publishers, all about
journal B. Its heartbeat reports `state: passive` and its own head, so lag is one subtraction:
active `n` minus passive `n`. Commands are refused with 503. A passive sequencer may be followed
by another.

**Lifecycle.** `recovering → recovered → passive`, and from there, by promotion, `→ active`.

**Promotion** turns a passive sequencer into the writer: stop following, confirm the head, start
accepting commands. In the first cut it is a **manual, explicit operator action**, and the
proposal is firm on why:

- **Two writers is the one unrecoverable failure.** If the old active is still alive when the
  passive is promoted, both assign the same `n` to different lines and the logs diverge for good.
  Nothing in this design fences the old writer. Automatic failover needs that fencing, and it is a
  much larger subject than this proposal.
- **Replication is asynchronous.** A client is acknowledged when the *active* has fsynced, not
  when the passive has the line. Lines the passive had not yet read when the active was lost are
  not in journal B. Promotion can therefore lose the tail; the operator decides whether that is
  acceptable or whether journal A can still be read.

**How it reaches upstream.** In the first cut, by reading the active's journal file — so the two
share a filesystem, or a mounted one. That protects against a lost process, a corrupted journal B
versus A comparison, and (with B on another disk) a lost disk. Following across hosts without a
shared filesystem needs lines over the network — the feed of §5.5 plus replay by `n` (§12.15) —
and is the natural second transport for a follower, not part of the first cut.

**Knowing the copy is true.** `n` continuity catches a missing or repeated line. To catch
corruption, both roles can keep a running digest of the journal bytes and put it on the heartbeat;
a passive sequencer at the same `n` must show the same digest. See §12.17.

**Invariant check.** "One writer" holds: each journal has exactly one process appending to it, and
the passive sequencer holds the lock on journal B. "The log is the only truth" needs one more
sentence in `V2-PROPOSAL.md`: journal A is the truth, journal B is a verified copy, and after a
promotion B is the truth and A is retired.

---

## 6. How a domain is injected

Three mechanisms, in increasing ambition. They stack; the recommendation is the first two.

### A. Composition (recommended, first cut)

The kernel is a library. A thin launcher constructs the domain and passes it in; `trex-v2-dist`'s
`sequencer` subcommand is that launcher for trex. There is no discovery and no reflection. A new
project writes its own domain and its own ten-line launcher.

Why first: it is the smallest change, it keeps the single shaded jar, and it keeps every wiring
decision visible in code.

### B. Plugin by name (recommended, cheap to add on A)

`sequencer.yaml` names the domain; the kernel finds it with the JDK's `ServiceLoader` among the
jars on the classpath and hands it its own section of the config file, uninterpreted.

```yaml
bindHost: "0.0.0.0"
bindPort: 8080
env: "Prod1"                 # replaces the TREX_ENV variable; the variable can remain an override
journal:
  source: "/var/lib/trex/journal/trex.jsonl"
  target: "/var/lib/trex/journal/trex.jsonl"
archive:
  dir: "/var/lib/trex/archive"
  prefix: "trex"
limits:
  maxBodyBytes: 104857600
heartbeat:
  intervalSeconds: 60        # 0 disables; never written to the journal (§5.4)
publishers:                  # zero or more (§5.5); each receives commits and heartbeats
  - type: "sse"              # GET /stream
  # - type: "udp"            # later: group, port, interface, ttl
role: "active"               # or "passive" (§5.6), with:
# follow:
#   journal: "/mnt/primary/trex.jsonl"
#   head: "http://primary:8080"     # where the durable head is published
sources:
  file: "sources.yaml"       # absent = accept any well-formed code, as today
  code: { width: 8, pad: true, charset: "A-Za-z0-9_" }
domain:
  name: "trex"
  config:                    # opaque to the kernel
    dir: "/etc/trex"         # accounts.yaml, users.yaml, categories.yaml
```

Why second: it makes "one generic sequencer binary, different domain jar" real, which is the
re-use goal, for very little code. It stays JDK-only.

### C. Fully declarative domain (not recommended)

§3 already takes the declarative half that pays: records, keys and stock checks are
declarations, in code. What is deliberately *not* taken is the rest — schemas read from a
config file, and logic written as expressions in YAML. A field list in config can drift from the
code that reads the log, and an expression language inside the only component that can destroy
data is the wrong trade. Logic that stock checks cannot express is a Java lambda.

### What is configurable, by layer

| Thing the request asked to make pluggable | Mechanism |
|---|---|
| Event types / kinds | application declares kinds (§3.3) |
| Message fields and their order | the event record's components (§3.2) |
| Message versions | per-kind `v`, one record per version (§3.1, §3.8) |
| Mandatory fields | required by default on the record, enforced by the kernel (§3.8) |
| Command types | application declares command types (§3.4) |
| Command envelope | kernel-owned core (`source`, `target`, `allOrNone`) + application-declared list field and batch/single shape (§3.4, §5.2) |
| Command message structure | the command record; the binder from request to command is the kernel's, replaceable (§3.4) |
| Business validation and logic | admission: checks and one factory (§3.6) over the context (§3.7) |
| Identity and dedup | keys and their policies (§3.5); batch counters (§3.7) |
| Reference data (accounts, users, categories) | loaded by the domain from its own config section |
| API structure | route aliases and response result fields (§5.2, §5.3) |
| Outcome vocabulary | application labels over the kernel's three dispositions (§3.5, §3.6) |
| Source / env / target code rules | kernel config, a policy (§6 B) |
| Snapshot naming, body cap, bind address, heartbeat interval | kernel config |
| Storage format (framed JSONL) | **not pluggable** in this proposal (§12.5) |

---

## 7. Trex as the first domain

Nothing about trex's behaviour changes; this is where each existing piece lands.

| Today | Becomes |
|---|---|
| `Fact`, `Decision`, `IngestEvent`, `Action`, `Actor`, `Observation`, `Provenance`, `Ids` | unchanged, still in `trex-v2-core` |
| `Envelope` | moves to the kernel's contract; trex's records keep referring to it |
| sealed `LogLine` permitting the three kinds plus `Unknown` | a trex-side sealed type over the kernel's record; `Unknown` becomes the kernel's "skipped kind" |
| `LogCodec` | deleted: the kernel derives the codec from the records. The records' component order must equal today's field order (checked by the golden journal) |
| `FactDraft`, `DecisionDraft`, `IngestBatch` | trex command payloads; the test-source constructors are dropped from the wire types |
| `validateDraft`, `assignOcc`, `Ids.externalId` | required-ness moves onto `FactDraft`; one account check; the `fact` factory (`occ` from a batch counter) |
| `ObsKey` dedup, `Appended/Duplicate/Flagged` | two keys on `trex.fact`: observation (skip) and identity (label) |
| `buildDecision` and the `require*` helpers, `REVIEW_KINDS` | the `decision` factory (the same switch on `action`), with reference checks as stock checks and required-ness left to the `Decision` records |
| `submitIngest`, `IngestBatch` | an `IngestCommand` record and no factory: the kernel builds `IngestEvent` by name (gaining the row machinery it skips today) |
| `SequencerState` (minus the unused day index) | key sets in the context; no trex state class remains |
| `contentCounts`, `batchSeen` | a batch counter; the kernel's in-batch key check |
| `ConfigLoader.load` → `Registry`, `RuleSet` | loaded by the trex domain at construction |
| `JournalLock`, `Recovery`, `JsonlJournal`, `FramedReader`, `Maintenance`, `HttpApi`, `SourceRegistry`, the batch loop | the kernel |

Things that stay trex-specific and should be **said so out loud**, so they are not copied into the
next project by reflex:

- `occ` and the content-hash `externalId` exist because bank exports carry no stable id. A domain
  whose sources have ids needs neither, and its dedup is a one-line key lookup.
- Whole-observation dedup (balance included) and the `Flagged` outcome answer a bank-statement
  problem: the same row re-issued with a different running balance.
- The 8-character padded `env`/`source`/`target` codes are a formatting choice. The kernel makes
  the width and padding a policy; the default can be "trimmed, up to N".
- Echoing a client-supplied time into `atMs` is a trex choice for v1 import and re-parse. A new
  domain should prefer the kernel's clock and keep its event time in the body.
- "Structure at the writer, semantics in `derive()`" — a check confirms that a reference
  *exists*, never that the decision is *sensible* — is the most valuable thing to copy, and it is
  a convention the kernel cannot enforce (§11).

### 7.1 The hub as the first subscriber

Today the hub learns the sequencer is down only when someone submits a decision and the forward
fails with a `502`. Its status strip shows index lag in bytes and says nothing about the writer.
Subscribing to `GET /stream` lets the hub *know* the writer's state rather than discover it by
failing:

| The hub learns | From | Used for |
|---|---|---|
| writer up / down | heartbeats arriving, or silence for a few intervals | status strip; the UI can show "read-only" before a decision is attempted |
| writer unusable | `state: broken` | status strip; an explicit "restart the sequencer" rather than repeated 500s |
| writer starting | `state: recovering` / `recovered` | "sequencer starting" instead of "sequencer down" |
| writer memory | `heapUsed` / `heapMax`, `keys` | a status-strip gauge; an early warning as the log grows |
| writer restarted | `session` changed | a log line and a status note; reconnect logic |
| exact lag | heartbeat head `n` against the index's `n` | "caught up" as a fact, in lines, not inferred from byte counts |
| pointed at the wrong writer | heartbeat `env`; head `offset` against the size of the journal file the hub reads | a loud misconfiguration warning — the hub is reading one log while forwarding to another |
| the log moved | `head` frame | a prompt to refresh the index, alongside the file watch |

The hub already pushes server-sent events to the browser, so writer state reaches the UI by the
route it has.

Two limits, both from §5.4:

- **The index never depends on it.** Writer state is shown, not derived from. `index --rebuild`
  produces the same tables with the sequencer switched off.
- **The file watch stays.** The `head` frame is a faster prompt, not a replacement: the journal is
  the truth, and the hub must still catch up correctly after missing every frame.

---

## 8. Modules

Names are placeholders (§12.6); the split is the proposal.

| Module | Depends on | Holds |
|---|---|---|
| `seq-api` | JDK only | `Envelope`, the `Command` and `Event` interfaces, the four application objects of §3.9, the record annotations, key, check, context, stock checks, response shapes |
| `seq-kernel` | `seq-api`, Jackson, slf4j | lock, recovery, framed journal, record codec and binder, key sets, batch loop, source policy, HTTP transport, snapshot; and the **reader side**: framed reader, tail from an offset, skip unknown kinds, a persisted cursor; the heartbeat timer and the publisher seam with its SSE publisher (§5.5); the passive role (§5.6) |
| `seq-testkit` | `seq-api`, `seq-kernel`, JUnit | the conformance suite (§9) and a toy domain |
| `trex-v2-core` | `seq-api` | as now; the model refers to the kernel's envelope |
| `trex-v2-sequencer` | `seq-api`, `trex-v2-core`, `trex-v2-log` | the trex domain: command records, keys, checks, factories, routes. No file I/O, no HTTP. |
| `trex-v2-log` | `seq-kernel`, `trex-v2-core` | what is left: evidence store, `ConfigLoader`, `Json`/`Yaml`; a trex-typed reader façade over the kernel's framed reader |
| `trex-v2-dist` | everything | the launcher that pairs kernel and trex domain |

Consequences for the other modules:

- **Readers** (`trex-v2-index`, `trex-v2-egress`, the hub's refresher) keep reading typed trex
  lines through the façade in `trex-v2-log`; underneath it is the kernel's framed reader plus the
  codec the kernel derives from trex's records. Their code should not change beyond imports.
- **The hub's dependency on `trex-v2-sequencer`** "for shared wire DTOs only" becomes honest: the
  DTOs live with the trex domain, which no longer drags in a server.
- **`trex-v2-core` gains a dependency** on `seq-api`. It stays pure: `seq-api` is a handful of
  records and interfaces with no I/O. If that still feels wrong, the alternative is for trex's
  records to hold their own envelope type and convert at the domain boundary — more code, cleaner
  core. See §12.2.

Whether the kernel lives in this repository or its own is a separate, later decision; it should be
extracted *in place* first and moved only once a second domain exists.

---

## 9. Guardrails

Making logic injectable makes it easier to inject the wrong logic. Three defences.

1. **Admission's contract is narrow by construction.** A check or factory receives no file, no network handle,
   no clock and no way to mutate writer state. It can be a pure function, and the kit tests that
   it behaves like one.
2. **A conformance kit any domain must pass**, with a toy domain (a key-value or counter log) to
   prove the kernel is not secretly trex-shaped:
   - *replay equivalence*: after every batch, the incrementally updated writer state equals a
     fresh fold of the log;
   - *redelivery*: a domain that claims idempotent commands appends nothing on resubmission;
   - *all-or-none*: one bad row writes zero bytes and performs no fsync;
   - *rejected rows leave no trace* in the context (§3.7);
   - *recovery*: torn tail truncated, state rebuilt, next `n` correct;
   - *rollback*: after a rejected row, a rejected `allOrNone` batch, a contract failure and a
     failed append, the context equals a fresh fold of the log — for key sets, counters, `n` and
     any application fold;
   - *row atomicity*: a command's events are all written, consecutively, or none are; an envelope
     handed out for a row that is not admitted leaves no gap in `n`;
   - *passive equivalence*: a passive sequencer's journal is a byte-identical prefix of its
     upstream's, its context equals a fresh fold of that journal, and it never holds a line beyond
     the upstream's published head — including when the upstream's append fails and rolls back;
   - *ephemeral means ephemeral*: with the heartbeat running and no commands, the journal's bytes
     and head do not change;
   - *envelope integrity*: no body field shadows an envelope field; only registered kinds appear;
   - *contract*: no line is ever written that fails its record; a factory that builds a bad event
     fails the request and writes nothing; an old-`v` line replays against its own record;
   - *determinism*: the same commands against the same log and the same `now` yield the same
     bytes.
3. **The trex invariants are restated against the domain, not the kernel.** "No matching, no
   category, no derived review flags in the writer" becomes a review rule for the two trex
   checks and factories — about 200 lines that can still be read in one sitting, which is the property
   `V2-PROPOSAL.md` §6.3 asks for.

---

## 10. Acceptance

The extraction is done when all of these hold; none needs a new feature.

1. **Golden journal.** A recorded sequence of fact, decision and ingest submissions, replayed with
   a fixed clock against the current sequencer and against kernel + trex domain, produces
   byte-identical journals.
2. **Golden responses.** The same replay produces identical response bodies, modulo the additive
   `head` field (§5.3), the random `batchHandle`, and the two in-batch labels of §3.7.
3. **Existing tests pass unchanged in intent**: `SequencerTest`, `SequencerServiceTest`,
   `SequencerSnapshotTest`, `JournalRecoveryTest`, `LogCodecTest`, and the hub and ingest tests
   that run against a real sequencer.
4. **`trex index --rebuild` over an existing journal** yields the same index as before.
5. **The toy domain passes the conformance kit** using a kernel jar that contains no `trex.*`
   class — checked by the build, not by eye.
6. **`seq-api` has no dependencies**; `seq-kernel` imports nothing from `trex.*`.

---

## 11. Risks, and the case against

This should be argued honestly, because `AGENTS.md` says "do not redesign working parts for
hypothetical requirements" and "prefer simple code over abstractions".

- **One example is a weak basis for an abstraction.** The kernel's shape is inferred from trex
  alone. Mitigation: principle 5 (move, do not invent), the toy domain, and keeping the kernel in
  this repository until a second real domain has used it. If the second domain forces the SPI to
  change, that is the expected cost, paid once.
- **The sequencer stops being one file you can read top to bottom.** Today 457 lines tell the
  whole story. After, it is a kernel loop plus records, checks and factories. The mitigation is that each half is
  shorter and single-purpose, but the indirection is real.
- **A plugin point invites business logic into the writer.** The kernel cannot tell a reference
  check from a matching rule. §9 narrows the contract and the conformance kit tests purity, but
  "semantics belong in `derive()`" remains a human rule.
- **It is not in the staged plan.** It would be a new stage, after the current ones are green, and
  it moves working code without adding a user-visible feature.
- **The requirement is not hypothetical, but it is not trex's.** The reason to do it is re-use in
  other projects. If that re-use is more than a few months away, a cheaper first step exists:
  *do only the in-module split* — name the batch loop, the checks, the factories, the keys and the counters
  inside `trex-v2-sequencer`, with no new modules and no plugin loading. That captures most of the
  clarity, costs a day, and leaves the module extraction as a mechanical follow-up.

Recommendation: do the in-module split first as its own step (it is a pure refactor and proves the
seam), then the module extraction with mechanism A, then B when a second domain appears. Skip C.

---

## 12. Open questions to tune

1. **`/ingest`'s response.** Unify on the batch response with `head` (one shape, one small client
   change) or keep a head-only response for *single* command types (zero client change, two
   shapes)? Proposal: unify.
2. **Where `Envelope` lives.** In `seq-api` with `trex-v2-core` depending on it, or duplicated in
   trex and converted at the boundary? Proposal: `seq-api`; it is seven fields.
3. **In-batch visibility.** *Decided: accepted.* The context shows rows admitted earlier in the
   same batch (§3.7). Two trex response details change (same-batch `REVOKE`; `Flagged` for a second
   observation of an id within one batch); journal bytes do not.
4. **Cross-type batches.** One atomic submission mixing command types (a fact and the decision
   about it)? Today impossible. The kernel loop would allow it cheaply via `/commands` with a type
   per command. Proposal: leave out; note the loop does not preclude it.
5. **Storage pluggability.** Framed JSONL with hand-ordered fields is baked into the kernel here.
   A binary or length-prefixed frame would be a different kernel, not a plugin. Proposal: not
   pluggable; the journal interface stays as the seam for tests.
6. **Names.** `seq-*` is a placeholder. The kernel wants a name that does not say "trex" and does
   not collide with the v1 `trex-sequencer` directory.
7. **Kernel-level idempotency.** Facts are idempotent through trex's observation key; decisions
   are not — resubmitting a decision batch appends it again. A kernel-level idempotency key would
   have to be logged to survive a restart, which collides with principle 2. Proposal: leave to the
   domain; note it as the first thing a second domain will ask for.
8. **Source code policy default.** Keep 8-char space-padded as the kernel default, or make it
   trex's setting and default the kernel to trimmed codes? Proposal: trex's setting.
9. **Authentication.** The API has none and warns when bound off loopback. Still out of scope, but
   the kernel is where it would go, and a generic kernel makes the question more pressing.
10. **`env` configuration.** Move from the `TREX_ENV` variable into `sequencer.yaml`, keeping the
    variable as an override? Proposal: yes.
17. **A journal digest on the heartbeat.** A running hash of the journal bytes lets a passive
    sequencer prove its copy matches (§5.6), and would let any follower do the same. It costs a
    streaming hash per append and a refold's worth of hashing at start. Proposal: yes, but after
    the passive role works on `n` continuity alone.
18. **Promotion.** Manual only in §5.6. Is a guarded manual promotion enough (refuse unless the
    upstream heartbeat has been silent for N intervals, or the operator forces it)? Proposal: yes;
    automatic failover stays out.
19. **Should trex's archive mirror become a mirror-grade passive sequencer?** Same job, one
    mechanism fewer. Proposal: decide after the passive role exists; do not couple the two now.
20. **Batch framing on disk.** A line is framed by its terminator, so a torn *line* is detected
    and truncated. A batch is not framed: it is one write, but a crash part-way through can leave
    a prefix of the batch as complete lines, and recovery keeps them. "One atomic append" then
    holds for the running process, not across a power loss. A length or end marker at batch level
    would close it — the fixed-length-plus-length discipline of binary feeds, applied one level up
    — for example an additive envelope field naming the batch's last `n`, so recovery and readers
    drop a batch whose end is missing. It is a change to the line (additive), so it cannot be in
    the byte-identical first cut. Proposal: confirm the hazard with a test first; decide after
    the extraction.
16. **Activation and pause.** §5.4 names `recovered` and `inactive` as states. Should activation
    be an explicit step, and should there be an operator pause? Proposal: automatic activation, no
    pause route, in the first cut; the states exist so adding either later changes no vocabulary.
15. **Replay by `n`.** The multicast feed (§5.5) needs gap fill addressed by `n`, and readers
    today address by byte offset. An `n` → offset index is small, but it is new kernel state.
    Proposal: defer until the UDP publisher is built; do not add the route speculatively.
14. **Application-defined ephemeral events.** §5.4 gives the kernel a heartbeat and a head-moved
    frame. Should an application be able to emit its own ephemeral events on the same stream
    (progress of a long ingest, say)? Proposal: not in the first cut; the stream is kernel-only.
12. **Unknown command fields.** §3.8 rejects them. Today's endpoints may tolerate them (it depends
    on the JSON mapper's setting); if any client sends extras, strictness will surface it. Proposal:
    strict, and fix the client.
13. **Event-contract failure as a 500.** §3.8 fails the whole request when the application's own
    factory builds a bad event. The alternative — reject just that row — hides a bug behind a client
    error. Proposal: fail the request.
11. **Memory.** Writer state is an in-memory fold that only grows (trex's observation set). Fine at
    personal scale; a generic kernel should state the limit rather than hide it. Snapshotting
    writer state is explicitly not proposed.

---

## 13. Suggested order, if adopted

1. Settle §12.1–§12.3 and §12.6; fold the outcome into `V2-PROPOSAL.md` §5.2, §6 and §6.5.
2. Record the golden journal and golden responses from the current sequencer (§10.1–2). This is
   the safety net and is worth having regardless.
3. In-module split: one batch loop, three admissions, keys, counters — inside `trex-v2-sequencer`.
   Goldens green.
4. Replace `LogCodec` with the record-derived codec. Goldens green.
5. Carve out `seq-api` and `seq-kernel`; trex becomes a domain wired by composition (A).
   Goldens green; build check that the kernel has no `trex.*` import.
6. `seq-testkit` with the toy domain and the conformance suite.
7. Plugin by name (B) and the `domain:` section of `sequencer.yaml`, when a second domain exists.
8. Lifecycle states, the heartbeat and the SSE publisher (§5.4, §5.5); the hub subscribes (§7.1).

**Deferred, not scheduled:**

9. The passive role, mirror grade first, then warm (§5.6). Manual promotion only.
10. The UDP / multicast publisher and replay by `n`.

Step 8 adds capability the current sequencer does not have. It is deliberately after the
extraction, which moves code without changing behaviour, so that the goldens of step 2 stay a
clean test of the move.
