#!/usr/bin/env bash
#
# Stop every dev service, followers first so the sequencer closes last.
#
#   stop-all.sh

. "$(dirname "${BASH_SOURCE[0]}")/_common.sh"

for svc in web egress-sqlite egress-archive sequencer; do
    stop_svc "$svc"
done
