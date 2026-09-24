#!/usr/bin/env bash
#
# trex-web in the foreground — the journal browser at / and the HELD/REVIEW
# worklist at /resolve (SPEC.md §5.4).
#
#   web.sh [start|stop|status]
#
# Config: ../config/web.env plus TREX_BIND / TREX_WEB_PORT / TREX_SEQ_PORT from
# ../trex-dev.env. It tails the journal itself, but every decision goes to the
# sequencer's POST /decisions, so that must be up to resolve anything.
#
# Decisions are final — there is no undo, in dev either. Categories are derived
# and cost nothing to change.

. "$(dirname "${BASH_SOURCE[0]}")/_common.sh"

svc_start() {
    load_env web
    local url="${SEQUENCER_URL:-}"
    [ -n "$url" ] || url="http://$TREX_BIND:$TREX_SEQ_PORT"
    local args=(--journal "$(in_run "$JOURNAL")" --sequencer-url "$url"
        --bind "$TREX_BIND" --port "$TREX_WEB_PORT" --poll-ms "$POLL_MS")
    # Categories come from the config directory; without it every row reads
    # UNCATEGORIZED (SPEC.md §5.6). Edit the rules and restart to recategorise.
    [ -d "$CONFIG" ] && args+=(--config "$CONFIG")
    run_fg web "${args[@]}"
}

dispatch web "$@"
