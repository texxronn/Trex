#!/usr/bin/env sh
# Seed the v2 system from the private v1 journal (V2-IMPLEMENTATION-PLAN.md §3 P0.7).
#
# The real journal is private and is not in the repository. Point TREX_DEV_FIXTURE at it, or drop
# it at deploy/dev/journal/ (git-ignored). This script is a dev tool: it starts the sequencer,
# imports the v1-format journal through the API, stops the sequencer and rebuilds the index.
#
#   TREX_DEV_FIXTURE=/path/to/journal.jsonl  seed.sh
#
set -eu

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)

fixture=${TREX_DEV_FIXTURE:-$here/journal}
if [ -d "$fixture" ]; then
  fixture="$fixture/journal.jsonl"
fi
if [ ! -f "$fixture" ]; then
  echo "no v1 journal at $fixture — set TREX_DEV_FIXTURE or place it at deploy/dev/journal/journal.jsonl" >&2
  exit 64
fi

config=${TREX_DEV_CONFIG:-$root/deploy/config}
jar=${TREX_JAR:-$root/trex-v2-dist/target/trex-v2.jar}
state=${TREX_DEV_STATE:-$here/run/v2}
journal="$state/trex.jsonl"
index="$state/trex.sqlite"
port=${TREX_DEV_PORT:-18080}
asof=${TREX_AS_OF:-$(date -u +%Y-%m-%dT%H:%M:%SZ)}

if [ ! -f "$jar" ]; then
  echo "missing $jar — build it with: mvn -pl trex-v2-dist -am package" >&2
  exit 1
fi

mkdir -p "$state"
rm -f "$journal" "$journal.lock" "$index" "$index.lock"

echo "starting sequencer on 127.0.0.1:$port (journal $journal)"
java -jar "$jar" sequencer --journal "$journal" --config "$config" --port "$port" &
seq_pid=$!
trap 'kill "$seq_pid" 2>/dev/null || true' EXIT

i=0
until curl -sf "http://127.0.0.1:$port/head" >/dev/null 2>&1; do
  i=$((i + 1))
  if [ "$i" -gt 50 ]; then echo "sequencer did not start" >&2; exit 1; fi
  sleep 0.2
done

echo "importing $fixture"
java -jar "$jar" import --journal-v1 "$fixture" --sequencer-url "http://127.0.0.1:$port" \
  --pins "$config/pins.yaml"

kill "$seq_pid" 2>/dev/null || true
wait "$seq_pid" 2>/dev/null || true
trap - EXIT

echo "rebuilding the index"
java -jar "$jar" index --journal "$journal" --config "$config" --index "$index" --rebuild --as-of "$asof"
echo "seeded: journal $journal, index $index"
