-- Bookkeeping applied before the numbered migrations, so every migration can
-- record itself even on an empty database. This is not a versioned step: it runs
-- unconditionally and is idempotent.

CREATE TABLE IF NOT EXISTS schema_version (
    version INT NOT NULL PRIMARY KEY,
    applied_at TIMESTAMP WITH TIME ZONE NOT NULL
);
