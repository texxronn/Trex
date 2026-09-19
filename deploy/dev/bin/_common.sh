# Shared plumbing for the deploy/dev/bin scripts. Sourced, never executed.
#
# Each service script runs its JVM in the FOREGROUND so a tmux panel is the log:
# Ctrl-C stops it. A pid file is written before exec so `stop` and `status` work
# from another panel.

set -euo pipefail

TREX_DEV="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO="$(cd "$TREX_DEV/../.." && pwd)"
CONF="$TREX_DEV/config"

# shellcheck source=../trex-dev.env
. "$TREX_DEV/trex-dev.env"

RUN="$TREX_DEV_RUN"
PIDS="$RUN/pids"
STOP_TIMEOUT=30

die() { echo "trex-dev: $*" >&2; exit 1; }

java_bin() {
    if [ -n "${JAVA_HOME:-}" ]; then
        echo "$JAVA_HOME/bin/java"
    elif [ -x "$HOME/Tools/JDK/jdk-25.0.4.1+1/bin/java" ]; then
        echo "$HOME/Tools/JDK/jdk-25.0.4.1+1/bin/java"
    else
        command -v java || die "no java on PATH and JAVA_HOME unset"
    fi
}

jar_for() {
    local module="trex-$1"
    local jars=("$REPO/$module/target/$module-"*-all.jar)
    [ -e "${jars[0]}" ] || die "no shaded jar for $module — run: (cd $REPO && mvn -q -DskipTests package)"
    echo "${jars[0]}"
}

# Paths in the per-component .env files are relative to the run directory.
in_run() {
    case "$1" in
        /*) echo "$1" ;;
        *)  echo "$RUN/$1" ;;
    esac
}

# Read a per-component config file, e.g. load_env grid → config/grid.env
load_env() {
    local f="$CONF/$1.env"
    [ -f "$f" ] || die "missing $f"
    # shellcheck disable=SC1090
    . "$f"
}

dirs() {
    mkdir -p "$RUN/journal" "$RUN/archive" "$RUN/sqlite" "$PIDS"
}

# A pid file counts only if the process is alive AND still the service we
# started — pids are reused, and TERMing a stranger is worse than a stale file.
pid_of() {
    local svc="$1" pidfile="$PIDS/$1.pid" pid
    [ -f "$pidfile" ] || return 1
    pid="$(cat "$pidfile")"
    kill -0 "$pid" 2>/dev/null || return 1
    if [ -r "/proc/$pid/cmdline" ]; then
        tr '\0' ' ' < "/proc/$pid/cmdline" | grep -q "trex-$svc-" || return 1
    fi
    echo "$pid"
}

# Replace this shell with the JVM, keeping $$ so the pid file stays correct.
run_fg() {
    local svc="$1"; shift
    local pid
    dirs
    if pid="$(pid_of "$svc")"; then
        die "$svc already running (pid $pid) — stop it first"
    fi
    echo "$$" > "$PIDS/$svc.pid"
    echo "trex-dev: $svc (pid $$) — Ctrl-C to stop"
    # shellcheck disable=SC2086  # JAVA_OPTS is deliberately word-split
    exec "$(java_bin)" $JAVA_OPTS \
        "-Dorg.slf4j.simpleLogger.defaultLogLevel=$TREX_LOG_LEVEL" \
        -jar "$(jar_for "$svc")" "$@"
}

stop_svc() {
    local svc="$1" pid
    if ! pid="$(pid_of "$svc")"; then
        rm -f "$PIDS/$svc.pid"
        echo "trex-dev: $svc not running"
        return 0
    fi
    # TERM lets the sequencer finish an in-flight fsync and close the journal.
    kill -TERM "$pid" 2>/dev/null || true
    for _ in $(seq 1 $((STOP_TIMEOUT * 2))); do
        kill -0 "$pid" 2>/dev/null || break
        sleep 0.5
    done
    if kill -0 "$pid" 2>/dev/null; then
        echo "trex-dev: $svc ignored TERM after ${STOP_TIMEOUT}s, sending KILL" >&2
        kill -KILL "$pid" 2>/dev/null || true
    fi
    rm -f "$PIDS/$svc.pid"
    echo "trex-dev: $svc stopped"
}

status_svc() {
    local svc="$1" pid
    if pid="$(pid_of "$svc")"; then
        printf '%-16s running  pid %s\n' "$svc" "$pid"
    else
        printf '%-16s stopped\n' "$svc"
    fi
}

# Every service script has the same tiny CLI: start (default) | stop | status.
dispatch() {
    local svc="$1"; shift
    local cmd="${1:-start}"
    [ "$#" -gt 0 ] && shift || true
    case "$cmd" in
        start)  svc_start "$@" ;;
        stop)   stop_svc "$svc" ;;
        status) status_svc "$svc" ;;
        *)      die "usage: $(basename "$0") [start|stop|status]" ;;
    esac
}
