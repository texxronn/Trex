#!/usr/bin/env bash
#
# trex-gateway in the foreground — the consumer API (SPEC.md §5.7): the journal
# folded and categorised, its rule files, and the decisions path to the sequencer.
#
#   gateway.sh [start|stop|status]
#
# Config: ../config/gateway.env plus TREX_BIND / TREX_GATEWAY_PORT / TREX_SEQ_PORT
# from ../trex-dev.env.
#
# Start this before web.sh: trex-web is a client of it and shows a stale-or-empty
# page without it. Decisions are final — there is no undo, in dev either. Categories
# are derived and cost nothing to change.

. "$(dirname "${BASH_SOURCE[0]}")/_common.sh"

svc_start() {
    load_env gateway
    local url="${SEQUENCER_URL:-}"
    [ -n "$url" ] || url="http://$TREX_BIND:$TREX_SEQ_PORT"
    # Loopback regardless of TREX_BIND: this service writes the rule files and
    # forwards decisions, with no authentication (SPEC.md §5.7). Expose web.sh.
    local args=(--journal "$(in_run "$JOURNAL")" --sequencer-url "$url"
        --bind 127.0.0.1 --port "$TREX_GATEWAY_PORT" --poll-ms "$POLL_MS")
    [ -d "$CONFIG" ] && args+=(--config "$CONFIG")
    run_fg gateway "${args[@]}"
}

dispatch gateway "$@"
