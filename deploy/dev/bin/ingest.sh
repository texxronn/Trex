#!/usr/bin/env bash
#
# trex-ingress-ing — one-shot ING CSV ingest (SPEC.md §4). Not a service: it
# validates the whole file and sends nothing if any row is bad.
#
#   ingest.sh <accountRef> <file.csv>
#   ingest.sh ing-savings ../samples/ing-savings.csv
#
# accountRef must exist in ../config/accounts.toml. Relative paths resolve
# against the current directory. Config: ../config/ingress-ing.env.

. "$(dirname "${BASH_SOURCE[0]}")/_common.sh"

[ "$#" -eq 2 ] || die "usage: $(basename "$0") <accountRef> <file.csv>"
account="$1"
file="$2"
[ -f "$file" ] || die "no such file: $file"

load_env ingress-ing
url="${SEQUENCER_URL:-}"
[ -n "$url" ] || url="http://$TREX_BIND:$TREX_SEQ_PORT"

args=(--account "$account" --url "$url" --batch-rows "$BATCH_ROWS")
[ "${GZIP:-1}" = 0 ] && args+=(--no-gzip)

# shellcheck disable=SC2086  # JAVA_OPTS is deliberately word-split
exec "$(java_bin)" $JAVA_OPTS \
    "-Dorg.slf4j.simpleLogger.defaultLogLevel=$TREX_LOG_LEVEL" \
    -jar "$(jar_for ingress-ing)" "${args[@]}" "$file"
