-- V3: analytics projection table (consumed by analytics consumers)
CREATE TABLE IF NOT EXISTS analytics_event (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_category  TEXT NOT NULL,
    event_type      TEXT NOT NULL,
    payload         JSONB NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_analytics_category ON analytics_event (event_category, created_at DESC);
