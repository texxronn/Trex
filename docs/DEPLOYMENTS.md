# Deployments

A living record of where trex runs, so a deployment is reproducible and not only in someone's
shell history. No secrets here. Add a section per host.

## trex — production

| | |
|---|---|
| Host | `trex` (`10.10.10.142`) |
| Login | `deploy@10.10.10.142`; the stack is managed as `root` on the host |
| Docker context | `trex` → `ssh://deploy@10.10.10.142` (`docker context inspect trex`) |
| Layout | `/opt/trex/compose.yml`, `/opt/trex/config/*.yaml`, `/opt/trex/.env` |
| Compose source | `deploy/v2/compose.server.yml` (a copy at `/opt/trex/compose.yml`) |
| Image | `trex/trex-v2:0.1.0-SNAPSHOT` — tag pinned in `/opt/trex/.env` |
| Environment | `TREX_ENV` in `/opt/trex/.env` — the log envelope's `env` and the header badge (Dev1 / Prod / Prod-IN); at most 8 characters |
| Services | `trex-v2-sequencer-1` on `127.0.0.1:8080` (private), `trex-v2-hub-1` on `0.0.0.0:8090` (LAN) |
| State | docker volumes `trex-v2_config`, `trex-v2_journal`, `trex-v2_index`, `trex-v2_evidence`, `trex-v2_archive` |

### Network exposure

The **hub** is published on the LAN — **http://10.10.10.142:8090/** (the Blotter UI and the
read/decision API). The **sequencer** stays on loopback: it is the only writer and nothing off-host
needs it.

The bind hosts are compose variables, loopback by default, set per host in `/opt/trex/.env`:

```
TREX_HUB_BIND=0.0.0.0     # the UI on the LAN
TREX_SEQ_BIND=127.0.0.1   # the writer stays private
```

### Sign-in (opt-in)

`trex-v2-hub-1` takes `--auth /etc/trex-auth/auth.yaml` (or `TREX_AUTH_FILE`); with it every read and
decision needs a signed-in **ron** or **mel** (`V2-SPEC.md` §9.5). Started without it the hub serves
anything to anyone who can reach :8090 — the bind host is then the only protection. The credential file
is **not** a `deploy/config` file: it is created on the host, is never seeded, synced or drift-checked,
and holds only PBKDF2 hashes and one `sessionSecret`.

```sh
# as root on the host: create the file and its sessionSecret, then set each password
docker run --rm -it -v /opt/trex/auth:/auth trex/trex-v2:0.1.0-SNAPSHOT \
  hub passwd --auth /auth/auth.yaml --user ron
docker run --rm -it -v /opt/trex/auth:/auth trex/trex-v2:0.1.0-SNAPSHOT \
  hub passwd --auth /auth/auth.yaml --user mel
chmod 600 /opt/trex/auth/auth.yaml        # root only

# compose mounts it read-only into the hub and passes the flag:
#   volumes: - /opt/trex/auth/auth.yaml:/etc/trex-auth/auth.yaml:ro
#   command: … --auth /etc/trex-auth/auth.yaml
docker compose -f /opt/trex/compose.yml up -d
```

Re-running `passwd` for a user replaces that user's hash; `hub passwd --list` names the ids that have
one and never prints a hash. Changing `sessionSecret` in the file signs everyone out at once, and that
is the whole revocation model.

**Still true, and worth repeating:** the hub is served over plain HTTP on the LAN, so the password and
the session cookie cross the wire in the clear. Sign-in stops the stray device; it does not stop a
sniffer. Bind the hub to loopback and reach it through a tunnel, or terminate TLS in front, before
trusting anything wider than the house.

### MCP server

The hub serves a Model Context Protocol endpoint at **`http://127.0.0.1:8090/mcp`** (LAN:
`http://10.10.10.142:8090/mcp`). It rides the hub's existing listener — no new port — as sessionless
Streamable HTTP: one JSON-RPC `POST` per request.

