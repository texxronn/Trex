#!/usr/bin/env bash
#
# dev.sh — the local v2 dev stack: the same shape as the trex host, on this machine's Docker
# daemon, with the UI served from the working tree.
#
#   dev.sh build             build the image locally (needed after a Java change)
#   dev.sh up                start/recreate the stack (picks up UI edits with no rebuild)
#   dev.sh down              stop, keep volumes
#   dev.sh reset             stop and delete the volumes (day 0 again)
#   dev.sh ingest [DIR]      ingest statements (default ~/Downloads/Statements/Statements_CSV)
#   dev.sh sync-config [--adopt FILE]  update the volume's repo-newer config files (QOL §3)
#   dev.sh ps | logs [svc]   convenience
#
# Iterating on the UI: edit trex-v2-hub/src/main/resources/trex/v2/hub/web/*, reload
# http://localhost:8090 — the hub reads them from the working tree (compose.dev.yml).
# Iterating on Java: dev.sh build, then dev.sh up.
#
# The default Docker context wins; a DOCKER_CONTEXT/DOCKER_HOST set for the remote host is
# cleared here so a dev build can never land on the deployment machine.

set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/../.." && pwd)"

export TREX_WEB_DIR="$repo/trex-v2-hub/src/main/resources/trex/v2/hub/web"
# The dev Firefly III (a local container, published on the host). A container reaches it through the
# host gateway; the API token comes from ~/.config/trex/firefly.env and is never committed.
if [ -z "${FIREFLY_TOKEN:-}" ] && [ -f "$HOME/.config/trex/firefly.env" ]; then
    set -a; . "$HOME/.config/trex/firefly.env"; set +a
fi
export TREX_FIREFLY_URL="${TREX_FIREFLY_URL:-http://host.docker.internal:8083}"
export TREX_IMAGE_TAG="${TREX_IMAGE_TAG:-$(sed -n 's|^  <version>\(.*\)</version>|\1|p' "$repo/pom.xml" | head -1)}"

compose=(docker compose -f "$repo/deploy/v2/compose.yml" -f "$repo/deploy/v2/compose.dev.yml")

die() { echo "dev: $*" >&2; exit 1; }

local_docker() {
    unset DOCKER_HOST
    export DOCKER_CONTEXT=default
}

build() {
    local_docker
    echo "== building the image locally"
    (cd "$repo" && deploy/bin/trex-v2-docker.sh build)
}

# Config drift (QOL_Improvements.md §3): compare ., .shipped and .base under the config volume and
# update only the files the repo moved while you did not — in a throwaway alpine container, so the
# host needs no tools and the script itself never runs on this machine's filesystem.
sync_config() {
    local_docker
    docker run --rm -i -v trex-v2_config:/etc/trex -e ADOPT="${1:-}" alpine \
        sh -s < "$here/sync-config.sh"
}

case "${1:-up}" in
    build) build ;;
    up)
        local_docker
        "${compose[@]}" up -d
        echo "trex ui: http://localhost:8090"
        ;;
    down) local_docker; "${compose[@]}" down ;;
    reset)
        local_docker
        "${compose[@]}" down -v
        echo "volumes removed — next 'dev.sh up' is day 0"
        ;;
    ingest)
        local_docker
        dir="${2:-$HOME/Downloads/Statements/Statements_CSV}"
        TREX_COMPOSE_DIR="$repo/deploy/v2" "$repo/deploy/v2/ingest-all.sh" "$dir"
        ;;
    sync-config)
        adopt=""
        if [ "${2:-}" = "--adopt" ]; then
            adopt="${3:?--adopt needs a file}"
        elif [ -n "${2:-}" ]; then
            die "usage: dev.sh sync-config [--adopt FILE]"
        fi
        sync_config "$adopt"
        ;;
    ps) local_docker; "${compose[@]}" ps ;;
    logs) shift; local_docker; "${compose[@]}" logs --tail 50 "$@" ;;
    *) sed -n '3,23p' "$0" | sed 's|^# \{0,1\}||' ;;
esac
