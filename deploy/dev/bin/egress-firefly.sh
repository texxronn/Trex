#!/usr/bin/env bash
#
# trex-egress-firefly — one-shot projection into Firefly III (SPEC.md §5.8). Not
# a service: it makes one pass and exits.
#
#   egress-firefly.sh                 project
#   egress-firefly.sh dry-run         print what would be posted, write nothing
#   egress-firefly.sh accounts        list Firefly's accounts beside firefly.yaml
#   egress-firefly.sh verify          rebuild the cache from Firefly, then project
#
# Start with `accounts`: posting into the wrong account is not recoverable except
# one transaction at a time, so the mapping is worth reading before the first run.
#
# Needs the gateway running and FIREFLY_TOKEN in the environment:
#   set -a; . ~/.config/trex/firefly.env; set +a
#
# Config: ../config/egress-firefly.env and ../config/firefly.yaml.

. "$(dirname "${BASH_SOURCE[0]}")/_common.sh"

cmd="${1:-run}"
case "$cmd" in
    run|dry-run|accounts|verify) ;;
    *) die "usage: $(basename "$0") [run|dry-run|accounts|verify]" ;;
esac

load_env egress-firefly
[ -n "${FIREFLY_TOKEN:-}" ] || die "FIREFLY_TOKEN is not set.
A token belongs in the environment, never in config or in the repo (SPEC §6):
  set -a; . ~/.config/trex/firefly.env; set +a"

url="${GATEWAY_URL:-}"
[ -n "$url" ] || url="http://127.0.0.1:$TREX_GATEWAY_PORT"

args=(--gateway-url "$url" --firefly-url "$FIREFLY_URL" --accounts "$ACCOUNTS"
      --retries "$RETRIES" --retry-base-ms "$RETRY_BASE_MS" --retry-max-ms "$RETRY_MAX_MS")
[ -n "${CACHE:-}" ] && args+=(--cache "$(in_run "$CACHE")")

case "$cmd" in
    dry-run)  args+=(--dry-run) ;;
    accounts) args+=(--print-accounts) ;;
    verify)   args+=(--verify) ;;
esac

# shellcheck disable=SC2086  # JAVA_OPTS is deliberately word-split
exec "$(java_bin)" $JAVA_OPTS \
    "-Dorg.slf4j.simpleLogger.defaultLogLevel=$TREX_LOG_LEVEL" \
    -jar "$(jar_for egress-firefly)" "${args[@]}"
