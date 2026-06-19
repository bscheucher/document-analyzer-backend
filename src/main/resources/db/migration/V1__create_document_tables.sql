-- V1__create_document_tables.sql

-- gen_random_uuid() is built into Postgres 13+; pgcrypto provides it on older versions.
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE documents (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    filename    VARCHAR(255) NOT NULL,
    file_type   VARCHAR(50)  NOT NULL,  -- 'PDF' | 'IMAGE'
    file_size   BIGINT       NOT NULL,
    storage_path VARCHAR(512) NOT NULL,
    status      VARCHAR(50)  NOT NULL DEFAULT 'PENDING',
    -- PENDING | EXTRACTING | ANALYZING | DONE | FAILED
    created_at  TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE TABLE analysis_results (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id     UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    summary         TEXT,
    document_type   VARCHAR(100),   -- e.g. "Invoice", "Contract", "Medical Record"
    key_topics      TEXT[],
    extracted_text  TEXT,
    raw_llm_response TEXT,
    error_message   TEXT,
    created_at      TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_documents_status    ON documents(status);
CREATE INDEX idx_analysis_document   ON analysis_results(document_id);
