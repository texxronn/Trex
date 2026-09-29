#!/usr/bin/env bash
#
# trex-release.sh — cut a release: run the gate, date the changelog, bump the version,
# tag, build the artifacts, and begin the next snapshot.
#
#   trex-release.sh verify                  the release gate: the full reactor build
#   trex-release.sh prepare 0.2.0 [flags]   cut v0.2.0
#   trex-release.sh help
#
# Flags for prepare:
#   --skip-tests   skip the verify gate (you have run it already)
#   --image        also build and push the container image
#   --push         push the release commit, the follow-up commit and the tag
#   --dry-run      print the steps; change nothing
#
# Versioning is SemVer. Development versions carry -SNAPSHOT; a release does not. The
# version lives in the root pom and in every module's <parent> reference, and
# `versions:set` keeps them in step. A tag is never moved: published history is not
# rewritten (AGENTS.md). Release artifacts are written under target/, which is never
# committed.

set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
cd "$repo"

MVN="${MVN:-mvn}"
today="$(date -u +%Y-%m-%d)"
dry=0

die() { echo "trex-release: $*" >&2; exit 1; }
say() { echo "== $*"; }
run() { if [ "$dry" = 1 ]; then echo "   [dry-run] $*"; else "$@"; fi; }

# The root pom's version, which every module inherits.
current_version() { sed -n 's|^  <version>\(.*\)</version>|\1|p' pom.xml | head -1; }

usage() { sed -n '2,30p' "$0" | sed 's/^# \{0,1\}//'; }

cmd_verify() {
  say "release gate: $MVN -B -ntp verify"
  "$MVN" -B -ntp verify
}

cmd_prepare() {
  local version="$1"; shift || true
  local skip_tests=0 image=0 push=0
  while [ $# -gt 0 ]; do
    case "$1" in
      --skip-tests) skip_tests=1 ;;
      --image) image=1 ;;
      --push) push=1 ;;
      --dry-run|--dry) dry=1 ;;
      *) die "unknown flag '$1'" ;;
    esac
    shift
  done

  echo "$version" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+$' \
    || die "version must be MAJOR.MINOR.PATCH without -SNAPSHOT, got '$version'"
  git rev-parse --is-inside-work-tree >/dev/null 2>&1 || die "not a git repository"
  local tag="v$version"
  git rev-parse -q --verify "refs/tags/$tag" >/dev/null && die "tag $tag already exists; tags are never moved"
  [ -z "$(git status --porcelain)" ] || die "working tree is not clean; commit or stash first"
  grep -q '^## \[Unreleased\]' CHANGELOG.md || die "CHANGELOG.md has no '## [Unreleased]' section"
  local cur; cur="$(current_version)"
  [ "$cur" != "$version" ] || die "pom.xml is already at $version"

  say "prepare $version (from $cur)$([ "$dry" = 1 ] && echo ' [dry-run]')"

  if [ "$skip_tests" = 0 ] && [ "$dry" = 0 ]; then
    cmd_verify
  elif [ "$dry" = 1 ]; then
    say "skip the gate (dry-run)"
  else
    say "skip the gate (--skip-tests)"
  fi

  say "CHANGELOG: date the release, leave [Unreleased] for next time"
  if [ "$dry" = 1 ]; then
    echo "   [dry-run] insert '## [$version] - $today' below '## [Unreleased]'"
  else
    awk -v v="$version" -v d="$today" '
      { print }
      /^## \[Unreleased\]$/ && !done { print ""; print "## [" v "] - " d; done=1 }
    ' CHANGELOG.md > CHANGELOG.md.tmp && mv CHANGELOG.md.tmp CHANGELOG.md
  fi

  say "set the version in every pom"
  run "$MVN" -B -ntp versions:set -DnewVersion="$version" -DgenerateBackupPoms=false

  say "commit and tag"
  run git add -A
  run git commit -m "Release v$version"
  run git tag -a "$tag" -m "trex v$version"

  say "build the release artifacts"
  run "$MVN" -B -ntp -DskipTests package
  if [ "$dry" = 1 ]; then
    echo "   [dry-run] sha256sum trex-v2-dist/target/trex-v2.jar > target/release/$tag/SHA256SUMS"
  else
    local out="target/release/$tag"
    mkdir -p "$out"
    sha256sum trex-v2-dist/target/trex-v2.jar > "$out/SHA256SUMS"
    echo "   $(cat "$out/SHA256SUMS")"
  fi

  if [ "$image" = 1 ]; then
    say "build and push the image"
    run "$MVN" -B -ntp -Pdocker-push -DskipTests -pl trex-v2-dist -am package
  fi

  local next="${version%.*}.$(( 10#${version##*.} + 1 ))-SNAPSHOT"
  say "begin $next"
  run "$MVN" -B -ntp versions:set -DnewVersion="$next" -DgenerateBackupPoms=false
  run git add -A
  run git commit -m "Begin $next"

  if [ "$push" = 1 ]; then
    say "push"
    run git push origin HEAD
    run git push origin "$tag"
  fi

  say "done: $tag"
}

case "${1:-help}" in
  verify) shift; cmd_verify ;;
  prepare) shift; [ $# -ge 1 ] || die "prepare needs a version, e.g. prepare 0.2.0"; cmd_prepare "$@" ;;
  help|-h|--help|"") usage ;;
  *) die "unknown command '$1' (verify | prepare | help)" ;;
esac
