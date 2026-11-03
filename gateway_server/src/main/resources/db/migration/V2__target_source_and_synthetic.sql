-- Phase 1: Add source and synthetic flags on monitored_targets so test/synthetic probes can be excluded from dashboards.
ALTER TABLE monitored_targets
    ADD COLUMN IF NOT EXISTS source    TEXT    NOT NULL DEFAULT 'real',
    ADD COLUMN IF NOT EXISTS synthetic BOOLEAN NOT NULL DEFAULT FALSE;
