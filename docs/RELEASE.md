# Releasing trex

trex is a private, single-operator project, so a release is a tag plus a reproducible artifact —
not a public contract. The mechanics are scripted so a cut is one command; the judgement calls are
here.

## Versioning

SemVer `MAJOR.MINOR.PATCH`. Development versions carry `-SNAPSHOT`; a release does not. The
version lives in the **root `pom.xml`** and in every module's `<parent>` reference, and
`mvn versions:set` keeps all fifteen in step — never edit a version by hand.

- `MAJOR` — a breaking change to the log line format, identity, config grammar, or API. `externalId`
  and the log's history are permanent (`AGENTS.md`), so a major bump means a deliberate format
  change and, since there is no migration, a fresh journal.
- `MINOR` — new capability (a stage, a subcommand, an endpoint, a rule-file feature).
- `PATCH` — fixes, tuning, docs.

## What a release produces

- the shaded `trex-v2-dist/target/trex-v2.jar`, and
- the one container image `trex/trex-v2:<version>` (role by subcommand), and
- a `SHA256SUMS` under `target/release/v<version>/`.

`target/` is never committed; the artifacts are rebuildable from the tag.

## The gate

The release gate is the full reactor build, v1 modules included:

```sh
deploy/bin/trex-release.sh verify        # mvn -B -ntp verify
```

It must be green, and the tree must be clean, before a cut. Reproducibility is already wired in:
`project.build.outputTimestamp` is fixed and the jib image pins its creation time, so rebuilding a
tag reproduces the jar and image digest. To check by hand, build twice and compare
`sha256sum trex-v2-dist/target/trex-v2.jar`.

## Cutting a release

```sh
deploy/bin/trex-release.sh prepare 0.2.0            # gate, bump, tag, build, next snapshot
deploy/bin/trex-release.sh prepare 0.2.0 --image    # also push the image
deploy/bin/trex-release.sh prepare 0.2.0 --push     # also push the commit and the tag
```

`prepare` does, in order:

1. checks the tree is clean, the tag does not exist, and `CHANGELOG.md` has an `[Unreleased]` section;
2. runs `mvn verify`;
3. dates the changelog — inserts `## [0.2.0] - YYYY-MM-DD` below `[Unreleased]`, so the released
   entries move under it and `[Unreleased]` starts empty for next time;
4. `mvn versions:set -DnewVersion=0.2.0` across every pom;
5. commits `Release v0.2.0` and creates the annotated tag `v0.2.0`;
6. builds the jar and writes `target/release/v0.2.0/SHA256SUMS`;
7. with `--image`, runs `mvn -Pdocker-push -pl trex-v2-dist -am package` (honours
   `TREX_IMAGE_PREFIX` / `TREX_IMAGE_TAG`);
8. bumps to the next development version (`0.2.1-SNAPSHOT`) and commits `Begin 0.2.1-SNAPSHOT`;
9. with `--push`, pushes `HEAD` and the tag.

Add `--dry-run` to print every step without changing anything.

## Tagging

- Tags are **annotated** (`git tag -a vX.Y.Z -m …`). Sign them with `git tag -s` if a key is set.
- A tag is **never moved**. `prepare` refuses if `vX.Y.Z` already exists; published history is not
  rewritten. A mistake is a new patch tag, not a force-move.
- The tag points at the `Release vX.Y.Z` commit — the one that carries the version bump and the
  dated changelog — not at the follow-up snapshot commit.

## Hotfix

Branch from the tag, fix, and cut a patch (`0.2.1`) from that branch. Because `externalId` and the
log are permanent, a hotfix never touches identity or re-writes journal lines.

## What a release does not do

- It does not migrate v1. There is no migration (`V2-PROPOSAL.md` §16 is retired); the v1-format
  importer is a development tool, and a v2 journal starts at day 0.
- It does not change `externalId`. Only the log's history and `externalId` are permanent.
- It does not back up anything. The backup story is the same every day: the journal, the evidence
  store, and the config (git). The index and projection state are rebuilt, never released.