A stdio-only client (Claude Desktop) reaches it through the `trex mcp` bridge, which reads
newline-delimited JSON-RPC on stdin, forwards each line to the hub, and relays the response. Point
the client at:

```json
{"command":"trex","args":["mcp","--hub-url","http://127.0.0.1:8090"]}
```

`--hub-url` defaults to `$TREX_HUB_URL`, else `http://127.0.0.1:8090`.

The surface is read tools (one per hub read) plus resources (the config files, the status snapshot,
reference data) and prompts, and a compact read (`trex_brief`, `GET /api/brief`). Nothing an MCP
client does writes to the journal unless the hub runs with **`TREX_MCP_ALLOW_WRITES=1`** (or `true`);
then the
low-level write tool (`trex_submit_decisions`) and the three intent-shaped action tools
(`trex_categorize`, `trex_note`, `trex_mark_paid`) each name an **`actingUser`** — a declared active
user (`ron`/`mel`) or `agent`, never anything else. The contract the family bot and an MCP host both
follow — the reads to prefer, the action payloads, the attribution rule and the safety posture — is
`docs/ASSISTANT.md`. The egress jobs — `egress-ezb` (live) and `egress-firefly` (dormant) — are never
exposed; job orchestration stays in the Jobs UI.
**`/mcp` has no authentication of its own** — it inherits the hub's listener, and the hub's sign-in
(§9.5) deliberately does not cover it — so keep the hub loopback-only (or behind the perimeter)
whenever writes are enabled. Turning sign-in on does not close this seam; binding the hub to loopback
does.

### Why `/opt/trex/compose.yml` differs from `deploy/v2/compose.yml`

Only the config paths: the project directory on the server is `/opt/trex`, so the tuned rules are
`./config/...` rather than `../config/...`, and the sequencer config sits with them. Everything else
is identical; regenerate it from the repo file as:

```sh
sed -e 's|\.\./config/|./config/|g' \
    -e 's|file: \./sequencer\.yaml|file: ./config/sequencer.yaml|' \
    deploy/v2/compose.yml > deploy/v2/compose.server.yml
```

### Deploy or update

```sh
# 1. build the image into the host's daemon (no registry needed)
DOCKER_CONTEXT=trex deploy/bin/trex-v2-docker.sh build

# 2. refresh the compose + config on the host, then apply
scp deploy/v2/compose.server.yml deploy@10.10.10.142:/tmp/compose.yml
scp deploy/config/accounts.yaml deploy/config/users.yaml deploy/config/categories.yaml \
    deploy/config/transfers.yaml deploy/config/pins.yaml deploy/config/firefly.yaml \
    deploy/config/ezb.yaml deploy/config/cashflow.yaml deploy/config/profiles.yaml deploy/config/statements.yaml deploy/config/sources.yaml \
    deploy/config/schedule.yaml deploy/config/mortgages.yaml deploy/v2/sequencer.yaml \
    deploy@10.10.10.142:/tmp/
# as root on the host: install into /opt/trex/config and `docker compose up -d`
```

`docker compose up -d` re-runs the `init` service, which seeds the config volume only if a file is
absent, so live rule edits survive an update. It also refreshes `/etc/trex/.shipped/<file>` on
every `up` — the image's copy of each seeded file — so the Jobs page can say which files the
volume is behind on (see *Config drift* below).

### Config drift (`sync-config`)

A repo change to a config file the volume already has never reaches the stack: `init` keeps live
files. The hub compares the three versions of each seeded file — `/etc/trex/.shipped/<file>` (the
image), `/etc/trex/.base/<file>` (the shipped version last installed) and `/etc/trex/<file>`
(live) — and the Jobs page shows a one-line strip when they differ. A file the strip calls
**repo-newer** is safe to update: you never edited it, the repo moved.

`deploy/v2/sync-config.sh` is the container-side script (the one `dev.sh sync-config` runs). Copy
it with the others, then run it as root on the host inside a throwaway container:

