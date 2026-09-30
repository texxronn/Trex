#!/usr/bin/env bash
#
# trex-v2-docker.sh — build/push the v2 image with jib, and drive the v2 compose stack,
# through a Docker context. The v2 counterpart of the v1 trex-docker.sh (archived in ../TrexV1).
#
#   trex-v2-docker.sh build [--context NAME]   build the image into the selected daemon
#   trex-v2-docker.sh push  [--context NAME]   build and push to ${TREX_IMAGE_PREFIX}
#   trex-v2-docker.sh up|down|ps|logs|restart|stop|start [args...]
#   trex-v2-docker.sh env                      print the resolved daemon and image ref
#
# The deployment machine is reached the same way as everywhere else: select the context,
# and the image is built straight into that daemon (no registry needed).
#
#   DOCKER_CONTEXT=deploy trex-v2-docker.sh build      # image lands on the deploy machine
#   trex-v2-docker.sh --context deploy up -d           # and the stack runs there
#
# jib reads DOCKER_HOST and ignores Docker's context file, so the selected context is
# resolved to a DOCKER_HOST first (deploy/bin/_trex-docker.sh). -am keeps the v2 siblings
# on the reactor classpath; a stale ~/.m2 jar otherwise yields an image that dies with
# NoClassDefFoundError.
#
# Environment:
#   DOCKER_CONTEXT     context to use (default: Docker's current context)
#   DOCKER_HOST        bypasses context resolution entirely
#   TREX_IMAGE_PREFIX  image prefix/registry (default trex; push needs a real one)
#   TREX_IMAGE_TAG     image tag (default: the project version)

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
COMPOSE_FILE="$REPO/deploy/v2/compose.yml"

# shellcheck source=deploy/bin/_trex-docker.sh
. "$HERE/_trex-docker.sh"

die() { echo "trex-v2-docker: $*" >&2; exit 1; }

resolve() {
    trex_resolve_docker_host || die "cannot resolve the Docker context (set DOCKER_CONTEXT or DOCKER_HOST)"
    echo "trex-v2-docker: context '${DOCKER_CONTEXT:-active}' -> $DOCKER_HOST" >&2
}

mvn_jib() {
    local profile="$1"
    (cd "$REPO" && mvn -B -ntp -DskipTests package "-P$profile" -pl trex-v2-dist -am)
}

compose() {
    (cd "$REPO" && TREX_IMAGE_TAG="${TREX_IMAGE_TAG:-$(trex_project_version "$REPO")}" \
        docker compose -f "$COMPOSE_FILE" "$@")
}

# `--context NAME` may appear anywhere; it is the per-invocation form of DOCKER_CONTEXT.
args=()
while [ $# -gt 0 ]; do
    case "$1" in
        --context)
            DOCKER_CONTEXT="${2:-}"
            [ -n "$DOCKER_CONTEXT" ] || die "--context needs a name"
            export DOCKER_CONTEXT
            shift 2
            ;;
        *) args+=("$1"); shift ;;
    esac
done
set -- "${args[@]:-}"

case "${1:-}" in
    build)
        shift; resolve; mvn_jib docker
        ;;
    push)
        shift; resolve
        [ -n "${TREX_IMAGE_PREFIX:-}" ] \
            || die "set TREX_IMAGE_PREFIX to a registry namespace before pushing"
        mvn_jib docker-push
        ;;
    up)
        shift; resolve; compose up -d "$@"
        ;;
    down|ps|logs|restart|stop|start)
        cmd="$1"; shift; resolve; compose "$cmd" "$@"
        ;;
    env)
        resolve
        echo "DOCKER_HOST=$DOCKER_HOST"
        echo "TREX_IMAGE_PREFIX=${TREX_IMAGE_PREFIX:-trex}"
        echo "TREX_IMAGE_TAG=${TREX_IMAGE_TAG:-$(trex_project_version "$REPO")}"
        echo "TREX_IMAGE=${TREX_IMAGE_PREFIX:-trex}/trex-v2:${TREX_IMAGE_TAG:-$(trex_project_version "$REPO")}"
        ;;
    *)
        sed -n '3,30p' "${BASH_SOURCE[0]}" | sed 's|^# \{0,1\}||'
        exit 64
        ;;
esac
