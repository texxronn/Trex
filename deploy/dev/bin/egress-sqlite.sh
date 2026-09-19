#!/usr/bin/env bash
#
# trex-egress-sqlite in the foreground — SQLite WAL projection (SPEC.md §5.3).
#
#   egress-sqlite.sh [start|stop|status]
#
# Config: ../config/egress-sqlite.env. The db is rebuilt from the journal, so
# deleting it is always safe: bin/reset.sh does that.

. "$(dirname "${BASH_SOURCE[0]}")/_common.sh"

svc_start() {
    load_env egress-sqlite
    local journal
    journal="$(in_run "$JOURNAL")"
    local args=(--journal "$journal" --db "$(in_run "$DB")"
                --poll-seconds "$POLL_SECONDS")
    [ "${ONCE:-0}" = 1 ] && args+=(--once)
    run_fg egress-sqlite "${args[@]}"
}

dispatch egress-sqlite "$@"
