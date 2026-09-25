#!/usr/bin/env bash
#
# trex-ws in the foreground — the consumer API (SPEC.md §5.7) and the pages (§5.4):
# the journal folded and categorised, its rule files, the decisions path to the
# sequencer, and the browser UI, all in one process.
#
#   ws.sh [start|stop|status]
#
# TWO LISTENERS, and the split is the security boundary (SPEC.md §5.7). While the
# consumer API was its own process it bound loopback and only the page server was
# ever exposed; merging them replaced that with a listener boundary here:
#
#   admin  127.0.0.1:$TREX_ADMIN_PORT  every write, never bindable elsewhere
#   read   $TREX_BIND:$TREX_WEB_PORT   pages and reads, safe on another interface
#
# So TREX_BIND moves only the read listener. Pointing it at 0.0.0.0 to reach the
# UI from a phone does not expose the rule writer — a mutating route is not
# registered on that listener at all, and ListenerRolesTest asserts it 404s.
#
# Config: ../config/ws.env plus TREX_BIND / TREX_ADMIN_PORT / TREX_WEB_PORT /
# TREX_SEQ_PORT from ../trex-dev.env.
#
# Decisions are final — there is no undo, in dev either. Categories are derived
# and cost nothing to change.

. "$(dirname "${BASH_SOURCE[0]}")/_common.sh"

svc_start() {
    load_env ws
    local url="${SEQUENCER_URL:-}"
    [ -n "$url" ] || url="http://$TREX_BIND:$TREX_SEQ_PORT"
    local args=(--journal "$(in_run "$JOURNAL")" --sequencer-url "$url"
        --bind "$TREX_BIND" --port "$TREX_WEB_PORT" --admin-port "$TREX_ADMIN_PORT"
        --poll-ms "$POLL_MS")
    [ -d "$CONFIG" ] && args+=(--config "$CONFIG")
    run_fg ws "${args[@]}"
}

dispatch ws "$@"
