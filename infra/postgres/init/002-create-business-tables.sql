-- Current V1 business schema.
-- This script is intentionally executed explicitly by scripts/init-local-database.sh.
-- Database migration tooling will be evaluated in a later version.

CREATE TABLE IF NOT EXISTS core.modules (
    id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    slug VARCHAR(120) NOT NULL UNIQUE,
    description TEXT,
    icon VARCHAR(80),
    sort_order INTEGER NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT modules_status_check CHECK (status IN ('ACTIVE', 'ARCHIVED'))
);

CREATE INDEX IF NOT EXISTS modules_status_sort_idx
    ON core.modules (status, sort_order, name);

CREATE TABLE IF NOT EXISTS core.tags (
    id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    slug VARCHAR(120) NOT NULL UNIQUE,
    parent_id UUID REFERENCES core.tags (id) ON DELETE SET NULL,
    color VARCHAR(32),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS tags_parent_idx ON core.tags (parent_id);
CREATE INDEX IF NOT EXISTS tags_name_idx ON core.tags (name);

CREATE TABLE IF NOT EXISTS core.documents (
    id UUID PRIMARY KEY,
    module_id UUID REFERENCES core.modules (id) ON DELETE SET NULL,
    title VARCHAR(240) NOT NULL,
    content TEXT NOT NULL,
    normalized_content TEXT,
    source_type VARCHAR(32) NOT NULL DEFAULT 'MANUAL',
    source_name VARCHAR(240),
    source_path TEXT,
    content_hash VARCHAR(128),
    index_error TEXT,
    indexed_at TIMESTAMPTZ,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING_INDEX',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT documents_status_check CHECK (status IN ('PENDING_INDEX', 'INDEXING', 'INDEXED', 'FAILED', 'ARCHIVED')),
    CONSTRAINT documents_source_type_check CHECK (source_type IN ('MANUAL', 'MARKDOWN', 'FILE'))
);

ALTER TABLE core.documents ADD COLUMN IF NOT EXISTS normalized_content TEXT;
ALTER TABLE core.documents ADD COLUMN IF NOT EXISTS index_error TEXT;
ALTER TABLE core.documents ADD COLUMN IF NOT EXISTS indexed_at TIMESTAMPTZ;
ALTER TABLE core.documents DROP CONSTRAINT IF EXISTS documents_status_check;
UPDATE core.documents SET status = 'INDEXED' WHERE status = 'READY';
UPDATE core.documents SET status = 'PENDING_INDEX' WHERE status = 'DRAFT';
ALTER TABLE core.documents ALTER COLUMN status SET DEFAULT 'PENDING_INDEX';
ALTER TABLE core.documents ADD CONSTRAINT documents_status_check
    CHECK (status IN ('PENDING_INDEX', 'INDEXING', 'INDEXED', 'FAILED', 'ARCHIVED'));

CREATE INDEX IF NOT EXISTS documents_module_idx ON core.documents (module_id);
CREATE INDEX IF NOT EXISTS documents_status_updated_idx
    ON core.documents (status, updated_at DESC);
CREATE INDEX IF NOT EXISTS documents_content_hash_idx
    ON core.documents (content_hash);

CREATE TABLE IF NOT EXISTS core.interview_questions (
    id UUID PRIMARY KEY,
    module_id UUID REFERENCES core.modules (id) ON DELETE SET NULL,
    title VARCHAR(240) NOT NULL,
    question TEXT NOT NULL,
    short_answer TEXT,
    detailed_answer TEXT,
    key_points JSONB NOT NULL DEFAULT '[]'::jsonb,
    difficulty VARCHAR(32) NOT NULL DEFAULT 'MEDIUM',
    mastery_status VARCHAR(32) NOT NULL DEFAULT 'NOT_STARTED',
    source_type VARCHAR(32) NOT NULL DEFAULT 'MANUAL',
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT questions_difficulty_check CHECK (difficulty IN ('EASY', 'MEDIUM', 'HARD')),
    CONSTRAINT questions_mastery_check CHECK (mastery_status IN ('NOT_STARTED', 'LEARNING', 'MASTERED')),
    CONSTRAINT questions_source_type_check CHECK (source_type IN ('MANUAL', 'DOCUMENT', 'AI_GENERATED')),
    CONSTRAINT questions_status_check CHECK (status IN ('ACTIVE', 'ARCHIVED'))
);

CREATE INDEX IF NOT EXISTS questions_module_idx ON core.interview_questions (module_id);
CREATE INDEX IF NOT EXISTS questions_filter_idx
    ON core.interview_questions (difficulty, mastery_status, updated_at DESC);

CREATE TABLE IF NOT EXISTS core.document_tags (
    document_id UUID NOT NULL REFERENCES core.documents (id) ON DELETE CASCADE,
    tag_id UUID NOT NULL REFERENCES core.tags (id) ON DELETE CASCADE,
    PRIMARY KEY (document_id, tag_id)
);

CREATE INDEX IF NOT EXISTS document_tags_tag_idx ON core.document_tags (tag_id);

CREATE TABLE IF NOT EXISTS core.interview_question_tags (
    question_id UUID NOT NULL REFERENCES core.interview_questions (id) ON DELETE CASCADE,
    tag_id UUID NOT NULL REFERENCES core.tags (id) ON DELETE CASCADE,
    PRIMARY KEY (question_id, tag_id)
);

CREATE INDEX IF NOT EXISTS question_tags_tag_idx ON core.interview_question_tags (tag_id);

CREATE TABLE IF NOT EXISTS core.index_jobs (
    id UUID PRIMARY KEY,
    job_type VARCHAR(64) NOT NULL,
    resource_type VARCHAR(64) NOT NULL,
    resource_id UUID NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    max_attempts INTEGER NOT NULL DEFAULT 3,
    force_rebuild BOOLEAN NOT NULL DEFAULT FALSE,
    error_message TEXT,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    CONSTRAINT index_jobs_type_check CHECK (job_type = 'INDEX_DOCUMENT'),
    CONSTRAINT index_jobs_resource_type_check CHECK (resource_type = 'DOCUMENT'),
    CONSTRAINT index_jobs_status_check CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'RETRY_WAITING'))
);

