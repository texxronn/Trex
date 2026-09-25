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

# Read a per-component config file, e.g. load_env web → config/web.env
load_env() {
    local f="$CONF/$1.env"
    [ -f "$f" ] || die "missing $f"
    # shellcheck disable=SC1090
    . "$f"
}

dirs() {
    mkdir -p "$RUN/journal" "$RUN/archive" "$RUN/sqlite" "$RUN/hledger" "$PIDS"
}

# The journal path the SEQUENCER will actually use, resolved the way it resolves
# it: from journal.target in config/sequencer.yaml, relative to the config dir.
sequencer_journal() {
    local yaml="$CONF/sequencer.yaml" target
    [ -f "$yaml" ] || { echo ""; return 0; }
    target="$(sed -n 's/^[[:space:]]*target:[[:space:]]*"\{0,1\}\([^"]*\)"\{0,1\}[[:space:]]*$/\1/p' "$yaml" | head -1)"
    # No die() here: this runs inside $( ), where die would only kill the
    # subshell and the caller would carry on with an empty answer.
    [ -n "$target" ] || { echo ""; return 0; }
    case "$target" in
        /*) echo "$target" ;;
        *)  (cd "$CONF" && cd "$(dirname "$target")" 2>/dev/null && echo "$PWD/$(basename "$target")") \
                || echo "$CONF/$target" ;;
    esac
}

# TREX_DEV_RUN moves the pid files, the archive and the sqlite mirror. It does
# NOT move the journal: the sequencer reads its path from sequencer.yaml, which
# is relative to the CONFIG directory, not to the run directory. So pointing
# TREX_DEV_RUN at a scratch dir and expecting an isolated instance gives you a
# scratch dir for everything except the one file that matters — and the real dev
# journal gets appended to, silently, by what you thought was a throwaway run.
#
# This has happened. Refuse to start rather than let it happen again: either the
# two agree, or sequencer.yaml has to be pointed at the same run directory.
check_run_dir() {
    local want="$RUN/journal/journal.jsonl" have
    have="$(sequencer_journal)"
    [ -n "$have" ] || die "cannot read journal.target from $CONF/sequencer.yaml"
    [ "$want" = "$have" ] && return 0
    die "TREX_DEV_RUN and sequencer.yaml disagree about the journal.

  TREX_DEV_RUN      $RUN
  would write to    $want
  sequencer.yaml    $have

TREX_DEV_RUN does not move the journal — the sequencer resolves journal.target
relative to the config directory. Starting now would append to the journal above,
which is probably not the one you meant.

Fix either side:
  - point journal.source/target in $CONF/sequencer.yaml at $RUN/journal/journal.jsonl
  - or unset TREX_DEV_RUN to use the default dev instance"
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
    # An `&&` here would return non-zero for every other service and set -e would
    # kill the script before it ever started one.
    if [ "$svc" = "sequencer" ]; then check_run_dir; fi
    dirs
    if pid="$(pid_of "$svc")"; then
        die "$svc already running (pid $pid) — stop it first"
    fi
    echo "$$" > "$PIDS/$svc.pid"
    echo "trex-dev: $svc (pid $$) — Ctrl-C to stop"
    # shellcheck disable=SC2086  # JAVA_OPTS is deliberately word-split
    exec "$(java_bin)" $JAVA_OPTS \
        "-Dorg.slf4j.simpleLogger.defaultLogLevel=$TREX_LOG_LEVEL" \
        -jar "$(jar_for "$(jar_module "$svc")")" $(subcommand "$svc") "$@"
}

# The four egress targets ship in ONE jar as subcommands (SPEC §5.2/5.3/5.8/5.9),
# so `egress-sqlite` means: the trex-egress jar, with `sqlite` as its first argument.
# Every other service still maps one-to-one onto its own jar.
jar_module() {
    case "$1" in egress-*) echo "egress" ;; *) echo "$1" ;; esac
}
subcommand() {
    case "$1" in egress-*) echo "${1#egress-}" ;; *) echo "" ;; esac
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
