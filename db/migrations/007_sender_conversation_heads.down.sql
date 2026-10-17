CREATE TABLE conversation_heads_legacy (
 sender VARCHAR(100) NOT NULL, receiver VARCHAR(24) NOT NULL,
 message_id BIGINT NOT NULL, timestamp BIGINT NOT NULL,
 PRIMARY KEY(sender,receiver), UNIQUE KEY head_message(message_id),
 KEY latest(timestamp,message_id), KEY receiver_latest(receiver,timestamp,message_id),
 FOREIGN KEY(message_id) REFERENCES messages(id)
);
INSERT INTO conversation_heads_legacy (sender,receiver,message_id,timestamp)
SELECT sender,receiver,id,timestamp FROM (
 SELECT sender,receiver,id,timestamp,
  ROW_NUMBER() OVER (PARTITION BY sender,receiver ORDER BY timestamp DESC,id DESC) AS rn
 FROM messages
) ranked WHERE rn=1;
RENAME TABLE conversation_heads TO conversation_heads_sender,
 conversation_heads_legacy TO conversation_heads;
DROP TABLE conversation_heads_sender;
