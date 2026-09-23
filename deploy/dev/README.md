# Dev harness — one panel per component

Sample configs and foreground start/stop scripts for running the whole stack out
of a build tree, one tmux panel per component. Nothing here touches
`/var/lib/trex`, `/etc/trex` or systemd; everything lives under
`deploy/dev/run/` (gitignored).

This is the panel-per-service counterpart to `deploy/bin/trex.sh`, which
daemonizes the same services into background processes with log files. Use that
one for a small single-host install, this one for watching a service's log while
you poke it.

## Once

    cd <repo> && mvn -DskipTests package     # shaded -all.jar per module

## Start

    deploy/dev/bin/tmux.sh                   # all six panels, tiled
    deploy/dev/bin/tmux-shells.sh            # same six panels, empty shells, nothing started

or, by hand, one panel each (order matters only for readability — the followers
retry until the journal exists):

    deploy/dev/bin/sequencer.sh
    deploy/dev/bin/egress-archive.sh
    deploy/dev/bin/egress-sqlite.sh
    deploy/dev/bin/resolver.sh
    deploy/dev/bin/grid.sh

Each script takes `start` (the default), `stop` or `status`. `start` runs the
JVM in the foreground, so the panel is the log and **Ctrl-C is the stop**;
`stop` exists for driving a panel from somewhere else and for scripting:

    deploy/dev/bin/grid.sh stop
    deploy/dev/bin/status.sh                 # all five, plus URLs and journal size
    deploy/dev/bin/stop-all.sh               # followers first, sequencer last
    deploy/dev/bin/reset.sh --force          # stop everything, wipe run/

## Feed it

    deploy/dev/bin/ingest.sh ing-csv ing-savings deploy/dev/samples/ing-savings.csv
    deploy/dev/bin/ingest.sh ing-csv ing-orange  deploy/dev/samples/ing-orange.csv

The second file is what makes the matcher fire: a T1 pair by receipt and a T3
pair by amount/date, one leg left HELD for the resolver, one deliberate
bad-row file. See `samples/README.md` for what each row proves.

    curl -s http://127.0.0.1:8080/head      | python3 -m json.tool
    curl -s http://127.0.0.1:8080/held      | python3 -m json.tool
    curl -s http://127.0.0.1:8080/review    | python3 -m json.tool
    curl -s http://127.0.0.1:8080/reconcile | python3 -m json.tool

Resolver UI: <http://127.0.0.1:8090>  ·  Grid: <http://127.0.0.1:8091>

## Config

| file | component | shape |
|---|---|---|
| `trex-dev.env` | all | bind address, ports, log level, `JAVA_OPTS` |
| `config/sequencer.yaml` | trex-sequencer | YAML, `bindPort` + `journal:` |
| `config/accounts.yaml` | trex-sequencer | YAML, the account registry |
| `config/transfers.yaml` | trex-sequencer | YAML, allowlist + `windowDays` |
| `config/categories.yaml` | trex-grid, trex-resolver | YAML, master category rules + pins |
| `config/egress-archive.env` | trex-egress-archive | shell vars → CLI flags |
| `config/egress-sqlite.env` | trex-egress-sqlite | shell vars → CLI flags |
| `config/resolver.env` | trex-resolver | shell vars → CLI flags |
| `config/grid.env` | trex-grid | shell vars → CLI flags |
| `config/ingress.env` | trex-ingress | shell vars → CLI flags |

Every value in a `.env` file is written `VAR="${VAR:-default}"`, so exporting
one wins over the file for a single run:

    ONCE=1 deploy/dev/bin/egress-archive.sh     # drain what is there, print, exit

Only the sequencer reads TOML (SPEC.md §6); the followers, resolver, grid and
ingress are configured by command-line flags, so their `.env` files are plain
shell sourced by the scripts, not a second config format.

Journal paths in the TOML resolve against `config/`, which is why
`sequencer.yaml` says `../run/journal/journal.jsonl`. Paths in the `.env` files
resolve against `run/`. Absolute paths are used as-is in both.

Dev defaults that differ from `deploy/config`: log level `debug`, poll intervals
seconds instead of minutes, heap 256m.

## Notes

- Ports: sequencer 8080, resolver 8090, grid 8091. Change them in
  `trex-dev.env` — and the sequencer's also in `config/sequencer.yaml`, which is
  the file the sequencer actually reads.
- No service authenticates. Everything binds loopback; keep it that way.
- The pid files under `run/pids/` are only a convenience for `stop`/`status`. A
  panel killed with `kill -9` leaves a stale file behind; `status` notices,
  because it checks the process is still that service before believing the pid.
- Panel order does not matter. A follower started before the sequencer logs
  `waiting for journal …` and picks up when the file appears (SPEC.md §5.1), so
  a panel never dies on a race you cannot scroll back to.
- `reset.sh` deletes the journal. There is no undo for a decision either — both
  are the real semantics, not a dev shortcut.
