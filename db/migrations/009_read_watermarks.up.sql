CREATE TABLE IF NOT EXISTS conversation_reads (
 sender VARCHAR(100) NOT NULL PRIMARY KEY,
 last_read_message_id BIGINT NOT NULL,
 read_at BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS read_state (
 id INT PRIMARY KEY,
 global_watermark_id BIGINT NOT NULL,
 updated_at BIGINT NOT NULL
);

INSERT INTO read_state (id, global_watermark_id, updated_at)
SELECT 1, COALESCE(MAX(id), 0), UNIX_TIMESTAMP() * 1000
FROM messages
ON DUPLICATE KEY UPDATE id=id;
