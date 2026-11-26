CREATE TABLE ig_reel_batch_draft (
    batch_id VARCHAR(36) NOT NULL,
    position INTEGER NOT NULL,
    account_id VARCHAR(100) NOT NULL,
    filename VARCHAR(512) NOT NULL,
    title VARCHAR(120) NOT NULL,
    description VARCHAR(2080) NOT NULL,
    cover_headline VARCHAR(42) NOT NULL,
    cover_second DOUBLE PRECISION NOT NULL,
    cover_template VARCHAR(24) NOT NULL,
    publish_at TIMESTAMP WITH TIME ZONE NOT NULL,
    slot_date DATE NOT NULL,
    evidence VARCHAR(512) NOT NULL,
    comparable_reels INTEGER NOT NULL,
    day_hour_samples INTEGER NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    reel_id VARCHAR(36),
    PRIMARY KEY (batch_id, position),
    CONSTRAINT ig_reel_batch_unique_day UNIQUE (account_id, slot_date)
);
CREATE INDEX ig_reel_batch_account_idx ON ig_reel_batch_draft (account_id, batch_id);
