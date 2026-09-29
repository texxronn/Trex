# Shared docker-context helpers for the trex build scripts. Source this; do not execute it.
#
# jib talks to the daemon through DOCKER_HOST and does not read Docker's context file, so
# `docker context use` / `docker --context` alone would not reach a remote deployment
# machine. Resolving the selected context to a DOCKER_HOST is the one non-obvious step that
# makes the jib build and `docker compose` target the same daemon.

# trex_resolve_docker_host
#   DOCKER_HOST wins if set; otherwise DOCKER_CONTEXT (or the active context) is inspected.
#   Exports DOCKER_HOST and returns non-zero when the context cannot be resolved.
trex_resolve_docker_host() {
    [ -n "${DOCKER_HOST:-}" ] && return 0
    local ctx host
    ctx="${DOCKER_CONTEXT:-$(docker context show 2>/dev/null || echo default)}"
    host="$(docker context inspect "$ctx" --format '{{.Endpoints.docker.Host}}' 2>/dev/null || true)"
    [ -n "$host" ] || return 1
    export DOCKER_HOST="$host"
    return 0
}

# trex_project_version <repo-root>
#   The root pom's <version>, so the image tag defaults to it without a Maven start-up.
trex_project_version() {
    sed -n 's|^  <version>\(.*\)</version>|\1|p' "$1/pom.xml" | head -1
}
