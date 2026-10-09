-- trex-v2-index schema (V2-PROPOSAL.md §7.2, §7.4).
--
-- Level 1 (meta, fact, decision) mirrors the log row for row. Level 2 is derived and is replaced
-- wholesale by derive(); everything here can be dropped and rebuilt.

CREATE TABLE IF NOT EXISTS meta (
  key   TEXT PRIMARY KEY,
  value TEXT NOT NULL
);

-- Level 1: the log mirrored.
CREATE TABLE IF NOT EXISTS fact (
  n               INTEGER PRIMARY KEY,
  external_id     TEXT NOT NULL,
  account_ref     TEXT NOT NULL,
  date            TEXT NOT NULL,
  amount          INTEGER NOT NULL,
  balance         INTEGER NOT NULL,
  raw_description TEXT NOT NULL,
  receipt         TEXT,
  occ             INTEGER NOT NULL,
  observation     TEXT NOT NULL,
  source_type     TEXT NOT NULL,
  provenance      TEXT NOT NULL,
  evidence_id     TEXT,
  parser          TEXT,
  line_v          INTEGER NOT NULL,
  at_ms           INTEGER NOT NULL,
  env             TEXT NOT NULL,
  source          TEXT NOT NULL,
  target          TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS fact_external ON fact(external_id);
CREATE INDEX IF NOT EXISTS fact_account_date ON fact(account_ref, date);

CREATE TABLE IF NOT EXISTS decision (
  n       INTEGER PRIMARY KEY,
  action  TEXT NOT NULL,
  payload TEXT NOT NULL,
  actor   TEXT NOT NULL,
  user_id TEXT,
  at      TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS decision_action ON decision(action);

-- level 1: the third kind, mirrored like the others (V2-PROPOSAL.md §6, §12.6)
CREATE TABLE IF NOT EXISTS ingest_event (
  n           INTEGER PRIMARY KEY,
  phase       TEXT NOT NULL,
  batch       TEXT NOT NULL,
  evidence_id TEXT,
  file        TEXT,
  account_ref TEXT,
  source_type TEXT,
  parser      TEXT,
  appended    INTEGER,
  duplicate   INTEGER,
  flagged     INTEGER,
  status      TEXT,
  line_v      INTEGER NOT NULL,
  at_ms       INTEGER NOT NULL,
  env         TEXT NOT NULL,
  source      TEXT NOT NULL,
  target      TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS ingest_event_batch ON ingest_event(batch);

-- Level 2: derived. Rebuilt from level 1 + config + asOf.
CREATE TABLE IF NOT EXISTS supersession (
  from_id    TEXT PRIMARY KEY,
  to_id      TEXT,
  decision_n INTEGER NOT NULL,
  reason     TEXT
);

CREATE TABLE IF NOT EXISTS chain_resolved (
  id         TEXT PRIMARY KEY,
  current_id TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS txn_current (
  external_id     TEXT PRIMARY KEY,
  n               INTEGER NOT NULL,
  account_ref     TEXT NOT NULL,
  date            TEXT NOT NULL,
  amount          INTEGER NOT NULL,
  balance         INTEGER NOT NULL,
  raw_description TEXT NOT NULL,
  receipt         TEXT,
  occ             INTEGER NOT NULL,
  observation     TEXT NOT NULL,
  source_type     TEXT NOT NULL,
  provenance      TEXT NOT NULL,
  evidence_id     TEXT,
  parser          TEXT,
  line_v          INTEGER NOT NULL,
  at_ms           INTEGER NOT NULL,
  env             TEXT NOT NULL,
  source          TEXT NOT NULL,
  target          TEXT NOT NULL,
  role            TEXT NOT NULL,
  rail            TEXT,
  leg             TEXT NOT NULL,
  transfer_id     TEXT,
  category        TEXT NOT NULL,
  category_origin TEXT NOT NULL,
  rule_id         TEXT,
  state_hash      TEXT,
  synthetic       INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS txn_current_date ON txn_current(date);
CREATE INDEX IF NOT EXISTS txn_current_account ON txn_current(account_ref);

CREATE TABLE IF NOT EXISTS transfer (
  transfer_id TEXT PRIMARY KEY,
  from_leg    TEXT NOT NULL,
  to_leg      TEXT NOT NULL,
  confidence  TEXT NOT NULL,
  origin      TEXT NOT NULL,
  decision_n  INTEGER,
  method      TEXT NOT NULL,
  clearing_account TEXT,
  matched_at  TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS transfer_from ON transfer(from_leg);
CREATE INDEX IF NOT EXISTS transfer_to ON transfer(to_leg);

CREATE TABLE IF NOT EXISTS pending (
  external_id TEXT PRIMARY KEY,
  fact_n      INTEGER NOT NULL,
  account_ref TEXT NOT NULL,
  date        TEXT NOT NULL,
  amount      INTEGER NOT NULL,
  settled_by  TEXT,
  state       TEXT NOT NULL
);

-- an ingest attempt: its markers paired, with the n range its facts sit in (V2-PROPOSAL.md §12.6)
CREATE VIEW IF NOT EXISTS ingest_batch AS
SELECT s.batch, s.file, s.evidence_id, s.account_ref,
       s.n AS n_start, c.n AS n_end,
       c.appended, c.duplicate, c.flagged, c.status,
       s.at_ms AS started_ms, c.at_ms AS completed_ms
FROM ingest_event s
LEFT JOIN ingest_event c ON c.batch = s.batch AND c.phase = 'complete'
WHERE s.phase = 'start';

CREATE TABLE IF NOT EXISTS review_item (
  subject      TEXT NOT NULL,
  kind         TEXT NOT NULL,
  detail       TEXT,
  amount_stake INTEGER,
  opened_at    TEXT NOT NULL,
  state_hash   TEXT,
  PRIMARY KEY (subject, kind)
);
CREATE INDEX IF NOT EXISTS review_item_kind ON review_item(kind);

CREATE TABLE IF NOT EXISTS category_current (
  external_id TEXT PRIMARY KEY,
  category    TEXT NOT NULL,
  origin      TEXT NOT NULL,
  rule_id     TEXT
);

CREATE TABLE IF NOT EXISTS pin_current (
  external_id TEXT PRIMARY KEY,
  category    TEXT NOT NULL,
  decision_n  INTEGER NOT NULL,
  user_id     TEXT,
  comment     TEXT
);

-- Effective notes (§6.2 NOTE): a thread, one row per decision, ids chain-resolved (§9.4).
CREATE TABLE IF NOT EXISTS note_current (
  decision_n  INTEGER PRIMARY KEY,
  external_id TEXT NOT NULL,
  text        TEXT NOT NULL,
  user_id     TEXT,
  at          TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS note_current_external ON note_current(external_id);

CREATE TABLE IF NOT EXISTS ineffective_decision (
  decision_n INTEGER PRIMARY KEY,
  action     TEXT NOT NULL,
  reason     TEXT NOT NULL
);

-- Commitments and Expected (V2-PROPOSAL.md §6.11): the registry, its effective rules, the
-- materialised occurrences and the note thread. All derived; decisions live in the log.
CREATE TABLE IF NOT EXISTS commitment (
  commitment_id    TEXT PRIMARY KEY,
  candidate_key    TEXT,
  name             TEXT,
  origin           TEXT NOT NULL,
  direction        TEXT NOT NULL,
  cadence          TEXT NOT NULL,
  amount_kind      TEXT NOT NULL,
  kind             TEXT NOT NULL,
  status           TEXT NOT NULL,
  first_date       TEXT,
  last_date        TEXT,
  anchor_date      TEXT,
  current_amount   INTEGER,
  previous_amount  INTEGER,
  change_pct       REAL,
  change_date      TEXT,
  occurrence_count INTEGER NOT NULL,
  outliers         INTEGER NOT NULL DEFAULT 0,
  regularity       REAL,
  variable         INTEGER NOT NULL,
  arrears_count    INTEGER NOT NULL,
  arrears_amount   INTEGER,
  declared_n       INTEGER,
  retired_n        INTEGER,
  ended_at         TEXT,
  state_hash       TEXT
);

CREATE TABLE IF NOT EXISTS commitment_rule (
  commitment_id TEXT NOT NULL,
  match         TEXT NOT NULL,
  account_ref   TEXT,
  decision_n    INTEGER NOT NULL,
  PRIMARY KEY (commitment_id, match, account_ref)
);

CREATE TABLE IF NOT EXISTS commitment_occurrence (
  commitment_id       TEXT NOT NULL,
  due_date            TEXT NOT NULL,
  status              TEXT NOT NULL,
  window_start        TEXT,
  window_end          TEXT,
  matched_external_id TEXT,
  matched_date        TEXT,
  matched_by          TEXT,
  off_schedule        INTEGER NOT NULL,
  settle_n            INTEGER,
  amount              INTEGER,
  state_hash          TEXT,
  PRIMARY KEY (commitment_id, due_date)
);
CREATE INDEX IF NOT EXISTS commitment_occurrence_due ON commitment_occurrence(due_date, status);

CREATE TABLE IF NOT EXISTS commitment_note (
  decision_n    INTEGER PRIMARY KEY,
  commitment_id TEXT NOT NULL,
  text          TEXT NOT NULL,
  user_id       TEXT,
  at            TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS commitment_note_commitment ON commitment_note(commitment_id);

CREATE TABLE IF NOT EXISTS commitment_exclusion (
  commitment_id TEXT NOT NULL,
  external_id   TEXT NOT NULL,
  decision_n    INTEGER NOT NULL,
  PRIMARY KEY (commitment_id, external_id)
);

-- The fact -> commitment reverse map (V2-COMMITMENT-FACT-PLAN.md §3.1): one row per claimed fact.
-- Assignment is exclusive, so external_id is the key; matched_by is rule or pin. A projection of
-- the claim pass, disposable and rebuilt by `trex index --rebuild`; it drives the ledger chip and
-- the commitment popup's transaction list and price chart.
CREATE TABLE IF NOT EXISTS commitment_fact (
  external_id   TEXT PRIMARY KEY,
  commitment_id TEXT NOT NULL,
  matched_by    TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS commitment_fact_commitment ON commitment_fact(commitment_id);

CREATE TABLE IF NOT EXISTS unit (
  unit_id     TEXT PRIMARY KEY,
  unit_kind   TEXT NOT NULL,
  account_ref TEXT NOT NULL,
  date        TEXT NOT NULL,
  amount      INTEGER NOT NULL,
  currency    TEXT NOT NULL,
  category    TEXT NOT NULL,
  origin      TEXT NOT NULL,
  pairing     TEXT NOT NULL,
  retired     INTEGER NOT NULL,
  ineffective INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS projection_state (
  unit_id          TEXT PRIMARY KEY,
  unit_kind        TEXT NOT NULL,
  firefly_group_id TEXT,
  category         TEXT,
  state_hash       TEXT,
  config_revision  TEXT,
  derive_version   TEXT,
  verified_at      TEXT
);

CREATE TABLE IF NOT EXISTS user_ack (
  user_id         TEXT NOT NULL,
  external_id     TEXT NOT NULL,
  state_hash      TEXT NOT NULL,
  config_revision TEXT NOT NULL,
  derive_version  TEXT NOT NULL,
  hash_version    TEXT NOT NULL,
  acked_at        TEXT NOT NULL,
  PRIMARY KEY (user_id, external_id)
);

CREATE TABLE IF NOT EXISTS source_cursor (
  source TEXT PRIMARY KEY,
  cursor TEXT NOT NULL,
  at     TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS evidence (
  sha256      TEXT PRIMARY KEY,
  path        TEXT NOT NULL,
  bytes       INTEGER NOT NULL,
  media_type  TEXT,
  source_type TEXT,
  first_seen  TEXT NOT NULL
);
