# V2 daily archive plan (Prod1)

Status: A-1…A-7 accepted 2026-11-05 (off-host: not now; retention: keep all). Spec: `V2-SPEC.md` §14.

## Why

Prod1 is the log of record and had no scheduled backup: `schedule.yaml` was `jobs: []`, the archive
volume held only source copies, and the manual "volume → tarball" recipe ran only by hand. The
runner's `journal-snapshot` job and the scheduler already exist; what was missing is turning them
on, covering evidence and config (a snapshot is journal only), and noticing when it stops working.

Measured 2026-11-05: journal 4.0 MB (8024 lines), evidence 2.7 MB, config 1.2 MB, disk 28 GB free.

## Decisions

- **A-1 Journal.** Live `schedule.yaml`: `journal-snapshot`, `every: 24h`, `at: "02:30"`,
  `zone: Australia/Sydney` — config only. Runner restart to load it.
- **A-2 Evidence + config.** Root systemd `trex-backup.timer` at 03:00 Sydney runs
  `deploy/v2/trex-backup.sh`: `rsync --link-dest` copy into `/opt/trex/backups/daily/<YYYY-MM-DD>/`
  (Sydney date). Needs `rsync` on the host.
- **A-3 Off-host.** Not now. Same-disk copy guards a bad write or a deletion, not disk loss; a
  Proxmox vzdump of CT 108 is the later step.
- **A-4 Retention.** Keep all; no pruning code. Revisit at 1 GB.
- **A-5 Checks.** The script fails (unit failed) unless the newest snapshot is from today, passes
  `gzip -t`, and has at least the previous snapshot's line count. Monthly manual drill: `trex verify`
  over the newest snapshot with that day's evidence/config copy (`docs/DEPLOYMENTS.md`).
- **A-6 Repo.** `deploy/v2/trex-backup.sh`, `deploy/v2/systemd/trex-backup.{service,timer}`, the
  "Daily archive" section in `docs/DEPLOYMENTS.md` (replaces "Back up (volume → tarball)"), one
  §14 line. No Java change, no release.
- **A-7 Rollout.** Enable the schedule entry, take one snapshot by hand, install script + units,
  run the service once, enable the timer; next morning check the new `.gz`, the new day folder and
  the unit status.

Downstream (ezbookkeeping): none.
