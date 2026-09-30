# trex v2 deployment

One image, one jar, every role named by the command (V2-PROPOSAL.md §14, §19). This directory is
the v2 deployment set; the v1 units in `deploy/systemd` and the root `compose.yml` are left as the
reference deployment until the operator cuts over (§16 "Deployment change": the artifact swaps,
the arguments stay).

## Build the image

```sh
deploy/bin/trex-v2-docker.sh build                     # into the active context's daemon
deploy/bin/trex-v2-docker.sh --context deploy build    # into the deploy machine's daemon
TREX_IMAGE_PREFIX=registry.example/trex deploy/bin/trex-v2-docker.sh push
```

or the raw Maven:

```sh
mvn -pl trex-v2-dist -am package -Pdocker        # into the daemon at DOCKER_HOST
mvn -pl trex-v2-dist -am package -Pdocker-push   # push to ${trex.image.prefix}
```

jib reaches the daemon through `DOCKER_HOST` and ignores Docker's context file, so
`deploy/bin/trex-v2-docker.sh` resolves the selected context (`--context NAME`, `DOCKER_CONTEXT`,
or the active one) to a `DOCKER_HOST` first. That is how the image lands directly on the deployment
machine with no registry.

The image is `${TREX_IMAGE_PREFIX:-trex}/trex-v2:${TREX_IMAGE_TAG:-<version>}`. jib's layout is the
project's own classes in `/app/classes` plus the dependency jars in `/app/libs` — not a copied
`trex-v2.jar`; the entrypoint is `java -cp @/app/jib-classpath-file trex.v2.Main`. The base image is
pinned by digest in the root `pom.xml`.

## Compose

The script resolves the same context and defaults the image tag to the project version, so the
build and the stack always agree:

```sh
deploy/bin/trex-v2-docker.sh up -d                           # sequencer + hub, in the context
deploy/bin/trex-v2-docker.sh ps
deploy/bin/trex-v2-docker.sh logs -f hub
deploy/bin/trex-v2-docker.sh down                            # add -v to discard the journal
```

or the raw compose (must target the same daemon and tag yourself):

```sh
docker compose -f deploy/v2/compose.yml up -d                # sequencer + hub
docker compose -f deploy/v2/compose.yml down                 # add -v to discard the journal
```


Tool roles run once and exit, behind the `tools` profile:

```sh
docker compose -f deploy/v2/compose.yml --profile tools run --rm ingest --types
docker compose -f deploy/v2/compose.yml --profile tools run --rm verify \
  verify --journal /var/lib/trex/journal/trex.jsonl --config /etc/trex \
         --index /var/lib/trex/index/trex.sqlite
TREX_FIREFLY_URL=http://firefly:8081 \
  docker compose -f deploy/v2/compose.yml --profile tools run --rm egress-firefly
```

Each role is one `command:` on the one image:

| role | command | notes |
|---|---|---|
| sequencer | `sequencer --journal … --config …` | the only journal writer |
| hub | `hub --journal … --config … --index … --sequencer-url …` | owns the index; writes the rules |
| ingest | `ingest --source-type … --account … --evidence … FILE` | one statement per run |
| index | `index … --rebuild` | offline; stops the hub first |
| verify | `verify …` | framing, rebuild equivalence, status strip |
| egress archive | `egress archive …` | byte mirror + evidence copy |
| egress firefly | `egress firefly … --plan` | `--apply` on instruction only |
| runner | `runner --config … --staging … --statements … --evidence … --archive …` | on-demand jobs + the staging inbox; loopback; the hub proxies it |
| snapshot | `snapshot --sequencer-url http://127.0.0.1:8080` | a dated gzip copy of the journal in the archive (V2-PROPOSAL.md §12.6) |

## Local dev

The same stack, on your own Docker daemon, with the UI served from the working tree:

```sh
deploy/v2/dev.sh build      # build the image locally (after a Java change)
deploy/v2/dev.sh up         # start; UI at http://localhost:8090
deploy/v2/dev.sh ingest     # the standard statements (~/Downloads/Statements/Statements_CSV)
deploy/v2/dev.sh reset      # stop and delete the volumes — day 0 again
deploy/v2/dev.sh ps|logs hub
```

`compose.dev.yml` mounts `trex-v2-hub/src/main/resources/trex/v2/hub/web` at `/web` and sets
`-Dtrex.hub.webDir=/web`, so a CSS or JS edit is a **reload, not a rebuild**. Java changes still
need `dev.sh build` (the hub's classes live in the image). The overlay is additive — the base
volumes are kept — and `dev.sh` clears `DOCKER_CONTEXT`/`DOCKER_HOST` so a dev build can never
land on the deployment machine. Ports are loopback (8080 sequencer, 8090 hub), the same as the
host but local.

## systemd

Install the units in `deploy/v2/systemd/` into `/etc/systemd/system/`, the config into `/etc/trex/`,
and the jar at `/opt/trex/lib/trex-v2.jar`. Then:

```sh
systemctl enable --now trex.target
systemctl enable --now trex-egress-archive.timer
systemctl start 'trex-ingest@/data/statement.csv'
```

`trex.target` brings up the sequencer, the hub and the runner. The archive mirror runs nightly on a
timer; the Firefly pass and ingest run on demand from the hub's Jobs view through `trex runner`
(V2-PROPOSAL.md §5.5). `trex.env` carries `JAVA_OPTS`, `TREX_FIREFLY_URL` and `FIREFLY_TOKEN`.

## Back up three things

The v2 log (`/var/lib/trex/journal/trex.jsonl`), the evidence store (`/var/lib/trex/evidence`), and
the config (`/etc/trex`, in git). The index, projection state, review queues and ACK copies are
disposable — recovered with:

```sh
systemctl stop trex-hub
java -jar /opt/trex/lib/trex-v2.jar index --journal … --config /etc/trex --index … --rebuild
java -jar /opt/trex/lib/trex-v2.jar verify --journal … --config /etc/trex --index …
systemctl start trex-hub
```

Run that drill on a schedule; it is the cold recovery path, not an emergency procedure.

## Deployments

Where the stack actually runs — the `trex` host, its Docker context, `/opt/trex`, the state volumes
and the backup commands — is recorded in [`docs/DEPLOYMENTS.md`](../docs/DEPLOYMENTS.md). The server
compose is `deploy/v2/compose.server.yml`; `deploy/v2/compose.yml` is the repo-local variant.
