CREATE TABLE youtube_caption_excerpt (
    channel_id VARCHAR(128) NOT NULL,
    video_id VARCHAR(11) NOT NULL,
    state VARCHAR(24) NOT NULL,
    language VARCHAR(64),
    caption_source VARCHAR(64),
    payload TEXT,
    payload_bytes INTEGER NOT NULL,
    truncated BOOLEAN NOT NULL,
    fetched_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (channel_id, video_id)
);
CREATE INDEX ix_caption_excerpt_expiry ON youtube_caption_excerpt(expires_at);
CREATE INDEX ix_caption_excerpt_age ON youtube_caption_excerpt(fetched_at);
