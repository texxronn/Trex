# V2 asOf cadence plan

Status: decisions R-1…R-8 accepted 2026-11-05. Spec: `V2-SPEC.md` (authority).

## Why

On Prod1 the idle hub spikes to ~67% CPU for a few seconds every minute and writes ~40 MB/min to
the index (14.8 GB in 6 h): `IndexRefresher` forces a full derive whenever 60 s pass without one
(`HubConfig.DEFAULT_ASOF_REFRESH_MS`). Every `asOf` read in `trex-v2-core` truncates to the UTC date
(`asOf.atZone(UTC).toLocalDate()`), so within one UTC day those derives reproduce identical output;
only the `as_of` meta stamp moves. The derive output can only change when the UTC date turns.

## Decisions

- **R-1 Knob.** Env `TREX_ASOF_REFRESH` (CLI `--asof-refresh` wins over env, same pattern as
  `--auth`/`TREX_AUTH_FILE`), passed to the hub through compose like `TREX_ENV`.
- **R-2 Values.** `day` (default): a timer derive is due when the UTC date of now differs from the
  UTC date of the last full derive. `<n>m` / `<n>h` (n ≥ 1, e.g. `15m`, `1h`): due when now has
  crossed a UTC-aligned interval boundary since the last full derive, i.e.
  `floor(now/interval) != floor(last/interval)` in epoch millis (so `1h` fires at :00). `off`: never
  on a timer; only writes and startup move `asOf`.
- **R-3 Bad value.** Hub refuses to start with a clear message naming the bad value and the accepted
  forms. No silent fallback. Blank/unset = `day`.
- **R-4 Writes.** Unchanged: a structural write's full derive advances `asOf` and counts as the last
  full derive.
- **R-5 Startup.** Unchanged: boot derives with `Instant.now()`.
- **R-6 Spec.** `V2-SPEC.md` "When `asOf` advances" paragraph: cadence configurable, default the UTC
  day turn, because derive reads `asOf` only as a UTC date. Downstream (ezbookkeeping): none —
  derive outputs identical.
- **R-7 Tests.** Unit test of the pure "is a timer derive due?" function with explicit instants (no
  ambient clock): same UTC day → no; across UTC midnight → yes; `1h` within an hour → no, across
  :00 → yes; `15m` boundary; `off` → never; parse accepts `day`, `off`, `15m`, `1h`, case-insensitive
  and trimmed, and rejects `0m`, `-1h`, `1d`, `abc`, `60s`. Existing suites stay green.
- **R-8 Ship.** v1.0.3: PR, release PR, tag, `docker save | docker --context trex load` to Prod1,
  then `docker stats` over a few minutes (CPU and block I/O).

## Global constraints

- `derive()` untouched. No change in `trex-v2-core`.
- JDK-only, no new dependency. Comments carry the why.
- Keep existing `HubConfig` constructors compiling (tests use the long-ms overloads); a positive
  long ms maps to an interval cadence, `0` to `off`.
- The refresher loop polls about once a second; the due check must be cheap (no I/O).

## Task 1: Hub cadence (code + tests)

Files: `trex-v2-hub/src/main/java/trex/v2/hub/` (new `AsOfCadence.java`, `HubConfig.java`,
`IndexRefresher.java`, `HubService.java` if it passes the value through),
`trex-v2-dist/src/main/java/trex/v2/cli/HubCommand.java`, new test
`trex-v2-hub/src/test/java/trex/v2/hub/AsOfCadenceTest.java`.

1. `AsOfCadence` — small immutable type (record or sealed) with:
   - `static AsOfCadence parse(String)` per R-2/R-3 (null/blank → `day`; throws
     `IllegalArgumentException` with message `asOf refresh must be day, off, <n>m or <n>h (got '<v>')`).
   - `static AsOfCadence everyMs(long ms)` (0 → off) for the legacy long overloads.
   - `boolean due(Instant lastFullDerive, Instant now)` — pure, per R-2.
   - `toString()` gives the canonical form (`day`, `off`, `15m`, `1h`) for the startup log line.
2. `HubConfig`: replace `long refreshAsOfByMs` with `AsOfCadence asOfRefresh`; default
   `AsOfCadence.DAY` (constant). Keep the old long-taking constructors as overloads mapping via
   `everyMs`. Null → `DAY`. Update the javadoc (the 60 s rationale becomes the UTC-date rationale).
3. `IndexRefresher`: track the last full derive as a wall-clock `Instant` (it compares UTC dates);
   replace the `nanoTime` elapsed check with `asOfRefresh.due(lastFullDerive, Instant.now())`.
   Startup derive and every full derive from a write set it, as today. Update the class comment.
4. `HubCommand`: `@Option(names = "--asof-refresh")` String, falling back to env
   `TREX_ASOF_REFRESH`; parse; on `IllegalArgumentException` print the message to stderr and exit
   64. Print the cadence in the "trex hub listening" line, e.g. `asOf refresh day`.
5. Tests: `AsOfCadenceTest` covering R-7. Run the hub module's tests (`IndexRefresherTest` and the
   rest) and the dist module compile.

## Task 2: Spec, compose, docs

Files: `V2-SPEC.md`, `deploy/v2/compose.yml`, `deploy/v2/compose.server.yml`,
`docs/DEPLOYMENTS.md`, `CHANGELOG.md`.

1. `V2-SPEC.md` "When `asOf` advances": replace "on `HubConfig.refreshAsOfByMs` (default 60 s) when
   no write has; a shorter cadence…" with the configurable cadence (`TREX_ASOF_REFRESH` /
   `--asof-refresh`: `day` default, `<n>m`/`<n>h` UTC-aligned, `off`), the reason (derive reads
   `asOf` only as a UTC date, so `day` loses nothing; `as_of` the stamp moves only at the turn), and
   "Downstream (ezbookkeeping): none".
2. Both compose files, hub service environment, beside `TREX_ENV`:
   `TREX_ASOF_REFRESH: ${TREX_ASOF_REFRESH:-day}` with a one-line why comment.
3. `docs/DEPLOYMENTS.md`: document `TREX_ASOF_REFRESH` where the other hub `.env` knobs are listed.
4. `CHANGELOG.md`: an Unreleased (1.0.3) entry: idle hub no longer re-derives every minute;
   `TREX_ASOF_REFRESH` knob.