```sh
scp deploy/v2/sync-config.sh deploy@10.10.10.142:/tmp/
# as root on the host:
install -m 0644 /tmp/sync-config.sh /opt/trex/sync-config.sh
docker run --rm -i -v trex-v2_config:/etc/trex alpine \
  sh -s < /opt/trex/sync-config.sh
```

It prints every file's verdict, backs each file it updates up to `/etc/trex/.backup/<file>.<ts>`,
copies the image's copy over the live file, and never touches an `edited-here`, `both-changed` or
`unknown` file. A path that exists but is not a regular file stops the run instead of being
compared. The first run after this ships has no `.base/`, so every edited file reads `unknown`;
after looking, record it with `ADOPT` — refused when `.shipped` has no copy, which one `up` seeds:

```sh
docker run --rm -i -v trex-v2_config:/etc/trex -e ADOPT=profiles.yaml alpine \
  sh -s < /opt/trex/sync-config.sh
```

A sync needs no restart: the sequencer re-checks the config before each write, and the hub's
watcher re-derives on the change.

### Upgrading to Cash flow (`budget` → `liquid`)

The Budget tab became **Cash flow** (`V2-SPEC.md` §9.2, §9.3, §12): `accounts.yaml` `budget:` is
replaced by `liquid:`, and the new `ConfigLoader` rejects a `budget:` key, so the hub, sequencer and
runner will not start against an old live `accounts.yaml`. `init` never overwrites a live file, so
change it before (or right after) `up`:

1. Copy `cashflow.yaml` to the host with the others (the scp list above carries it).
2. Check the drift state on the Jobs page. If `accounts.yaml` is **repo-newer** (you never edited
   it), run `sync-config.sh` (*Config drift*). Otherwise edit by hand: rename every `budget` to
   `liquid`, and drop `liquid: false` from `ing-savings`, `ing-loan-offset` and `cba-netsaver` —
   they are liquid now; the two loan accounts keep `liquid: false`.
3. Restart the stack (`docker compose up -d`). Also check any dev run dir's `accounts.yaml`.

Changing `accounts.yaml` changes the `configRevision`, so expect one re-derive (harmless). For the
rest of the deploy month the Blotter's since-line "left this month (was …)" compares the old and
the new semantics.

### Seeding and testing (day 0)

The stack is seeded from the nine standard statement exports (private; never committed). The
mapping is the one the statements use:

| file | sourceType | account |
|---|---|---|
| `Salary_Account.csv` | ing-csv | ing-salary |
| `Loan_Offset.csv` | ing-csv | ing-loan-offset |
| `Mortgage_Simplifier.csv` | ing-csv | ing-mortgage-simplifier |
| `Variable_Rate.csv` | ing-csv | ing-variable-rate |
| `Orange_Everyday.csv` | ing-csv | ing-orange |
| `ING_Credit_Card.csv` | ing-csv | ing-credit-card |
| `Bankwest_Transactions_full.csv` | bw-csv | bw-credit-card |
| `CBA_SmartAccess.csv` | cba-csv | cba-smartaccess |
| `CBA_NetSaver_TransactionSummary.pdf` | cba-pdf | cba-netsaver |

On the host:

```sh
/opt/trex/ingest-all.sh /opt/trex/statements      # from deploy/v2/ingest-all.sh
```

Re-running is safe (whole-observation dedup appends nothing). After the first seed: `n=1857`
facts, 9 evidence files, 24 transfers, 1775 units, 98 review items (24 `POTENTIAL_DUP`,
25 `RESTATEMENT`, 49 `UNMATCHED_LEG`), and all nine accounts reconcile. The revision that produced
it is in `/api/status` (`configRevision`).

To start over — day 0 again — wipe the volumes and re-seed:

```sh
cd /opt/trex && docker compose down -v && docker compose up -d && ./ingest-all.sh
```

### Promoting dev curation to prod

