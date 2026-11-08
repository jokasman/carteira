CREATE TABLE classification_tasks (
  token_hash TEXT PRIMARY KEY NOT NULL,
  request_id TEXT NOT NULL,
  request_json TEXT NOT NULL,
  response_json TEXT,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL
);

CREATE INDEX classification_tasks_expires_at_idx
  ON classification_tasks (expires_at);

CREATE TABLE creation_rate_limits (
  ip_hash TEXT NOT NULL,
  day_number INTEGER NOT NULL,
  count INTEGER NOT NULL,
  PRIMARY KEY (ip_hash, day_number)
);

CREATE INDEX creation_rate_limits_day_idx
  ON creation_rate_limits (day_number);
