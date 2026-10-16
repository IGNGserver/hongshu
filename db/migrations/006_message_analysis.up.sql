CREATE TABLE ai_settings (
 id INT PRIMARY KEY, enabled BOOLEAN NOT NULL DEFAULT FALSE, paused BOOLEAN NOT NULL DEFAULT FALSE,
 endpoint VARCHAR(1000) NOT NULL DEFAULT '', model VARCHAR(200) NOT NULL DEFAULT '', secret TEXT NOT NULL,
 timeout_seconds INT NOT NULL DEFAULT 20, requests_per_minute INT NOT NULL DEFAULT 10,
 daily_limit INT NOT NULL DEFAULT 500, redact BOOLEAN NOT NULL DEFAULT TRUE,
 generation BIGINT NOT NULL DEFAULT 1, revision BIGINT NOT NULL DEFAULT 0,
 backfill_id BIGINT NOT NULL DEFAULT 0, backfill_before BIGINT NOT NULL DEFAULT 0, next_call BIGINT NOT NULL DEFAULT 0,
 usage_day BIGINT NOT NULL DEFAULT 0, usage_count INT NOT NULL DEFAULT 0, last_error VARCHAR(50) NOT NULL DEFAULT ''
);
INSERT INTO ai_settings (id,secret,backfill_id,backfill_before) SELECT 1,'',COALESCE(MAX(id),0),COALESCE(MAX(id),0)+1 FROM messages;
CREATE TABLE message_analysis (
 message_id BIGINT PRIMARY KEY, otp VARCHAR(8) NOT NULL DEFAULT '', expires_at BIGINT NOT NULL DEFAULT 0,
 rule_version INT NOT NULL DEFAULT 1, category VARCHAR(20) NOT NULL DEFAULT '',
 manual BOOLEAN NOT NULL DEFAULT FALSE, state VARCHAR(20) NOT NULL DEFAULT 'pending',
 error VARCHAR(50) NOT NULL DEFAULT '', attempts INT NOT NULL DEFAULT 0,
 next_at BIGINT NOT NULL DEFAULT 0, leased_until BIGINT NOT NULL DEFAULT 0,
 lease_token VARCHAR(32) NOT NULL DEFAULT '', revision BIGINT NOT NULL,
 KEY analysis_changes(revision), KEY analysis_queue(state,next_at,leased_until), KEY analysis_group(category,message_id),
 FOREIGN KEY(message_id) REFERENCES messages(id) ON DELETE CASCADE
);
