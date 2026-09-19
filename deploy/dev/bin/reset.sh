#!/usr/bin/env bash
#
# Wipe the dev run directory — journal, archive, sqlite db, pid files — so the
# next start begins from an empty journal. Config and samples are untouched.
#
#   reset.sh          refuse if anything is still running
#   reset.sh --force  stop everything first, then wipe

. "$(dirname "${BASH_SOURCE[0]}")/_common.sh"

force=0
[ "${1:-}" = "--force" ] && force=1

running=""
for svc in sequencer egress-archive egress-sqlite resolver grid; do
    if pid_of "$svc" > /dev/null; then
        running="$running $svc"
    fi
done

if [ -n "$running" ]; then
    if [ "$force" = 0 ]; then
        die "still running:$running — stop them, or rerun with --force"
    fi
    "$(dirname "${BASH_SOURCE[0]}")/stop-all.sh"
fi

# Guard against a mis-set TREX_DEV_RUN deleting something else.
case "$RUN" in
    */run) ;;
    *) die "refusing to delete '$RUN': TREX_DEV_RUN must end in /run" ;;
esac

rm -rf "$RUN"
dirs
echo "trex-dev: wiped $RUN"
