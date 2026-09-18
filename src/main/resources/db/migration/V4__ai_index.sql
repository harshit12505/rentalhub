-- ---------------------------------------------------------------------------
-- V4: the AI index
--
-- Spring AI's PgVectorStore keeps one row per embedded document. Flyway creates
-- the table, because Flyway owns the schema here: the store's own schema
-- initialisation stays switched off (spring.ai.vectorstore.pgvector.initialize-schema).
-- The column names and types are the ones the store expects.
-- ---------------------------------------------------------------------------

-- The vector extension came with V1. hstore is what PgVectorStore uses for metadata
-- filters; uuid generation is built into Postgres 13+, so no uuid-ossp is needed.
CREATE EXTENSION IF NOT EXISTS hstore;

CREATE TABLE vector_store (
    id        uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    content   text,
    metadata  json,
    -- 768 numbers per listing. gemini-embedding-001 can return 3,072, but pgvector's
    -- indexes stop at 2,000 dimensions, and 768 keeps the index small and fast.
    embedding vector(768)
);

-- HNSW ("hierarchical navigable small world") is a graph index for approximate
-- nearest-neighbour search: it finds the closest vectors without comparing every row.
-- Cosine distance compares direction and ignores length, which is how similarity
-- between embeddings is measured.
CREATE INDEX idx_vector_store_embedding ON vector_store USING hnsw (embedding vector_cosine_ops);


-- What has been embedded, and from which text. The hash is the point of this table:
-- an edit that leaves the embedded text unchanged (a new photo, an availability date)
-- must not spend free-tier quota re-embedding the same words.
CREATE TABLE listing_embeddings (
    property_id  BIGINT      PRIMARY KEY REFERENCES properties (id) ON DELETE CASCADE,
    document_id  uuid        NOT NULL,          -- the row this listing has in vector_store
    content_hash CHAR(64)    NOT NULL,          -- SHA-256 of the embedded text, in hex
    embedded_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The backfill job asks "which active listings have no embedding yet?", so the join
-- runs from properties to here; the primary key already serves that lookup.
