-- When each package's `nf.schedule`s that were given an id last ran, as Unix time in
-- milliseconds, so a restart knows what it missed.
CREATE TABLE schedule_runs (
  namespace TEXT NOT NULL,
  id TEXT NOT NULL,
  last_run INTEGER NOT NULL,
  PRIMARY KEY (namespace, id)
);
