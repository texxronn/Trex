# trex — transaction sequencer

trex turns bank statement exports into one append-only, deterministic journal of
transactions. It gives every transaction a stable identity, deduplicates re-imports,
matches transfers between your own accounts, and parks anything uncertain for a human
to resolve. Downstream systems (an archive, SQLite, later Firefly III) follow the
journal file.

- **Specification:** [SPEC.md](SPEC.md) — the authoritative build spec.
- **Design decisions:** [DECISIONS.md](DECISIONS.md) — why things are the way they are.

> **Status:** phase 1. Not yet validated against real bank exports; the ING column
> mapping must be checked before the first real ingest (identities are frozen once live).

## How it fits together

```
  bank CSV ──► trex-ingress-ing ──HTTP──►  trex-sequencer  ──append+fsync──► journal.jsonl
                                             ▲       │                          │
                                             │       └── GET /held /review     │ tail (inotify)
                                  POST /decisions                               │
                                             │        ┌─────────────────────────┼──────────────────┐
                                     trex-resolver ◄──┤                         │                  │
                                     (web UI, SSE)    │                 trex-egress-archive  trex-egress-sqlite
                                                      │                  (archive.jsonl)       (SQLite, WAL)
                                        trex-grid ◄───┘
                                  (read-only grid, SSE)
```

- **One writer.** Only the sequencer writes the journal. Every batch is one write
  followed by `fsync` before success is returned.
- **Journal = versions.** A state change (e.g. HELD → MATCHED) appends a new copy of
  the transaction with a new `n`. The line with the highest `n` for an `externalId` is
  its current state. Nothing is ever rewritten.
- **Followers read the file**, not the API, and wake on file-change events
  (inotify) with a slow fallback poll.

## Modules

| Module | Kind | What it does |
|---|---|---|
| `trex-core` | library | Pure domain: records, sealed types, identity hashing, occurrence index, state fold. No I/O. |
| `trex-journal` | library | Journal read side: shared JSON mapper, framed JSONL reader, change signal. |
| `trex-sequencer` | service | Journal writer, recovery, ingest pipeline, transfer matching, decisions, HTTP API. |
| `trex-ingress-ing` | CLI | ING CSV → candidates → sequencer. |
| `trex-egress-archive` | service | Mirrors every journal line to an archive JSONL file. |
| `trex-egress-sqlite` | service | Mirrors every journal line into SQLite (one row per line, keyed by `n`). |
| `trex-web` | library | Shared plumbing for the web followers: journal watcher, SSE, static pages. |
| `trex-resolver` | service | Admin web UI for HELD/REVIEW transactions; acts via `POST /decisions`. |
| `trex-grid` | service | Read-only, paged, sortable, filterable live grid of the journal. |

## Build

Requires JDK 25 and Maven 3.9.

```sh
export JAVA_HOME=/path/to/jdk-25
mvn clean package          # builds all modules and runs the test suite
```

