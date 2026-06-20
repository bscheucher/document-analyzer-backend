-- V3__add_users_and_ownership.sql

CREATE TABLE users (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email       VARCHAR(255) NOT NULL UNIQUE,
    created_at  TIMESTAMP    NOT NULL DEFAULT now()
);

-- Seed the local default user so any existing documents can be backfilled
-- to it. The application also ensures a user with the configured default
-- email exists at startup; for the default config the seeded row IS that
-- user.
INSERT INTO users (id, email)
VALUES ('00000000-0000-0000-0000-000000000001', 'local@docanalyzer');

-- Backfill existing rows to the seeded user via the column DEFAULT, then
-- drop the default so future inserts must specify owner_id explicitly.
ALTER TABLE documents
    ADD COLUMN owner_id UUID NOT NULL
        REFERENCES users(id) ON DELETE CASCADE
        DEFAULT '00000000-0000-0000-0000-000000000001';

ALTER TABLE documents
    ALTER COLUMN owner_id DROP DEFAULT;

CREATE INDEX idx_documents_owner ON documents(owner_id);
