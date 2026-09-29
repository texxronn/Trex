# trex v2 deployment

One image, one jar, every role named by the command (V2-PROPOSAL.md §14, §19). This directory is
the v2 deployment set; the v1 units in `deploy/systemd` and the root `compose.yml` are left as the
reference deployment until the operator cuts over (§16 "Deployment change": the artifact swaps,
the arguments stay).

## Build the image

```sh
mvn -pl trex-v2-dist -am package -Pdocker        # into the Docker daemon
mvn -pl trex-v2-dist -am package -Pdocker-push   # push to ${trex.image.prefix}
```

The image is `${TREX_IMAGE_PREFIX:-trex}/trex-v2:${TREX_IMAGE_TAG:-0.1.0-SNAPSHOT}`. jib's standard
layout carries the self-contained `trex-v2.jar` plus the dependency jars; the entrypoint is
`trex.v2.Main`, which dispatches the subcommand. The base image is pinned by digest in the root
`pom.xml`.

## Compose

```sh
docker compose -f deploy/v2/compose.yml up -d                # sequencer + hub
docker compose -f deploy/v2/compose.yml ps
docker compose -f deploy/v2/compose.yml logs -f hub
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

## systemd

Install the units in `deploy/v2/systemd/` into `/etc/systemd/system/`, the config into `/etc/trex/`,
and the jar at `/opt/trex/lib/trex-v2.jar`. Then:

```sh
systemctl enable --now trex.target
systemctl enable --now trex-egress-archive.timer trex-egress-firefly.timer
systemctl start 'trex-ingest@/data/statement.csv'
```

`trex.target` brings up the sequencer and the hub. The timers run the archive mirror nightly and
the Firefly plan hourly; `--apply` and `--verify` are manual. `trex.env` carries `JAVA_OPTS` and the
per-run variables.

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