Runnable jars are produced as `<module>/target/<module>-0.1.0-SNAPSHOT-all.jar`.
Container images are built from the same reactor — see [Container images](#container-images).

## Quick start (local)

[`deploy/bin/trex.sh`](deploy/bin/trex.sh) starts and stops all five services from the
build tree. On first start it creates a run directory (`run/` by default, override with
`TREX_RUN`) holding config, journal, logs and pid files; the config is copied from
`deploy/config` once and is yours to edit after that.

```sh
deploy/bin/trex.sh start                        # all five, sequencer first
deploy/bin/trex.sh ingest ing-savings statement.csv
deploy/bin/trex.sh status
deploy/bin/trex.sh logs sequencer               # tail -f
deploy/bin/trex.sh stop                         # reverse order, SIGTERM
```

Resolve HELD/REVIEW transactions at <http://127.0.0.1:8090>, browse everything at
<http://127.0.0.1:8091>.

Individual services take the same arguments by hand:

```sh
mkdir -p /tmp/trex/journal && cp deploy/config/*.toml /tmp/trex/
sed -i 's|/var/lib/trex/journal|/tmp/trex/journal|' /tmp/trex/sequencer.toml

# 1. sequencer (API on 127.0.0.1:8080)
java -jar trex-sequencer/target/trex-sequencer-0.1.0-SNAPSHOT-all.jar /tmp/trex

# 2. ingest a statement
java -jar trex-ingress-ing/target/trex-ingress-ing-0.1.0-SNAPSHOT-all.jar \
  --account ing-savings --url http://127.0.0.1:8080 statement.csv

# 3. resolve HELD/REVIEW transactions at http://127.0.0.1:8090
java -jar trex-resolver/target/trex-resolver-0.1.0-SNAPSHOT-all.jar \
  --journal /tmp/trex/journal/journal.jsonl --sequencer-url http://127.0.0.1:8080

# 4. browse everything at http://127.0.0.1:8091
java -jar trex-grid/target/trex-grid-0.1.0-SNAPSHOT-all.jar --journal /tmp/trex/journal/journal.jsonl
```

## Configuration

The sequencer takes a config directory containing three TOML files (a documented
subset of TOML: tables, arrays of tables, strings, integers, booleans, string arrays,
comments). Samples live in [`deploy/config`](deploy/config).

| File | Contents |
|---|---|
| `sequencer.toml` | `bindHost` (default `127.0.0.1`), `bindPort`, `[journal] source` / `target` |
| `accounts.toml` | `[[account]]` entries: `ref`, `format` (`ing`/`cba`/`bw`), `currency` (`AUD`/`USD`/`INR`), `fireflyAccountId` |
| `transfers.toml` | `windowDays` (required), `allowlist` of case-insensitive regexes for transfer-shaped descriptions |

Relative paths in `sequencer.toml` resolve against the config directory. Unknown keys
are errors. Changing `transfers.toml` affects future ingests only; the journal is never
re-evaluated.

The other programs take command-line flags:

| Program | Flags (defaults) |
|---|---|
| `trex-ingress-ing` | `--account <ref> --url <sequencer> [--batch-rows N] [--no-gzip] <file.csv>` |
| `trex-egress-archive` | `--journal <path> --archive <path> [--poll-seconds 30] [--once]` |
| `trex-egress-sqlite` | `--journal <path> --db <path> [--poll-seconds 30] [--once]` |
| `trex-resolver` | `--journal <path> --sequencer-url <url> [--port 8090] [--bind 127.0.0.1] [--poll-ms 10000]` |
| `trex-grid` | `--journal <path> [--port 8091] [--bind 127.0.0.1] [--poll-ms 10000]` |

For the followers, `--poll-seconds` / `--poll-ms` are only the fallback: they wake as
soon as the journal changes.

### ING adapter

Expects the ING export header `Date,Description,Credit,Debit,Balance`, dates as
`dd/mm/yyyy`, debits already negative. The whole file is validated first; if any value
is not exact cents, **nothing is sent** and every bad row is listed. Exit codes: `0` all
batches committed, `1` invalid file, `2` a batch was not fully committed (see per-row
output), `3` transport failure, `64` usage.

## Sequencer API

| Endpoint | Purpose |
|---|---|
| `POST /candidates` | `{ "allOrNone": false, "batch": [Candidate…] }` → per-row results (`Resolved`, `Held`, `Flagged`, `DroppedDuplicate`, `Rejected`) |
| `POST /decisions` | `MARK_EXTERNAL`, `CONFIRM_TRANSFER`, `DISMISS_DUP` (optional `comment`). **Decisions are final.** |
| `GET /held` | Latest line of every HELD transaction |
| `GET /review` | Latest line of every REVIEW or `POTENTIAL_DUP`-flagged transaction |
| `GET /head` | `{ offset, n }` |
| `GET /reconcile` | Per-account reconciliation of the current snapshot: `{ n, offset, ok, accounts:[…] }`. Read-only. |

Request and response bodies may be gzip-encoded (`Content-Encoding` / `Accept-Encoding`).
Full contract: SPEC.md §3.5.

## Deployment (systemd)

Units live in [`deploy/systemd`](deploy/systemd): one per service plus `trex.target`.
They assume this layout:

| Path | Contents |
|---|---|
| `/opt/trex/lib/*.jar` | the `-all` jars, renamed without version (e.g. `trex-sequencer.jar`) |
| `/etc/trex/` | `sequencer.toml`, `accounts.toml`, `transfers.toml`, `trex.env` (JVM options) |
| `/var/lib/trex/journal/` | the journal (written by the sequencer only) |
| `/var/lib/trex/archive/` | archive mirror and its `.offset` file |
| `/var/lib/trex/sqlite/` | SQLite mirror |

```sh
sudo useradd --system --home-dir /var/lib/trex --shell /usr/sbin/nologin trex
sudo install -d /opt/trex/lib /etc/trex
for m in trex-sequencer trex-ingress-ing trex-egress-archive trex-egress-sqlite trex-resolver trex-grid; do
  sudo install -m 0644 $m/target/$m-0.1.0-SNAPSHOT-all.jar /opt/trex/lib/$m.jar
done
sudo install -m 0644 deploy/config/*.toml deploy/config/trex.env /etc/trex/
sudo install -m 0644 deploy/systemd/* /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now trex.target
```

The `/var/lib/trex/*` directories are created by systemd (`StateDirectory=`), owned by
`trex`. Units run with `ProtectSystem=strict`; each service can write only its own state
directory. `java` is expected at `/usr/bin/java` — edit `ExecStart=` if your JDK lives
elsewhere.

Logs: `journalctl -u trex-sequencer -f` (and likewise for the other units).

On a host without systemd, `deploy/bin/trex.sh` runs the same five services from the
build tree with the same arguments — see [Quick start](#quick-start-local).

## Container images

Six images, one per service module, built by [jib](https://github.com/GoogleContainerTools/jib)
straight from Maven. There is no Dockerfile and no build context, so there is no
`.dockerignore` either: jib assembles layers from the reactor and talks to the Docker
daemon (or a registry) itself.

| Image | Module | Entry point | Port |
|---|---|---|---|
| `trex/sequencer` | trex-sequencer | `trex.sequencer.Main` | 8080 |
| `trex/resolver` | trex-resolver | `trex.resolver.Main` | 8090 |
| `trex/grid` | trex-grid | `trex.grid.Main` | 8091 |
| `trex/egress-archive` | trex-egress-archive | `trex.egress.archive.Main` | — |
| `trex/egress-sqlite` | trex-egress-sqlite | `trex.egress.sqlite.Main` | — |
| `trex/ingress-ing` | trex-ingress-ing | `trex.ingress.ing.Main` | — |

Properties of every image:

- base `eclipse-temurin:25-jre`, **pinned by digest** in the parent pom;
- runs as **uid 1000** (`ubuntu` in the Temurin base), never root;
- **reproducible** — fixed layer timestamps plus `project.build.outputTimestamp`, so
  two clean builds of the same commit give the same image digest;
- **no application data and no config**: the journal, archive and SQLite mirror all
  live on volumes, and the sequencer's TOML arrives as a mount. Only JVM flags are
  baked in, and `JAVA_TOOL_OPTIONS` overrides those at runtime.

### Building

```sh
mvn package -Pdocker                      # all six, into the Docker daemon
mvn package -Pdocker -am -pl trex-grid    # just one
mvn package -Pdocker-push                 # build and push instead
```

`deploy/bin/trex-docker.sh build [module...]` wraps the same commands and takes the
short image names (`grid`, `egress-sqlite`, …).

> **Always build through the `package` phase.** A bare `mvn jib:dockerBuild` resolves
> `trex-core` and `trex-journal` from `~/.m2` rather than the reactor; if what is
> installed there is stale, the image builds fine and then dies at runtime with
> `NoClassDefFoundError`. For the same reason `-pl` needs `-am`.

Override with `-Dtrex.image.prefix=registry.example.com/trex`, `-Dtrex.image.tag=…`,
`-Dtrex.image.arch=arm64`. To move the base image, re-resolve its digest and update
`trex.image.base` — do not replace the pin with a floating tag:

```sh
docker manifest inspect eclipse-temurin:25-jre > /dev/null   # warm auth, then:
curl -sI -H "Authorization: Bearer $TOKEN" \
  -H 'Accept: application/vnd.oci.image.index.v1+json' \
  https://registry-1.docker.io/v2/library/eclipse-temurin/manifests/25-jre \
  | grep -i docker-content-digest
```

### Running with Compose

```sh
deploy/bin/trex-docker.sh build
deploy/bin/trex-docker.sh up
deploy/bin/trex-docker.sh ingest ing-savings statement.csv
deploy/bin/trex-docker.sh down          # add -v to discard the journal
```

`compose.yml` starts the sequencer plus the four followers; `ingress` sits behind the
`tools` profile because it is a one-shot import, not a daemon. The sequencer's config
is delivered through compose `configs` (file **content**, not a bind mount), the
journal/archive/sqlite volumes are named volumes, and every published port binds to
`127.0.0.1` on the Docker host — nothing here is authenticated.

Two details worth knowing:

- Only the sequencer mounts the journal read-write. The followers, resolver and grid
  get `:ro`, which makes the single-writer invariant a mount-level guarantee.
- A one-shot `init` container chowns the fresh volumes to uid 1000 and exits; named
  volumes are created root-owned and the services are not root. It reuses the
  sequencer image, so nothing extra is pulled.
- Every service runs `read_only: true`, `cap_drop: [ALL]` and
  `no-new-privileges`, matching what the systemd units get from `ProtectSystem=strict`
  and friends. Writes go to the volumes and to a `/tmp` tmpfs. Two exceptions, both
  deliberate: `init` keeps `CAP_CHOWN` because that is its whole job, and
  `egress-sqlite` gets an `exec` tmpfs because sqlite-jdbc unpacks a native library
  at startup and `dlopen()`s it — the other five keep `/tmp` `noexec`.

Useful variables: `TREX_IMAGE_PREFIX`, `TREX_IMAGE_TAG`, `TREX_JAVA_OPTS`,
`TREX_SEQ_PORT`, `TREX_RESOLVER_PORT`, `TREX_GRID_PORT`, `TREX_POLL_SECONDS`.

### Remote Docker daemon

jib reads `DOCKER_HOST`; it does **not** read Docker's context file, so
`docker context use` on its own would build locally while compose talked to the remote
host. `deploy/bin/trex-docker.sh` resolves the active context to a `DOCKER_HOST` and
exports it, so Maven and compose always agree:

```sh
docker context create prod --docker host=ssh://trex@prod.example.com
DOCKER_CONTEXT=prod deploy/bin/trex-docker.sh build
DOCKER_CONTEXT=prod deploy/bin/trex-docker.sh up
DOCKER_CONTEXT=prod deploy/bin/trex-docker.sh env   # show what was resolved
```

Doing it by hand is the same idea:

```sh
export DOCKER_HOST=$(docker context inspect prod --format '{{.Endpoints.docker.Host}}')
mvn package -Pdocker && docker compose up -d
```

Loading six images over SSH is slow; pushing to a registry and letting the remote host
pull is usually better:

```sh
TREX_IMAGE_PREFIX=registry.example.com/trex deploy/bin/trex-docker.sh push
DOCKER_CONTEXT=prod TREX_IMAGE_PREFIX=registry.example.com/trex \
  deploy/bin/trex-docker.sh up
```

Volumes and compose `configs` travel to the remote daemon fine. The one thing that
does not is `ingest`: the CSV is read from a directory on the *daemon's* host, so with
a remote context the file has to be there (`TREX_CSV_DIR`).

## Operations

**Reconciliation.** `GET /reconcile` runs the balance-chain check (SPEC §7 test 6) over the
live journal, per account: each leg links `prev = balance − amount` → `balance`, and
`Σ amount` must equal `closing − opening` to the cent.

```sh
curl -s http://127.0.0.1:8080/reconcile | python3 -m json.tool
```
```json
{ "n": 5, "offset": 2848, "ok": true,
  "accounts": [ { "accountRef": "ing-savings", "reconcilable": true, "balances": true,
                  "opening": 200025, "closing": 399132, "sum": 199107 } ] }
```

`ok` is true when every account balances. A gap in the chain — a missing row, a day split
across two statement files — reports `reconcilable: false` for that account, with `opening`
and `closing` left at `0`: it never guesses which end is which. The `n`/`offset` pin the
answer to a journal point, so a result can be quoted against a known state rather than a
wall-clock time.

Worth running after every import, and especially when first validating a real bank export
against its own balances.

**Startup recovery.** On every start the sequencer scans the journal, truncates a torn
tail left by a crash (an incomplete last line), and rebuilds its state from the lines.
If a *complete* line does not parse, startup **aborts** — the journal is never silently
truncated past valid data. Inspect the reported offset before doing anything.

**Materialize.** If `journal.source` ≠ `journal.target`, the source is authoritative:
on every start it is byte-copied over the target and verified by SHA-256. Keep the
source current, or the target's newer lines are lost.

**Re-importing is safe.** Posting the same statement again returns `DroppedDuplicate`
for every row. A row that matches an existing identity with a *different* balance is
flagged `POTENTIAL_DUP` and shows up in the resolver.

**Split large files by whole days only.** Occurrence numbering is per account per day;
the ING adapter never splits a day across calls (`--batch-rows` is a soft target).

**Backups.** `trex-egress-archive` keeps a byte-identical second copy of the journal
(`cmp journal.jsonl archive.jsonl`). Point it at a different disk for real redundancy.
Followers can be stopped, rebuilt, or deleted and re-run at any time: they resume from
their offset and never duplicate.

**Resolving.** Transfer-shaped rows without a matching leg are HELD; ambiguous matches
and potential duplicates go to REVIEW. Nothing ages out automatically. Work through them
in the resolver; every action asks for confirmation because decisions cannot be undone.

### Logging

Every module logs through SLF4J; the six runnable modules bind `slf4j-simple` at runtime
scope (libraries never bind). Logs go to **stderr**, so stdout stays clean for CLI output
such as the ING adapter's per-row results.

```
2026-09-18T13:33:44.403Z INFO Sequencer - candidate batch 96c97e25-…: 5 rows, 5 journal lines, outcomes {Held=1, Resolved=4}
2026-09-18T13:33:29.420Z WARN Main - API has no authentication and is bound to 0.0.0.0; …
```

Defaults live in each service's `simplelogger.properties`; system properties override
them, so nothing has to be rebuilt to change level:

```sh
java -Dorg.slf4j.simpleLogger.defaultLogLevel=debug -jar …
TREX_JAVA_OPTS=-Dorg.slf4j.simpleLogger.defaultLogLevel=debug docker compose up -d
```

| Level | What it carries |
|---|---|
| `error` | Unhandled 500s, a failed journal append, a failed rollback (journal unusable until restart) |
| `warn` | Torn tail truncated, journal read failed, change events unavailable, unauthenticated non-loopback bind |
| `info` | Config loaded, recovery result, bind address, follower start, per-batch outcome counts |
| `debug` | Per-append head offsets, 4xx responses, SSE connect/disconnect, best-effort failures that are safe to ignore |
| `trace` | Every fold transition in `Ledger` (guarded — this is the replay hot path) |

**Journal lines are financial data.** Log statements carry `externalId`, `n`, states and
counts; never `rawDescription`, `description` or an amount. Keep it that way when adding
log lines — SPEC §1 requires it.

## Security

There is **no authentication** anywhere in phase 1.

- Every service binds `127.0.0.1` by default; anything else must be configured
  explicitly, and the sequencer warns when it is.
- The resolver only accepts JSON requests carrying `X-Trex-Admin: 1` with a matching
  `Origin` (blocks cross-site requests from other pages in your browser).
- Web pages use a strict Content-Security-Policy and never render bank text as HTML.
- For remote access use an SSH tunnel (`ssh -L 8090:127.0.0.1:8090 host`) or an
  authenticating reverse proxy. Do not expose the ports directly.

## Development

- Instructions for AI-assisted work: [CLAUDE.md](CLAUDE.md).
- Tests map to SPEC.md §7; `mvn test` runs all of them.
- The journal line format, field order and identity hashing are frozen contracts —
  changing them changes every `externalId`.
