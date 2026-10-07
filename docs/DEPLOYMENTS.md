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

**There is no authentication.** Anyone who can reach :8090 can read the journal and write decisions,
so it is LAN-only for now; front it with Caddy (TLS, optional auth) and/or Tailscale before it goes
anywhere wider.

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
    deploy/v2/sequencer.yaml deploy@10.10.10.142:/tmp/
# as root on the host: install into /opt/trex/config and `docker compose up -d`
```

`docker compose up -d` re-runs the `init` service, which seeds the config volume only if a file is
absent, so live rule edits survive an update.

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
7. **Project** when ready: `egress firefly --plan` → review → `--apply` (locked without
   `--allow-apply`).

While prod is still a replica, later deltas — more review, further curation — export with
`trex stream export --since <n>` and ingest the suffix. Once prod starts ingesting on its
own, the two logs diverge and prod becomes the log of record; from then on, streams are for
backup and restore, not promotion.

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

- **The first full apply can outlast the runner's 30-minute cap.** Firefly's
  `error_if_duplicate_hash` scans existing transactions, so a bulk apply slows as the table grows
  (measured on 6.7.3/SQLite: ~2.8/s over the first hundred, ~0.6/s past a thousand). A first
  full-history pass is served in chunks — the cap terminates the run (exit 143) and the next plan
  shows only the remainder. Every apply is idempotent (`external_id`, then the duplicate hash), so
  nothing double-posts; raise `--job-timeout-minutes` on the runner if one pass is preferred.
- **`init` command must be a list of one.** Compose shell-splits a string `command`, so
  `entrypoint: ["/bin/sh","-ec"]` received only `mkdir` as its script; the fix is a one-element
  list so the whole script stays a single argument.
- **`tmpfs: ["/tmp:exec"]`.** sqlite-jdbc extracts a native library into `/tmp` and loads it, so a
  `noexec` `/tmp` makes the hub (and the `index`/`verify` tools) die at startup. v1's sqlite egress
  needed the same.
- **chown the mounted volumes, not `/var/lib/trex`.** With `read_only: true`, the parent is on the
  read-only root; only the volume mount points are writable.
