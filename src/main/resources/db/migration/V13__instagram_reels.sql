CREATE TABLE ig_reel_schedule (
    id VARCHAR(36) PRIMARY KEY,
    account_id VARCHAR(100) NOT NULL,
    source_hash VARCHAR(64) NOT NULL,
    asset_key VARCHAR(64) NOT NULL UNIQUE,
    title VARCHAR(120) NOT NULL,
    caption VARCHAR(2200) NOT NULL,
    publish_at TIMESTAMP WITH TIME ZONE NOT NULL,
    slot_date DATE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL,
    state VARCHAR(24) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    container_id VARCHAR(100),
    media_id VARCHAR(100),
    error_code VARCHAR(80),
    assets_removed BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT ig_reel_unique_source UNIQUE (account_id, source_hash),
    CONSTRAINT ig_reel_unique_day UNIQUE (account_id, slot_date)
);

CREATE INDEX ig_reel_due ON ig_reel_schedule (state, next_attempt_at);
