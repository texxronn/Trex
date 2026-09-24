#!/usr/bin/env bash
#
# One-line status per dev service, plus where the data lives.
#
#   status.sh

. "$(dirname "${BASH_SOURCE[0]}")/_common.sh"

for svc in sequencer egress-archive egress-sqlite web; do
    status_svc "$svc"
done

echo
echo "  sequencer API  http://$TREX_BIND:$TREX_SEQ_PORT"
echo "  web            http://$TREX_BIND:$TREX_WEB_PORT  (browse; /resolve for the worklist)"
echo "  journal        $RUN/journal/journal.jsonl"
if [ -f "$RUN/journal/journal.jsonl" ]; then
    echo "  journal lines  $(wc -l < "$RUN/journal/journal.jsonl")"
fi
