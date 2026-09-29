#!/usr/bin/env bash
#
# trex-docker.sh — build the trex images with jib and drive compose.
#
#   trex-docker.sh build [module...]   build images into the Docker daemon
#   trex-docker.sh push  [module...]   build and push to ${TREX_IMAGE_PREFIX}
#   trex-docker.sh up [args...]        docker compose up -d
#   trex-docker.sh down [args...]      docker compose down   (-v discards the journal)
#   trex-docker.sh ps | logs [args...]
#   trex-docker.sh ingest <sourceType> <account> <source>
#   trex-docker.sh env                 print the resolved daemon endpoint
#
# Images are the short names: sequencer ingest egress ws. With none given, all four are built.
#
# The only reason this script exists: jib talks to the daemon through DOCKER_HOST
# and does not read Docker's context file, so `docker context use` alone would not
# reach a remote daemon. Everything below resolves the active context to a
# DOCKER_HOST first, so the Maven build and compose target the same daemon.
#
# Environment:
#   DOCKER_CONTEXT        context to use (default: Docker's current context)
#   DOCKER_HOST           bypasses context resolution entirely
#   TREX_IMAGE_PREFIX     image prefix/registry (default trex; push needs a real one)
#   TREX_IMAGE_TAG        image tag (default: the project version)

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"

MODULES="sequencer ingest egress ws"

die() { echo "trex-docker: $*" >&2; exit 1; }

# jib reads DOCKER_HOST, not ~/.docker/config.json — resolve the context ourselves.
resolve_docker_host() {
    [ -n "${DOCKER_HOST:-}" ] && return 0
    local ctx host
    ctx="${DOCKER_CONTEXT:-$(docker context show 2>/dev/null || echo default)}"
    host="$(docker context inspect "$ctx" --format '{{.Endpoints.docker.Host}}' 2>/dev/null || true)"
    [ -n "$host" ] || die "cannot resolve Docker context '$ctx'"
    export DOCKER_HOST="$host"
}

# The image tag defaults to the project version, so compose and jib agree without
# anyone having to repeat it. Read straight from the parent pom: help:evaluate would
# mean a Maven start-up (and a plugin download) on every compose command.
project_version() {
    local version
    version="$(sed -n 's|^  <version>\(.*\)</version>|\1|p' "$REPO/pom.xml" | head -1)"
    [ -n "$version" ] || die "cannot read <version> from $REPO/pom.xml"
    echo "$version"
}

# `-pl` wants module directories; the CLI takes the short image names.
module_list() {
    local m out=""
    for m in "$@"; do
        case " $MODULES " in
            *" $m "*) out="$out,trex-$m" ;;
            *) die "unknown module: $m (have: $MODULES)" ;;
        esac
    done
    echo "${out#,}"
}

mvn_jib() {
    local profile="$1"; shift
    local pl=""
    # -am is not optional: without it a single-module build resolves trex-core and
    # trex-journal from ~/.m2, and a stale jar there silently produces an image that
    # dies with NoClassDefFoundError at runtime.
    [ "$#" -gt 0 ] && pl="-am -pl $(module_list "$@")"
    echo "trex-docker: daemon $DOCKER_HOST"
    # shellcheck disable=SC2086  # -pl/-am are deliberately unquoted (empty means all)
    (cd "$REPO" && mvn -DskipTests package "-P$profile,v1" $pl)
}

compose() {
    (cd "$REPO" && TREX_IMAGE_TAG="${TREX_IMAGE_TAG:-$(project_version)}" \
        docker compose "$@")
}

case "${1:-}" in
    build)
        shift; resolve_docker_host; mvn_jib docker "$@"
        ;;
    push)
        shift; resolve_docker_host
        [ -n "${TREX_IMAGE_PREFIX:-}" ] \
            || die "set TREX_IMAGE_PREFIX to a registry namespace before pushing"
        mvn_jib docker-push "$@"
        ;;
    up)
        shift; resolve_docker_host; compose up -d "$@"
        ;;
    down|ps|logs|restart|stop|start)
        cmd="$1"; shift; resolve_docker_host; compose "$cmd" "$@"
        ;;
    ingest)
        shift
        [ "$#" -eq 3 ] || die "usage: trex-docker.sh ingest <sourceType> <account> <source>"
        resolve_docker_host
        # The CSV is read by the daemon's host, so mount its directory and pass the
        # basename; TREX_CSV_DIR is what compose.yml binds to /data.
        TREX_CSV_DIR="$(cd "$(dirname "$3")" && pwd)" \
            compose run --rm ingest \
                --source-type "$1" --account "$2" --sequencer-url http://sequencer:8080 \
                "/data/$(basename "$3")"
        ;;
    env)
        resolve_docker_host
        echo "DOCKER_HOST=$DOCKER_HOST"
        echo "TREX_IMAGE_PREFIX=${TREX_IMAGE_PREFIX:-trex}"
        echo "TREX_IMAGE_TAG=${TREX_IMAGE_TAG:-$(project_version)}"
        ;;
    *)
        sed -n '3,25p' "${BASH_SOURCE[0]}" | sed 's|^# \{0,1\}||'
        exit 64
        ;;
esac
