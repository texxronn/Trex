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

- **`init` command must be a list of one.** Compose shell-splits a string `command`, so
  `entrypoint: ["/bin/sh","-ec"]` received only `mkdir` as its script; the fix is a one-element
  list so the whole script stays a single argument.
- **`tmpfs: ["/tmp:exec"]`.** sqlite-jdbc extracts a native library into `/tmp` and loads it, so a
  `noexec` `/tmp` makes the hub (and the `index`/`verify` tools) die at startup. v1's sqlite egress
  needed the same.
- **chown the mounted volumes, not `/var/lib/trex`.** With `read_only: true`, the parent is on the
  read-only root; only the volume mount points are writable.
