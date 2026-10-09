#!/bin/sh
# sync-config — one-shot config drift sync (QOL_Improvements.md §3). Run inside a throwaway
# container against a compose `config` volume, never against the host filesystem directly:
#
#   docker run --rm -i -v trex-v2_config:/etc/trex alpine sh -s < deploy/v2/sync-config.sh
#   docker run --rm -i -v trex-v2_config:/etc/trex -e ADOPT=profiles.yaml \
#     alpine sh -s < deploy/v2/sync-config.sh
#
# For each seeded file it compares S = /etc/trex/.shipped/<f> (the image's copy), B =
# /etc/trex/.base/<f> (the shipped version last installed) and C = /etc/trex/<f> (the live file,
# possibly edited in the UI) — the same table as the hub's GET /api/config/drift. A repo-newer
# file (C == B, S != B) is backed up to .backup/<f>.<ts>, then S overwrites C and B;
# edited-here, both-changed and unknown are reported and left alone. ADOPT=<f> records the
# current file as its base, for the first run after drift ships; it is refused when .shipped has
# no copy of the file (start the stack once so init seeds it). A path that exists but is not a
# regular file fails the run rather than being compared or overwritten. POSIX sh + cmp/cp only.
# A sync needs no restart: the sequencer re-checks config before each write and the hub watches it.

set -eu

files="sequencer.yaml accounts.yaml users.yaml categories.yaml transfers.yaml pins.yaml firefly.yaml statements.yaml sources.yaml schedule.yaml profiles.yaml"
adopt="${ADOPT:-}"
ts="$(date -u +%Y%m%dT%H%M%SZ)"

# cmp with a missing file is an error, not "different": guard with -f.
same() { [ -f "$1" ] && [ -f "$2" ] && cmp -s "$1" "$2"; }

# The hub's ConfigDrift.state (trex-v2-hub), in POSIX sh. $1 S, $2 B, $3 C.
state() {
    if [ ! -f "$1" ]; then echo unknown; return; fi
    if same "$3" "$1"; then echo same; return; fi
    if [ ! -f "$2" ]; then echo unknown; return; fi
    if same "$3" "$2"; then echo repo-newer; return; fi
    if same "$1" "$2"; then echo edited-here; return; fi
    echo both-changed
}

# An existing path that is not a regular file cannot be compared or overwritten: fail loudly
# rather than treating it as absent (or letting cp/rm fail with a cryptic message).
check_regular() {
    if [ -e "$1" ] && [ ! -f "$1" ]; then
        echo "sync-config: $1 exists but is not a regular file — refusing to touch it" >&2
        exit 1
    fi
}

[ -d /etc/trex ] || { echo "sync-config: /etc/trex is not mounted — check the -v config volume" >&2; exit 1; }
[ -d /etc/trex/.shipped ] || {
    echo "sync-config: /etc/trex/.shipped is missing — start the stack once so init seeds it" >&2
    exit 1
}

if [ -n "$adopt" ]; then
    case " $files " in
        *" $adopt "*) ;;
        *) echo "sync-config: unknown file '$adopt' (expected one of: $files)" >&2; exit 1 ;;
    esac
    check_regular "/etc/trex/$adopt"
    check_regular "/etc/trex/.base/$adopt"
    check_regular "/etc/trex/.shipped/$adopt"
    [ -f "/etc/trex/$adopt" ] || {
        echo "sync-config: /etc/trex/$adopt is missing — there is nothing to adopt" >&2
        exit 1
    }
    [ -f "/etc/trex/.shipped/$adopt" ] || {
        echo "sync-config: /etc/trex/.shipped/$adopt is missing — start the stack once so init" \
            "seeds it; adopting cannot settle a file with no shipped copy" >&2
        exit 1
    }
    mkdir -p /etc/trex/.base
    cp "/etc/trex/$adopt" "/etc/trex/.base/$adopt"
    echo "$adopt: adopted the current file as the base"
fi

for f in $files; do
    s="/etc/trex/.shipped/$f"
    b="/etc/trex/.base/$f"
    c="/etc/trex/$f"
    check_regular "$s"
    check_regular "$b"
    check_regular "$c"
    case "$(state "$s" "$b" "$c")" in
        same)
            echo "$f: same" ;;
        repo-newer)
            mkdir -p /etc/trex/.backup
            cp "$c" "/etc/trex/.backup/$f.$ts"
            cp "$s" "$c"
            cp "$s" "$b"
            echo "$f: repo-newer — updated; the old file is in /etc/trex/.backup/$f.$ts" ;;
        edited-here)
            echo "$f: edited-here — left alone (your edit; the repo did not move)" ;;
        both-changed)
            echo "$f: both-changed — left alone (changed here and in the repo; merge by hand)" ;;
        unknown)
            if [ ! -f "$s" ]; then
                echo "$f: unknown — left alone (no .shipped copy; start the stack once so init seeds it)"
            else
                echo "$f: unknown — left alone (no base recorded; look, then adopt it)"
            fi ;;
    esac
done
