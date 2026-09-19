#!/usr/bin/env bash
#
# The same six-panel tmux layout as tmux.sh, but every panel is a plain shell
# in deploy/dev — nothing is started. For running the component scripts by hand,
# in whatever order, or with ONCE=1 and friends.
#
#   tmux-shells.sh           create the session (or attach if it exists)
#   tmux-shells.sh --kill    kill the session and its panels
#
# Layout (tiled, 2 columns x 3 rows). Session name: TREX_TMUX_SHELLS_SESSION,
# default trex-shells, so it can sit next to the tmux.sh session.

. "$(dirname "${BASH_SOURCE[0]}")/_common.sh"

SESSION="${TREX_TMUX_SHELLS_SESSION:-trex-shells}"
PANELS=6

command -v tmux > /dev/null || die "tmux not installed"

if [ "${1:-}" = "--kill" ]; then
    tmux kill-session -t "$SESSION" 2>/dev/null && echo "trex-dev: killed session $SESSION" \
        || echo "trex-dev: no session $SESSION"
    exit 0
fi

attach() {
    if [ ! -t 1 ]; then
        # No terminal (called from a script or an agent): leave it detached.
        # Checked before $TMUX, or switch-client hijacks whatever client
        # the caller's tmux session happens to be attached to.
        echo "trex-dev: session $SESSION running detached — tmux attach -t $SESSION"
    elif [ -n "${TMUX:-}" ]; then
        tmux switch-client -t "$SESSION"
    else
        tmux attach -t "$SESSION"
    fi
}

if tmux has-session -t "$SESSION" 2>/dev/null; then
    echo "trex-dev: session $SESSION exists — attaching (use --kill to start over)"
    attach
    exit 0
fi

tmux new-session -d -s "$SESSION" -n shells -c "$TREX_DEV"
for _ in $(seq 2 "$PANELS"); do
    tmux split-window -t "$SESSION:shells" -c "$TREX_DEV"
    tmux select-layout -t "$SESSION:shells" tiled > /dev/null
done
tmux select-pane -t "$SESSION:shells.{top-left}"

attach
