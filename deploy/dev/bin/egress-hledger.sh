#!/usr/bin/env bash
#
# trex-egress-hledger — one-shot ledger regeneration (SPEC.md §5.9). Not a
# service: it reads the gateway, rewrites the file whole and exits. There is no
# cache and no cursor, because the file is replaced every time.
#
#   egress-hledger.sh              regenerate
#   egress-hledger.sh check        regenerate, then let hledger validate it
#
# `check` is the point of this egress: hledger verifies every account against the
# bank's own running balance and fails at the transaction where it first stops
# being true. Needs the gateway running.
#
# Config: ../config/egress-hledger.env and ../config/hledger.yaml.

. "$(dirname "${BASH_SOURCE[0]}")/_common.sh"

cmd="${1:-run}"
case "$cmd" in
    run|check) ;;
    *) die "usage: $(basename "$0") [run|check]" ;;
esac

load_env egress-hledger
url="${GATEWAY_URL:-}"
[ -n "$url" ] || url="http://127.0.0.1:$TREX_GATEWAY_PORT"

out="$(in_run "$OUT")"
mkdir -p "$(dirname "$out")"

args=(--gateway-url "$url" --out "$out" --accounts "$ACCOUNTS")
[ "${NO_ASSERT:-0}" = 1 ] && args+=(--no-assert)

# shellcheck disable=SC2086  # JAVA_OPTS is deliberately word-split
"$(java_bin)" $JAVA_OPTS \
    "-Dorg.slf4j.simpleLogger.defaultLogLevel=$TREX_LOG_LEVEL" \
    -jar "$(jar_for egress)" hledger "${args[@]}"

[ "$cmd" = check ] || exit 0

command -v hledger >/dev/null || die "hledger is not on PATH — install it, or run without 'check'"
echo
echo "trex-dev: hledger check accounts ordereddates assertions"
hledger -f "$out" check accounts ordereddates assertions
echo "trex-dev: all checks passed"
echo
hledger -f "$out" bal assets liabilities --flat -N
