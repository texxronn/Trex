#!/usr/bin/env bash
# Daily local copy of trex's irreplaceable state on a docker-compose host (V2-ARCHIVE-PLAN.md).
# Run as root by trex-backup.timer at 03:00, after the runner's 02:30 `journal-snapshot`.
#
# The journal is covered by the runner's snapshot (archive volume, journal/trex-<ts>.jsonl.gz);
# this script adds what the snapshot does not hold — evidence and config — and checks that the
# newest snapshot is sound. Everything is kept (no pruning, V2-ARCHIVE-PLAN.md A-4).
#
#   /opt/trex/backups/daily/<YYYY-MM-DD>/{evidence,config}/
#
# `rsync --link-dest` hard-links every file unchanged since the previous day, so a day that ingested
# nothing costs only directory entries. Evidence is content-addressed and never rewritten, so its
# copies are almost all links.
#
# Exit non-zero on any failed check so systemd marks the unit failed (`systemctl --failed`).
set -euo pipefail

# Dates (the folder name, "today's snapshot") are the operator's local days, not the host's UTC.
export TZ=${TREX_BACKUP_TZ:-Australia/Sydney}
PROJECT=${TREX_COMPOSE_PROJECT:-trex-v2}
DEST=${TREX_BACKUP_DIR:-/opt/trex/backups/daily}
TODAY=$(date +%F)
fail() { echo "trex-backup: $*" >&2; exit 1; }
mountpoint_of() { docker volume inspect -f '{{.Mountpoint}}' "${PROJECT}_$1"; }

[ "$(id -u)" = 0 ] || fail "run as root"
command -v rsync >/dev/null || fail "rsync is not installed (apt-get install rsync)"

EVIDENCE=$(mountpoint_of evidence)
CONFIG=$(mountpoint_of config)
ARCHIVE=$(mountpoint_of archive)

# 1. Evidence + config, hard-linked against the newest earlier day.
mkdir -p "$DEST"
PREV=$(find "$DEST" -mindepth 1 -maxdepth 1 -type d -name '????-??-??' ! -name "$TODAY" | sort | tail -1)
OUT="$DEST/$TODAY"
mkdir -p "$OUT"
for part in evidence config; do
  src=$EVIDENCE; [ "$part" = config ] && src=$CONFIG
  link=()
  [ -n "$PREV" ] && [ -d "$PREV/$part" ] && link=(--link-dest "$PREV/$part")
  rsync -a --delete "${link[@]}" "$src/" "$OUT/$part/"
done
echo "trex-backup: copied evidence ($(find "$OUT/evidence" -type f | wc -l) files) and config to $OUT"

# 2. The newest journal snapshot must be today's, intact, and not shorter than the one before it:
#    the log only grows, so a shrinking snapshot means a wrong or truncated copy.
mapfile -t SNAPS < <(find "$ARCHIVE/journal" -maxdepth 1 -name 'trex-*.jsonl.gz' 2>/dev/null | sort)
[ "${#SNAPS[@]}" -gt 0 ] || fail "no journal snapshot in $ARCHIVE/journal (is the journal-snapshot schedule entry on?)"
NEWEST=${SNAPS[-1]}
find "$NEWEST" -newermt "$TODAY" | grep -q . || fail "newest snapshot $NEWEST is not from today"
gzip -t "$NEWEST" || fail "newest snapshot $NEWEST fails gzip -t"
N_NEW=$(gzip -dc "$NEWEST" | wc -l)
if [ "${#SNAPS[@]}" -gt 1 ]; then
  N_PREV=$(gzip -dc "${SNAPS[-2]}" | wc -l)
  [ "$N_NEW" -ge "$N_PREV" ] || fail "newest snapshot has $N_NEW lines, fewer than the previous $N_PREV"
fi
echo "trex-backup: snapshot $(basename "$NEWEST") ok ($N_NEW lines, ${#SNAPS[@]} kept)"
