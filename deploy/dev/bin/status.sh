#!/usr/bin/env bash
#
# One-line status per dev service, plus where the data lives.
#
#   status.sh

. "$(dirname "${BASH_SOURCE[0]}")/_common.sh"

for svc in sequencer egress-archive egress-sqlite ws; do
    status_svc "$svc"
done

echo
echo "  sequencer API  http://$TREX_BIND:$TREX_SEQ_PORT"
echo "  admin UI       http://127.0.0.1:$TREX_ADMIN_PORT  (full UI and writes)"
echo "  read UI        http://$TREX_BIND:$TREX_WEB_PORT  (read-only pages and API)"
echo "  journal        $RUN/journal/journal.jsonl"
if [ -f "$RUN/journal/journal.jsonl" ]; then
    echo "  journal lines  $(wc -l < "$RUN/journal/journal.jsonl")"
fi
