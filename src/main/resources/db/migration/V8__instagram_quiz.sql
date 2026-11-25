CREATE TABLE ig_quiz_batch (
 id VARCHAR(36) PRIMARY KEY, account_id VARCHAR(64) NOT NULL,
 status VARCHAR(20) NOT NULL, revision VARCHAR(36) NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE ig_quiz (
 id VARCHAR(36) PRIMARY KEY, batch_id VARCHAR(36) NOT NULL REFERENCES ig_quiz_batch(id),
 account_id VARCHAR(64) NOT NULL, position_no INTEGER NOT NULL,
 quiz_day DATE NOT NULL, continuity VARCHAR(10) NOT NULL,
 question TEXT NOT NULL, answer TEXT NOT NULL, source TEXT NOT NULL,
 question_hash VARCHAR(64) NOT NULL, slot_evidence TEXT NOT NULL,
 UNIQUE(account_id, question_hash), UNIQUE(account_id, quiz_day), UNIQUE(batch_id, position_no)
);
CREATE TABLE ig_quiz_story (
 id VARCHAR(36) PRIMARY KEY, quiz_id VARCHAR(36) NOT NULL REFERENCES ig_quiz(id),
 account_id VARCHAR(64) NOT NULL, kind VARCHAR(10) NOT NULL,
 publish_at TIMESTAMP WITH TIME ZONE NOT NULL, status VARCHAR(20) NOT NULL,
 image_data TEXT, asset_key VARCHAR(64) NOT NULL, container_id VARCHAR(64), media_id VARCHAR(64),
 published_at TIMESTAMP WITH TIME ZONE, reach_count BIGINT, reach_at TIMESTAMP WITH TIME ZONE,
 error_code VARCHAR(100), UNIQUE(quiz_id, kind)
);
CREATE INDEX ig_quiz_story_due ON ig_quiz_story(status, publish_at);
CREATE INDEX ig_quiz_story_history ON ig_quiz_story(account_id, kind, published_at);
