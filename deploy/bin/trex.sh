#!/usr/bin/env bash
#
# trex.sh — start/stop the trex services from a build tree.
#
# For local runs and small single-host installs. On a systemd host use the units
# in deploy/systemd instead; this script is the same wiring without systemd.
#
#   trex.sh start [service...]     start services (default: all)
#   trex.sh stop  [service...]     stop services (reverse order)
#   trex.sh restart [service...]
#   trex.sh status
#   trex.sh logs <service>         tail -f one log
#   trex.sh ingest <sourceType> <account> <source>
#
# Services: sequencer egress-archive egress-sqlite gateway web
#
# Environment:
#   TREX_RUN        run directory (default <repo>/run) — config, journal, logs, pids
#   TREX_BIND       bind address for every HTTP service (default 127.0.0.1)
#   TREX_SEQ_PORT   sequencer API port (default 8080)
#   TREX_GATEWAY_PORT   consumer API port, loopback only (default 8085)
#   TREX_WEB_PORT       (default 8090)
#   JAVA_HOME       JDK to run with
#   JAVA_OPTS       JVM options (default matches deploy/config/trex.env)

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"

RUN="${TREX_RUN:-$REPO/run}"
BIND="${TREX_BIND:-127.0.0.1}"
SEQ_PORT="${TREX_SEQ_PORT:-8080}"
ADMIN_PORT="${TREX_ADMIN_PORT:-8085}"
WEB_PORT="${TREX_WEB_PORT:-8090}"
JAVA_OPTS="${JAVA_OPTS:--Xms64m -Xmx512m -XX:+UseSerialGC}"

CONF="$RUN/config"
JOURNAL="$RUN/journal/journal.jsonl"
LOGS="$RUN/logs"
PIDS="$RUN/pids"

SERVICES="sequencer egress-archive egress-sqlite ws"
STOP_TIMEOUT=30

die() { echo "trex: $*" >&2; exit 1; }

java_bin() {
    if [ -n "${JAVA_HOME:-}" ]; then
        echo "$JAVA_HOME/bin/java"
    elif [ -x "$HOME/Tools/JDK/jdk-25.0.4.1+1/bin/java" ]; then
        echo "$HOME/Tools/JDK/jdk-25.0.4.1+1/bin/java"
    else
        command -v java || die "no java on PATH and JAVA_HOME unset"
    fi
}

# The four egress targets ship in one jar as subcommands, so `egress-sqlite`
# resolves to the trex-egress jar with `sqlite` as its first argument.
jar_module() { case "$1" in egress-*) echo "egress" ;; *) echo "$1" ;; esac; }
subcommand()  { case "$1" in egress-*) echo "${1#egress-}" ;; *) echo "" ;; esac; }

jar_for() {
    local module="trex-$1"
    local jars=("$REPO/$module/target/$module-"*-all.jar)
    [ -e "${jars[0]}" ] || die "no shaded jar for $module — run: mvn -q package"
    echo "${jars[0]}"
}

# The rules file is optional: without it every row reads UNCATEGORIZED (SPEC.md §5.6).
config_arg() {
    [ -f "$CONF/categories.yaml" ] && echo " --config $CONF"
}

args_for() {
    case "$1" in
        sequencer)
            echo "$CONF"
            ;;
        egress-archive)
            echo "--journal $JOURNAL --archive $RUN/archive/archive.jsonl --poll-seconds 60"
            ;;
        egress-sqlite)
            echo "--journal $JOURNAL --db $RUN/sqlite/trex.db --poll-seconds 60"
            ;;
        ws)
            # --bind moves the READ listener only. The admin listener is pinned to
            # 127.0.0.1 in the service itself and refuses anything else (SPEC.md §5.7).
            echo "--journal $JOURNAL --sequencer-url http://$BIND:$SEQ_PORT --bind $BIND --port $WEB_PORT --admin-port $ADMIN_PORT$(config_arg)"
            ;;
        *)
            die "unknown service: $1 (have: $SERVICES)"
            ;;
    esac
}

# A pid file is live only if the process exists AND is still the service we
# started — pids get reused, and killing a stranger would be worse than a
# stale file.
pid_of() {
    local svc="$1"
    local pidfile="$PIDS/$svc.pid"
    local pid
    [ -f "$pidfile" ] || return 1
    pid="$(cat "$pidfile")"
    kill -0 "$pid" 2>/dev/null || return 1
    if [ -r "/proc/$pid/cmdline" ]; then
        tr '\0' ' ' < "/proc/$pid/cmdline" | grep -q "trex-$svc-" || return 1
    fi
    echo "$pid"
}

