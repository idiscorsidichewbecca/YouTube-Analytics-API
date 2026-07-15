CREATE TABLE youtube_credentials (
    id BIGINT PRIMARY KEY,
    access_token VARCHAR(4096) NOT NULL,
    refresh_token VARCHAR(4096) NOT NULL,
    access_token_expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    token_type VARCHAR(50),
    scopes VARCHAR(2000),
    channel_id VARCHAR(100) NOT NULL,
    channel_title VARCHAR(500) NOT NULL,
    uploads_playlist_id VARCHAR(100) NOT NULL,
    connected_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);
