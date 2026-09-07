-- V6: durable consumer retry counters.
-- The in-memory ConcurrentHashMap attempt counter reset on every app
-- restart: a poison message that already failed 4 times got 5 fresh
-- attempts after each restart ("bounded per uptime"). The decision to
-- dead-letter is now as durable as the dead-letter table itself.
-- Rows are tiny, bounded (only failing messages get one), and purged
-- on success/dead-letter by the consuming code path.
CREATE TABLE consumer_retry (
    dedup_key   TEXT PRIMARY KEY,          -- '<topic>:<eventId>'
    attempts    INT NOT NULL CHECK (attempts > 0),
    last_error  TEXT,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
