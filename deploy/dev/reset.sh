#!/usr/bin/env sh
# Wipe the v2 dev run state so seed.sh can start from an empty log (V2-IMPLEMENTATION-PLAN.md §1.3).
set -eu

here=$(cd "$(dirname "$0")" && pwd)
state=${TREX_DEV_STATE:-$here/run/v2}

rm -rf "$state"
echo "removed $state"
