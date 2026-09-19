#!/usr/bin/env bash
#
# trex-sequencer in the foreground — one tmux panel. The only writer of the
# journal; start it first, since everything downstream tails the file it creates.
#
#   sequencer.sh [start|stop|status]
#
# Config: ../config/{sequencer,accounts,transfers}.toml (the whole directory is
# the argument). Port comes from sequencer.toml, not from trex-dev.env.
#
#   curl -s http://127.0.0.1:8080/head      | python3 -m json.tool
#   curl -s http://127.0.0.1:8080/held      | python3 -m json.tool
#   curl -s http://127.0.0.1:8080/review    | python3 -m json.tool
#   curl -s http://127.0.0.1:8080/reconcile | python3 -m json.tool

. "$(dirname "${BASH_SOURCE[0]}")/_common.sh"

svc_start() {
    run_fg sequencer "$CONF"
}

dispatch sequencer "$@"