CREATE INDEX IF NOT EXISTS index_jobs_resource_idx
    ON core.index_jobs (resource_type, resource_id, created_at DESC);
CREATE INDEX IF NOT EXISTS index_jobs_status_idx
    ON core.index_jobs (status, created_at);

-- Requeue former drafts without deleting content or duplicating active jobs.
INSERT INTO core.index_jobs (id, job_type, resource_type, resource_id)
SELECT gen_random_uuid(), 'INDEX_DOCUMENT', 'DOCUMENT', d.id
FROM core.documents d
WHERE d.status = 'PENDING_INDEX'
  AND NOT EXISTS (
      SELECT 1 FROM core.index_jobs j
      WHERE j.resource_id = d.id AND j.job_type = 'INDEX_DOCUMENT'
        AND j.status IN ('PENDING', 'RUNNING', 'RETRY_WAITING')
  );

CREATE TABLE IF NOT EXISTS rag.document_chunks (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL REFERENCES core.documents (id) ON DELETE CASCADE,
    job_id UUID REFERENCES core.index_jobs (id) ON DELETE SET NULL,
    chunk_index INTEGER NOT NULL,
    title_path TEXT,
    content TEXT NOT NULL,
    token_count INTEGER NOT NULL DEFAULT 0,
    embedding vector(1024),
    embedding_model VARCHAR(120),
    search_vector tsvector GENERATED ALWAYS AS (
        to_tsvector('simple', COALESCE(title_path, '') || ' ' || content)
    ) STORED,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (document_id, chunk_index)
);

ALTER TABLE rag.document_chunks ADD COLUMN IF NOT EXISTS embedding vector(1024);
ALTER TABLE rag.document_chunks ADD COLUMN IF NOT EXISTS embedding_model VARCHAR(120);
ALTER TABLE rag.document_chunks ADD COLUMN IF NOT EXISTS search_vector tsvector GENERATED ALWAYS AS (
    to_tsvector('simple', COALESCE(title_path, '') || ' ' || content)
) STORED;

CREATE INDEX IF NOT EXISTS document_chunks_document_idx
    ON rag.document_chunks (document_id, chunk_index);
CREATE INDEX IF NOT EXISTS document_chunks_search_idx
    ON rag.document_chunks USING GIN (search_vector);

-- Existing legacy AI tables are left untouched. New databases only need the
-- active V1 tables above; this script never drops historical business data.
