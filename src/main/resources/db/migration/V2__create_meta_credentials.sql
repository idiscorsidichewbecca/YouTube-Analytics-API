CREATE TABLE meta_credentials (
    id BIGINT PRIMARY KEY,
    system_user_access_token VARCHAR(8192) NOT NULL,
    page_access_token VARCHAR(8192) NOT NULL,
    access_token_expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    token_type VARCHAR(50),
    scopes VARCHAR(4000),
    business_id VARCHAR(100),
    business_name VARCHAR(500),
    page_id VARCHAR(100) NOT NULL,
    page_name VARCHAR(500) NOT NULL,
    instagram_account_id VARCHAR(100) NOT NULL,
    instagram_username VARCHAR(500) NOT NULL,
    connected_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);