Dev is where rules and reviews are iterated; prod is cut over by **replicating the stream**,
not by copying a journal file by hand. `trex stream` exports the log as-is and ingests it
line for line into a fresh target, so `n`, `atMs`, decisions, evidence ids and the
fact/decision interleaving all arrive intact. Pin one release and one config for both sides
first.

1. **Back up the host** (journal, evidence, config) — the old v1 journal is private and may be
   the only copy. See *Back up* below.
2. **Review to done in dev**, then export the stream:
   `trex stream export --journal <dev journal> --out trex-stream.jsonl.gz`, or the runner's
   `stream` job with `mode=export`. Treat the file as private, like the statements.
3. **Copy the stream *and* the evidence store** to the host. Facts name evidence ids;
   `stream ingest` refuses a missing one, and `trex verify` checks the hashes afterwards.
4. **Deploy the same image and config** to the host, and on cutover reset to day 0
   (`docker compose down -v && docker compose up -d`). Keep the statement store on the host
   for future ingests.
5. **Ingest the stream:**
   `trex stream ingest --file trex-stream.jsonl.gz --sequencer-url http://127.0.0.1:8080`
   (or the runner's `stream` job, `mode=ingest`). It refuses a config mismatch or a gap at
   `head + 1`, and is resumable — re-running skips what has already landed.
6. **Verify**: `n` and counts match dev, `reconcile` and the review queue match, `trex verify`
   is green (framing, index, evidence hashes, egress plan).
7. **Project** when ready: `egress ezb --plan` → review → `--apply` (locked without
   `--allow-apply`) — the live downstream view (V2-SPEC.md §11.2). The Firefly projection
   (`egress firefly`) stays in the tree but is dormant.

While prod is still a replica, later deltas — more review, further curation — export with
`trex stream export --since <n>` and ingest the suffix. Once prod starts ingesting on its
own, the two logs diverge and prod becomes the log of record; from then on, streams are for
backup and restore, not promotion.

### The drop folder (statements in, no clicks)

The runner's `ingest-inbox` job ingests every settled file in the staging inbox that
`statements.yaml` names, then files it under `staging/done/` (ingested) or `staging/failed/` (bad
rows or rejected); a file the map does not name stays put and the Jobs page says it needs a type.
Re-ingesting is idempotent, so a sweep can never double-post.

To make it a real drop folder:

1. **Expose the inbox.** `staging` is a named volume (`trex-v2_staging`). Swap it for a bind mount
   on the runner service — e.g. `- /srv/trex/inbox:/var/lib/trex/staging` — owned by uid 1000, and
   share `/srv/trex/inbox` the way downloads already travel (Syncthing, an SMB share, or `scp`).
   Partial files (`.part`, `.tmp`, dotfiles) and anything modified in the last 30 s are skipped.
2. **Name the files.** Check `statements.yaml` matches what the banks download as (globs:
   `BW_*.csv`, `CBA_SmartAccess_*.pdf`); copy it, and `schedule.yaml`, to `/opt/trex/config`.
3. **Schedule it.** Uncomment the `ingest-inbox` entry in `schedule.yaml` (hourly at :05). The Jobs
   page's **Ingest inbox** button runs the same sweep on demand.

Enable the schedule only once the inbox holds nothing you did not mean to ingest: the log is
permanent (undo is a `RETIRE`).

### Back up (volume → tarball)

The journal, evidence and config volumes are the only irreplaceable state; the index is rebuilt.

```sh
on_host() { ssh deploy@10.10.10.142 "sudo $*"; }   # or use the root tmux pane
for v in journal evidence config; do
  on_host docker run --rm -v trex-v2_$v:/v -v /opt/trex/backups:/b alpine \
    tar czf /b/trex-$v-\$(date +%F).tgz -C /v .
done
```

### Gotchas already paid for

- **Rebuild the shaded jar from `clean`.** An incremental `mvn package` once kept a stale
  `trex/v2/core/Ids.class` in `trex-v2-dist/target/trex-v2.jar` while the module jars were current
  (2026-10-09). The Jib image is built from the module jars and is unaffected; anything run with
  `java -jar trex-v2.jar` should come from `mvn clean package`.

- **The first full apply can outlast the runner's 30-minute cap.** Firefly's
  `error_if_duplicate_hash` scans existing transactions, so a bulk apply slows as the table grows
  (measured on 6.7.3/SQLite: ~2.8/s over the first hundred, ~0.6/s past a thousand). A first
  full-history pass is served in chunks — the cap terminates the run (exit 143) and the next plan
  shows only the remainder. Every apply is idempotent (`external_id`, then the duplicate hash), so
  nothing double-posts; raise `--job-timeout-minutes` on the runner if one pass is preferred.
- **The first run after Stage 4** checks every row once (`CHECK` lines; GETs, no writes where
  Firefly already matches).
- **After mapping the clearing accounts**, run `--create-missing-accounts --plan` once, then
  `--apply`.
- **A rule change** walks every unit but only the changed categories get a GET + PUT — expected,
  not a fault; unchanged rows keep their old `rules=` notes value, which `--validate` deliberately
  ignores.
- **`--remove-orphans`** is only for orphans the plan does **not** show as re-keyed; read the
  "replaced by" lines first.
- **Firefly is rebuilt, not migrated (D8):** the kept instance is refed from empty with the final
  build; no partial-stage apply. If an instance ever carries pre-Stage-5 groups, refeed it.
- **What a refeed costs (review V7):** everything done on the Firefly side — hand-assigned budgets,
  piggy-bank links, your tags, hand-splits — is gone after a refeed. Assign budgets with a Firefly
  rule group ("budget from category") and re-run it from "apply rule group to transactions" after a
  refeed and after each sync; never assign budgets by hand.
- **ezbookkeeping is re-applied, not restored (§11.2):** the view holds nothing trex does not, so a
  lost instance is cleared and re-imported with `trex egress ezb --apply`, never backed up or
  restored — it is a disposable view, like the index. Set `EZB_USER`/`EZB_PASSWORD` and the clear is
  one call (`data/clear/transactions`); without them it deletes each transaction by id and stores no
  password.
- **A missing group stops the pass (D9):** a transaction deleted in Firefly stops the next pass that
  actually touches its unit (a planned retag or update), which then needs `--verify` then `--apply`
  (or retire the unit in trex); units that have not moved keep converging. That is the intended
  forcing function, and `--validate` on a timer is how you hear about it before a pass is blocked.
- **The Do/Don't contract (`--validate`).** Yours: extra tags, budgets, bills, notes below our first
  line and hand-splits. Ours: deleting a tagged transaction, removing the `trex` tag, editing
  `external_id`, the first notes line (`n=`/`rules=`/`legs=`) or a single-split group's content, or
  running another importer over a tagged row. `--validate` classifies each: `MISSING` (the group was
  deleted), `UNTAGGED` (the `trex` tag was removed), `TAMPERED` (`external_id`, the notes line's
  shape or a transfer's `legs=` changed) and `DRIFT` (a single-split content edit); the remedies are
  restore the tag, revert the edit, fix the source in trex, retire the unit, or recreate via
  `--verify` → `--apply`. `BEHIND`, `ORPHAN` and `HAND_SPLIT` are informational. Schedule
  `--validate` (read-only) as the detector — not `--verify`, whose rebuild erases the "was known"
  signal.
- **`init` command must be a list of one.** Compose shell-splits a string `command`, so
  `entrypoint: ["/bin/sh","-ec"]` received only `mkdir` as its script; the fix is a one-element
  list so the whole script stays a single argument.
- **`tmpfs: ["/tmp:exec"]`.** sqlite-jdbc extracts a native library into `/tmp` and loads it, so a
  `noexec` `/tmp` makes the hub (and the `index`/`verify` tools) die at startup. v1's sqlite egress
  needed the same.
- **chown the mounted volumes, not `/var/lib/trex`.** With `read_only: true`, the parent is on the
  read-only root; only the volume mount points are writable.