bootstrap() {
    mkdir -p "$CONF" "$RUN/journal" "$RUN/archive" "$RUN/sqlite" "$LOGS" "$PIDS"
    # Config is copied once, then it is yours to edit — never overwritten.
    local f name
    for f in "$REPO/deploy/config"/*.yaml; do
        name="$(basename "$f")"
        [ -e "$CONF/$name" ] && continue
        sed -e "s|/var/lib/trex/journal|$RUN/journal|g" \
            -e "s|^bindHost: .*|bindHost: \"$BIND\"|" \
            -e "s|^bindPort: .*|bindPort: $SEQ_PORT|" \
            "$f" > "$CONF/$name"
        echo "trex: wrote $CONF/$name"
    done
}

wait_for_port() {
    local port="$1"
    for _ in $(seq 1 100); do
        if (exec 3<>"/dev/tcp/$BIND/$port") 2>/dev/null; then
            exec 3<&- 3>&-
            return 0
        fi
        sleep 0.1
    done
    return 1
}

start_one() {
    local svc="$1" pid jar
    if pid="$(pid_of "$svc")"; then
        echo "trex: $svc already running (pid $pid)"
        return 0
    fi
    jar="$(jar_for "$(jar_module "$svc")")"
    # shellcheck disable=SC2046,SC2086  # args and JAVA_OPTS are deliberately word-split
    nohup "$(java_bin)" $JAVA_OPTS -jar "$jar" $(subcommand "$svc") $(args_for "$svc") \
        >> "$LOGS/$svc.log" 2>&1 &
    echo $! > "$PIDS/$svc.pid"
    sleep 0.3
    if ! pid="$(pid_of "$svc")"; then
        rm -f "$PIDS/$svc.pid"
        die "$svc failed to start — see $LOGS/$svc.log"
    fi
    echo "trex: $svc started (pid $pid)"
    # Everything downstream reads the journal the sequencer creates.
    if [ "$svc" = sequencer ]; then
        wait_for_port "$SEQ_PORT" || die "sequencer did not open $BIND:$SEQ_PORT — see $LOGS/sequencer.log"
    fi
}

stop_one() {
    local svc="$1"
    local pid
    if ! pid="$(pid_of "$svc")"; then
        rm -f "$PIDS/$svc.pid"
        echo "trex: $svc not running"
        return 0
    fi
    # TERM lets the sequencer finish an in-flight fsync and close the journal.
    kill -TERM "$pid" 2>/dev/null || true
    for _ in $(seq 1 $((STOP_TIMEOUT * 2))); do
        kill -0 "$pid" 2>/dev/null || break
        sleep 0.5
    done
    if kill -0 "$pid" 2>/dev/null; then
        echo "trex: $svc ignored TERM after ${STOP_TIMEOUT}s, sending KILL" >&2
        kill -KILL "$pid" 2>/dev/null || true
    fi
    rm -f "$PIDS/$svc.pid"
    echo "trex: $svc stopped"
}

reverse() {
    local svc out=""
    for svc in $1; do out="$svc $out"; done
    echo "$out"
}

selected() {
    if [ "$#" -eq 0 ]; then
        echo "$SERVICES"
    else
        local svc
        for svc in "$@"; do args_for "$svc" > /dev/null; done
        echo "$@"
    fi
}

cmd_start() {
    local list svc
    list="$(selected "$@")"
    bootstrap
    for svc in $list; do start_one "$svc"; done
    echo
    echo "  sequencer API  http://$BIND:$SEQ_PORT"
    echo "  gateway        http://127.0.0.1:$ADMIN_PORT  (consumer API; loopback only)"
    echo "  web            http://$BIND:$WEB_PORT  (browse; /resolve for the worklist)"
    echo "  journal        $JOURNAL"
    echo "  logs           $LOGS"
}

cmd_stop() {
    local list svc
    list="$(reverse "$(selected "$@")")"
    for svc in $list; do stop_one "$svc"; done
}

cmd_status() {
    local svc pid
    for svc in $SERVICES; do
        if pid="$(pid_of "$svc")"; then
            printf '%-16s running  pid %s\n' "$svc" "$pid"
        else
            printf '%-16s stopped\n' "$svc"
        fi
    done
}

cmd_logs() {
    [ "$#" -eq 1 ] || die "usage: trex.sh logs <service>"
    args_for "$1" > /dev/null
    tail -f "$LOGS/$1.log"
}

cmd_ingest() {
    [ "$#" -eq 3 ] || die "usage: trex.sh ingest <sourceType> <account> <source>"
    # shellcheck disable=SC2086  # JAVA_OPTS is deliberately word-split
    "$(java_bin)" $JAVA_OPTS -jar "$(jar_for ingest)" \
        --source-type "$1" --account "$2" --sequencer-url "http://$BIND:$SEQ_PORT" "$3"
}

case "${1:-}" in
    start)   shift; cmd_start "$@" ;;
    stop)    shift; cmd_stop "$@" ;;
    restart) shift; cmd_stop "$@"; cmd_start "$@" ;;
    status)  cmd_status ;;
    logs)    shift; cmd_logs "$@" ;;
    ingest)  shift; cmd_ingest "$@" ;;
    *)
        sed -n '3,25p' "${BASH_SOURCE[0]}" | sed 's|^# \{0,1\}||'
        exit 64
        ;;
esac
