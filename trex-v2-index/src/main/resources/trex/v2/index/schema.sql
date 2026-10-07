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
  state_hash      TEXT
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

CREATE TABLE IF NOT EXISTS ineffective_decision (
  decision_n INTEGER PRIMARY KEY,
  action     TEXT NOT NULL,
  reason     TEXT NOT NULL
);

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
