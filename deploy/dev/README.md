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
    deploy/dev/bin/gateway.sh
    deploy/dev/bin/web.sh

Each script takes `start` (the default), `stop` or `status`. `start` runs the
JVM in the foreground, so the panel is the log and **Ctrl-C is the stop**;
`stop` exists for driving a panel from somewhere else and for scripting:

    deploy/dev/bin/web.sh stop
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

Web UI: <http://127.0.0.1:8090>  ·  worklist at <http://127.0.0.1:8090/resolve>

## Project it out

Both egresses are **one-shot**, like `ingest.sh` and unlike everything above:
they make one pass and exit, so they take a verb rather than `start`/`stop`.
Both read the gateway, so it has to be running.

    deploy/dev/bin/egress-hledger.sh          # regenerate run/hledger/trex.journal
    deploy/dev/bin/egress-hledger.sh check    # regenerate, then let hledger validate it

`check` is the reason this one exists. hledger verifies every account against the
bank's own running balance and fails at the transaction where it first stops being
true — a check nothing else here performs, for the cost of one file rewrite. It is
the cheapest way to find out that a statement is missing, and it says where.

    deploy/dev/bin/egress-firefly.sh accounts   # what Firefly has, beside firefly.yaml
    deploy/dev/bin/egress-firefly.sh dry-run    # print the postings, write nothing
    deploy/dev/bin/egress-firefly.sh            # project
    deploy/dev/bin/egress-firefly.sh verify     # rebuild the cache from Firefly first

Start with `accounts`. Posting into the wrong account is not recoverable except one
transaction at a time, so read the mapping before the first run. The token comes
from the environment and never from config (SPEC §6):

    set -a; . ~/.config/trex/firefly.env; set +a

Neither is a tmux panel: a panel is a log you watch, and these are commands you run.

## Config

| file | component | shape |
|---|---|---|
| `trex-dev.env` | all | bind address, ports, log level, `JAVA_OPTS` |
| `config/egress-hledger.env` | egress-hledger | output path, gateway URL, assertions on/off |
| `config/hledger.yaml` | egress-hledger | account tree, income categories, suspense and equity names |
| `config/egress-firefly.env` | egress-firefly | Firefly URL, cache, retry policy (**no token**) |
| `config/sequencer.yaml` | trex-sequencer | YAML, `bindPort` + `journal:` |
| `config/accounts.yaml` | trex-sequencer | YAML, the account registry |
| `config/transfers.yaml` | trex-sequencer | YAML, allowlist + `windowDays` |
| `config/categories.yaml` | trex-web | YAML, master category rules — hand-written, ordered |
| `config/pins.yaml` | trex-web | YAML, one-off overrides — order-independent, machine-writable |
| `config/egress-archive.env` | trex-egress-archive | shell vars → CLI flags |
| `config/egress-sqlite.env` | trex-egress-sqlite | shell vars → CLI flags |
| `config/web.env` | trex-web | shell vars → CLI flags |
| `config/ingress.env` | trex-ingress | shell vars → CLI flags |

Every value in a `.env` file is written `VAR="${VAR:-default}"`, so exporting
one wins over the file for a single run:

    ONCE=1 deploy/dev/bin/egress-archive.sh     # drain what is there, print, exit

The sequencer reads `sequencer.yaml`, `accounts.yaml` and `transfers.yaml`;
trex-gateway reads `categories.yaml` and `pins.yaml` and is their single writer
(SPEC.md §6). Everything else — the followers, trex-web and ingress — is
configured by command-line flags, so their `.env` files are plain shell sourced
by the scripts, not a second config format.

Journal paths in `sequencer.yaml` resolve against `config/`, which is why
`sequencer.yaml` says `../run/journal/journal.jsonl`. Paths in the `.env` files
resolve against `run/`. Absolute paths are used as-is in both.

Dev defaults that differ from `deploy/config`: log level `debug`, poll intervals
seconds instead of minutes, heap 256m.

## Notes

- Ports: sequencer 8080, web 8090. Change them in
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
- **`TREX_DEV_RUN` does not move the journal**, and the sequencer refuses to
  start if you assume otherwise. It relocates the pid files, the archive and the
  sqlite mirror, but the sequencer reads `journal.target` from
  `config/sequencer.yaml`, which resolves relative to the **config** directory —
  so a scratch `TREX_DEV_RUN` gives you a scratch everything except the one file
  that matters, and appends to the real dev journal instead. `sequencer.sh` now
  compares the two and stops with both paths printed. To get a genuinely
  isolated instance, copy `deploy/dev/config` as well and point `journal.source`
  and `journal.target` at the new run directory.
