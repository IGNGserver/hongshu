CREATE TABLE conversation_heads_sender (
 sender VARCHAR(100) NOT NULL,
 message_id BIGINT NOT NULL, timestamp BIGINT NOT NULL,
 PRIMARY KEY(sender), UNIQUE KEY head_message(message_id),
 KEY latest(timestamp,message_id),
 FOREIGN KEY(message_id) REFERENCES messages(id)
);
INSERT INTO conversation_heads_sender (sender,message_id,timestamp)
SELECT sender,id,timestamp FROM (
 SELECT sender,id,timestamp,
  ROW_NUMBER() OVER (PARTITION BY sender ORDER BY timestamp DESC,id DESC) AS rn
 FROM messages
) ranked WHERE rn=1;
RENAME TABLE conversation_heads TO conversation_heads_legacy,
 conversation_heads_sender TO conversation_heads;
DROP TABLE conversation_heads_legacy;
