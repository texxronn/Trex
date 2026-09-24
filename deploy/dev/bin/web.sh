#!/usr/bin/env bash
#
# trex-web in the foreground — the pages (SPEC.md §5.4): the journal browser at /
# and the HELD/REVIEW worklist at /resolve.
#
#   web.sh [start|stop|status]
#
# Config: ../config/web.env plus TREX_BIND / TREX_WEB_PORT / TREX_GATEWAY_PORT from
# ../trex-dev.env.
#
# It holds no journal state and reads no config file: everything comes from
# trex-gateway, so start gateway.sh first. This is the only service worth exposing
# beyond loopback, and even then it can still resolve transactions — there is no
# authentication (SPEC.md §9).

. "$(dirname "${BASH_SOURCE[0]}")/_common.sh"

svc_start() {
    load_env web
    local url="${GATEWAY_URL:-}"
    [ -n "$url" ] || url="http://127.0.0.1:$TREX_GATEWAY_PORT"
    run_fg web --gateway-url "$url" --bind "$TREX_BIND" --port "$TREX_WEB_PORT"
}

dispatch web "$@"
