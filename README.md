# trex — transaction sequencer (v2)

trex turns bank statement exports into one append-only, deterministic journal of transactions.
It gives every transaction a stable identity, deduplicates re-imports, matches transfers between
your own accounts, and parks anything uncertain for a human to resolve. The Firefly III projection
and the SQLite index are derived from the journal; **the journal is the only truth**, and every
derived table is rebuildable.

**v2 is the active system.** The v1 tree and its documents are archived in the sibling project
[`../TrexV1`](../TrexV1) — reference only; v2 does not import them.

- **Specification:** [V2-SPEC.md](V2-SPEC.md) — authoritative; §18 says what every other document is for.
- **History of intent:** [V2-PROPOSAL.md](V2-PROPOSAL.md) — frozen 2026-10-09; read it for *why*.
- **Current plan:** [V2-REVIEW-FIXES-PLAN.md](V2-REVIEW-FIXES-PLAN.md) — Stage 10 waits for the first feed; the [QOL plan](docs/plans/V2-QOL-IMPROVEMENTS-PLAN.md) §6 calls a month-long feature freeze. Built plans are in [docs/plans/](docs/plans/).
- **Parity with v1:** [docs/V2-PARITY.md](docs/V2-PARITY.md).
- **Release, deploy:** [docs/RELEASE.md](docs/RELEASE.md), [docs/DEPLOYMENTS.md](docs/DEPLOYMENTS.md).

## Shape

```
  statements ──► trex ingest ──HTTP──►  sequencer  ──append + fsync──►  journal.jsonl
                                            ▲                                │
                                     POST /decisions                         │
                                            │                                ▼
                                         trex hub  ◄──── index ◄──── SQLite (disposable)
                                      (Blotter UI)                           │
                                                                             ▼
                                                            egress firefly / analysis tools
```

One writer, one log, one global `n`. Everything else reads or derives. Scale means more
sequencers, not a bigger one.

| Module | Role |
|---|---|
| `trex-v2-core` | the model and pure `derive()` — no I/O, no clock |
| `trex-v2-log` | framing, journal, recovery, evidence, `Json`/`Yaml` |
| `trex-v2-index` | the disposable SQLite materializer |
| `trex-v2-sequencer` | the only writer |
| `trex-v2-hub` | index owner, Blotter UI, decision path, Jobs |
| `trex-v2-ingest` | statement adapters → facts (+ evidence) |
| `trex-v2-egress` | the Firefly III projection |
| `trex-v2-runner` | the on-demand job runner and its scheduler |
| `trex-v2-dist` | one shaded `trex.jar`, one image |

## Build

Java 25. `mvn test` is the gate; `mvn -DskipTests package` builds the shaded jar.

The dev stack — sequencer + hub + runner against a local Firefly III — is `deploy/v2/dev.sh`
(`build`, `up`, `down`, `reset`, `ingest`); see [deploy/v2/README.md](deploy/v2/README.md).
