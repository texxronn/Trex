#!/usr/bin/env bash
#
# trex-grid in the foreground — read-only journal grid (SPEC.md §5.5).
#
#   grid.sh [start|stop|status]
#
# Config: ../config/grid.env plus TREX_BIND / TREX_GRID_PORT from
# ../trex-dev.env. Needs no sequencer: it only tails the journal file, so it is
# also the easiest way to inspect a captured journal.

. "$(dirname "${BASH_SOURCE[0]}")/_common.sh"

svc_start() {
    load_env grid
    local args=(--journal "$(in_run "$JOURNAL")"
        --bind "$TREX_BIND" --port "$TREX_GRID_PORT" --poll-ms "$POLL_MS")
    # Categories are derived here from the rules file; without it every row reads
    # UNCATEGORIZED (SPEC.md §5.6). Edit it and restart to recategorise everything.
    [ -f "$CATEGORIES" ] && args+=(--categories "$CATEGORIES")
    run_fg grid "${args[@]}"
}

dispatch grid "$@"
