#!/usr/bin/env bash
#
# trex-ingest — one-shot ingest (SPEC.md §4). Not a service: it validates the
# whole source and sends nothing if any row is bad.
#
#   ingest.sh <sourceType> <accountRef> <source>
#   ingest.sh ing-csv ing-savings ../samples/ing-savings.csv
#
# accountRef must exist in ../config/accounts.yaml. Relative paths resolve
# against the current directory. Config: ../config/ingress.env.

. "$(dirname "${BASH_SOURCE[0]}")/_common.sh"

[ "$#" -eq 3 ] || die "usage: $(basename "$0") <sourceType> <accountRef> <source>"
source_type="$1"
account="$2"
file="$3"
[ -f "$file" ] || die "no such file: $file"

load_env ingest
url="${SEQUENCER_URL:-}"
[ -n "$url" ] || url="http://$TREX_BIND:$TREX_SEQ_PORT"

args=(--source-type "$source_type" --account "$account" --sequencer-url "$url" --batch-rows "$BATCH_ROWS")
[ "${GZIP:-1}" = 0 ] && args+=(--no-gzip)

# shellcheck disable=SC2086  # JAVA_OPTS is deliberately word-split
exec "$(java_bin)" $JAVA_OPTS \
    "-Dorg.slf4j.simpleLogger.defaultLogLevel=$TREX_LOG_LEVEL" \
    -jar "$(jar_for ingest)" "${args[@]}" "$file"
