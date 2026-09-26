#!/usr/bin/env bash
#
# Lay the dev stack out in one tmux window: a panel per component, each running
# its own script in the foreground, plus a free shell for curl and ingest.
#
#   tmux.sh              create the session (or attach if it exists)
#   tmux.sh --kill       kill the session and its panels
#
# Layout (tiled):
#   sequencer | egress-archive | egress-sqlite | ws | shell
#
# Ctrl-C in a panel stops just that service; its script can be re-run in place
# with the up arrow. Killing the session TERMs everything.

. "$(dirname "${BASH_SOURCE[0]}")/_common.sh"

SESSION="${TREX_TMUX_SESSION:-trex-dev}"
BIN="$(dirname "${BASH_SOURCE[0]}")"
BIN="$(cd "$BIN" && pwd)"

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

# The sequencer owns the journal, so it gets the first panel and a head start;
# the followers just retry until the file exists, but the log reads better.
tmux new-session -d -s "$SESSION" -n trex -c "$TREX_DEV" "$BIN/sequencer.sh"
for svc in egress-archive egress-sqlite ws; do
    tmux split-window -t "$SESSION:trex" -c "$TREX_DEV" "$BIN/$svc.sh"
    tmux select-layout -t "$SESSION:trex" tiled > /dev/null
done
tmux split-window -t "$SESSION:trex" -c "$TREX_DEV"
tmux select-layout -t "$SESSION:trex" tiled > /dev/null
tmux send-keys -t "$SESSION:trex" "$BIN/status.sh" C-m

attach
