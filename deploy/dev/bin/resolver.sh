#!/usr/bin/env bash
#
# trex-resolver in the foreground — manual resolver admin UI (SPEC.md §5.4).
#
#   resolver.sh [start|stop|status]
#
# Config: ../config/resolver.env plus TREX_BIND / TREX_RESOLVER_PORT /
# TREX_SEQ_PORT from ../trex-dev.env. Needs the sequencer up: it reads the
# journal itself but posts every decision to POST /decisions.
#
# Decisions are final — there is no undo, in dev either.

. "$(dirname "${BASH_SOURCE[0]}")/_common.sh"

svc_start() {
    load_env resolver
    local url="${SEQUENCER_URL:-}"
    [ -n "$url" ] || url="http://$TREX_BIND:$TREX_SEQ_PORT"
    run_fg resolver --journal "$(in_run "$JOURNAL")" --sequencer-url "$url" \
        --bind "$TREX_BIND" --port "$TREX_RESOLVER_PORT" --poll-ms "$POLL_MS"
}

dispatch resolver "$@"
