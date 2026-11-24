CREATE TABLE short_publication_drafts (
    id VARCHAR(36) PRIMARY KEY,
    title VARCHAR(100) NOT NULL,
    description VARCHAR(5000) NOT NULL,
    content_brief VARCHAR(10000) NOT NULL,
    publish_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    youtube_video_id VARCHAR(100),
    made_for_kids BOOLEAN NOT NULL,
    status VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);
