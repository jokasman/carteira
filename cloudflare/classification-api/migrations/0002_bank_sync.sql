CREATE TABLE IF NOT EXISTS bank_config (
  name TEXT PRIMARY KEY NOT NULL,
  value TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS bank_pending (
  state TEXT PRIMARY KEY NOT NULL,
  device_hash TEXT NOT NULL,
  aspsp TEXT NOT NULL,
  created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS bank_sessions (
  session_id TEXT PRIMARY KEY NOT NULL,
  device_hash TEXT NOT NULL,
  aspsp TEXT NOT NULL,
  accounts_json TEXT NOT NULL,
  valid_until TEXT,
  created_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS bank_sessions_device_idx ON bank_sessions (device_hash);
