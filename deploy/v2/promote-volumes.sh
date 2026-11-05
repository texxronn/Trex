#!/usr/bin/env bash
# Promote a dev stack to the trex host by volume copy (docs/DEPLOYMENTS.md, "Promoting by volume
# copy"). Run as root on the host from the unpacked bundle dir:
#   cd /tmp/promote && bash promote-volumes.sh
# Copies the dev journal/evidence/config volumes as-is (old lines keep their env stamp); the index
# is rebuilt by the hub. Everything the host had is backed up first; the wipe is the one-way step.
set -euo pipefail
cd "$(dirname "$0")"
BUNDLE=$PWD
TS=$(date +%Y%m%dT%H%M%S)
BK=/opt/trex/backups/pre-promote-$TS
DC="docker compose -f /opt/trex/compose.yml --project-directory /opt/trex"
step() { printf '\n=== %s\n' "$*"; }

[ "$(id -u)" = 0 ] || { echo "run as root"; exit 1; }
step "checksums";            sha256sum -c SHA256SUMS

IMAGE=$(ls trex-v2-*.tar.gz); TAG=${IMAGE#trex-v2-}; TAG=${TAG%.tar.gz}
step "load image $TAG";      gunzip -c "$IMAGE" | docker load

step "backup prod -> $BK"
mkdir -p "$BK"
cp -a /opt/trex/compose.yml /opt/trex/.env "$BK/" 2>/dev/null || true
cp -a /opt/trex/config "$BK/config-dir" 2>/dev/null || true
for v in journal evidence config archive staging; do
  if docker volume inspect trex-v2_$v >/dev/null 2>&1; then
    docker run --rm -v trex-v2_$v:/v:ro -v "$BK":/b alpine tar czf /b/prod-$v.tgz -C /v .
  fi
done
docker run --rm -v trex-v2_journal:/v:ro alpine sh -c 'wc -l /v/trex.jsonl' 2>/dev/null | sed 's/^/old prod journal: /' || true
ls -la "$BK"

step "stop prod stack";      $DC down --remove-orphans || true

step "wipe prod volumes"
for v in journal evidence config index archive staging; do docker volume rm trex-v2_$v 2>/dev/null && echo "removed trex-v2_$v" || true; done

step "install compose + config"
install -m 0644 compose.yml /opt/trex/compose.yml
mkdir -p /opt/trex/config
install -m 0644 config/*.yaml /opt/trex/config/

step "update /opt/trex/.env"
touch /opt/trex/.env
setenv() { if grep -q "^$1=" /opt/trex/.env; then sed -i "s|^$1=.*|$1=$2|" /opt/trex/.env; else echo "$1=$2" >> /opt/trex/.env; fi; }
keep()   { grep -q "^$1=" /opt/trex/.env || echo "$1=$2" >> /opt/trex/.env; }
setenv TREX_IMAGE_TAG "$TAG"
setenv TREX_ENV Prod1
keep   TREX_HUB_BIND 0.0.0.0
keep   TREX_SEQ_BIND 127.0.0.1
# The runner refuses to start without these; nothing listens there yet (ezb on prod is later)
# and egress apply stays locked on this host.
keep   TREX_FIREFLY_URL http://127.0.0.1:8083
keep   TREX_EZB_URL http://127.0.0.1:8095
if [ -f /opt/trex/auth/auth.yaml ]; then
  setenv TREX_AUTH_FILE /etc/trex-auth/auth.yaml
  chown -R 1000:1000 /opt/trex/auth     # the hub runs as uid 1000; a root-only file stops it
else echo "WARN: no /opt/trex/auth/auth.yaml -> hub runs WITHOUT sign-in (see DEPLOYMENTS.md)"; fi
sed -E 's/((TOKEN|PASSWORD|SECRET)=).*/\1<redacted>/' /opt/trex/.env

step "create volumes";       $DC create

step "restore Dev1 volumes"
for v in journal evidence config; do
  docker run --rm -v trex-v2_$v:/v -v "$BUNDLE":/b:ro alpine sh -c "tar xzf /b/dev1-$v.tgz -C /v && chown -R 1000:1000 /v"
done
docker run --rm -v trex-v2_journal:/v:ro alpine sh -c 'wc -l /v/trex.jsonl'
docker run --rm -v trex-v2_evidence:/v:ro alpine sh -c 'find /v -type f | wc -l' | sed 's/^/evidence files: /'

step "start";                $DC up -d
for i in $(seq 1 60); do
  [ "$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8090/api/status)" != 000 ] && break; sleep 2
done
$DC ps
# 401 with sign-in on is a healthy answer.
curl -sS -o /dev/null -w "hub /api/status: %{http_code}\n" http://127.0.0.1:8090/api/status || true

# verify takes the index lock, which the running hub holds.
step "verify";               $DC stop hub runner; $DC run --rm verify; $DC up -d

printf '\nDONE. Backup in %s. Compare the journal line count with dev.\n' "$BK"
