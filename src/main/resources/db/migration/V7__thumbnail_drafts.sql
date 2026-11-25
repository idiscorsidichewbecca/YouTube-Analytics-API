CREATE TABLE thumbnail_drafts (
    id VARCHAR(36) PRIMARY KEY,
    video_id VARCHAR(11) NOT NULL,
    channel_id VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    choice VARCHAR(1),
    option_a TEXT,
    option_b TEXT,
    option_c TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
