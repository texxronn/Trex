#!/usr/bin/env bash
#
# trex-egress-archive in the foreground — log-mirror follower (SPEC.md §5.2).
#
#   egress-archive.sh [start|stop|status]
#
# Config: ../config/egress-archive.env. ONCE=1 drains and exits instead of
# tailing, which is the quick way to see what is in the journal right now.

. "$(dirname "${BASH_SOURCE[0]}")/_common.sh"

svc_start() {
    load_env egress-archive
    local journal
    journal="$(in_run "$JOURNAL")"
    local args=(--journal "$journal" --archive "$(in_run "$ARCHIVE")"
                --poll-seconds "$POLL_SECONDS")
    [ "${ONCE:-0}" = 1 ] && args+=(--once)
    run_fg egress-archive "${args[@]}"
}

dispatch egress-archive "$@"
