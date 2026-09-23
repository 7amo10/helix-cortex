-- PostgreSQL schema migration for machine learning model registry in Helix Cortex
-- Table: helix_ml_models

CREATE TABLE IF NOT EXISTS helix_ml_models (
    id BIGSERIAL PRIMARY KEY,
    model_name VARCHAR(255) NOT NULL,
    version VARCHAR(64) NOT NULL,
    file_path TEXT NOT NULL,
    file_size_kb INTEGER NOT NULL,
    input_schema JSONB NOT NULL,
    output_type VARCHAR(32) DEFAULT 'FLOAT' NOT NULL,
    is_active BOOLEAN DEFAULT false NOT NULL,
    uploaded_by VARCHAR(255) NOT NULL,
    uploaded_at TIMESTAMP NOT NULL,
    description TEXT,
    CONSTRAINT uk_helix_ml_models_name_version UNIQUE (model_name, version)
);

CREATE INDEX IF NOT EXISTS idx_helix_ml_models_name ON helix_ml_models (model_name);
CREATE INDEX IF NOT EXISTS idx_helix_ml_models_active ON helix_ml_models (model_name, is_active);
