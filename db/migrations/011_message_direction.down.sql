-- Rollback for message direction. Without the column a sent message would read as one
-- received from its recipient, so sent messages are removed instead of relabelled
-- (DESTRUCTIVE for them; back up first). Received messages are untouched. Phones keep
-- their own copies in the system SMS database and can upload them again after re-upgrade.
DELETE p FROM push_jobs p JOIN messages m ON m.id=p.message_id WHERE m.direction='out';
DELETE h FROM conversation_heads h JOIN messages m ON m.id=h.message_id WHERE m.direction='out';
DELETE cr FROM conversation_reads cr JOIN messages m ON m.id=cr.last_read_message_id WHERE m.direction='out';
DELETE FROM messages WHERE direction='out';
INSERT INTO conversation_heads (sender,message_id,timestamp)
SELECT sender,id,timestamp FROM (
 SELECT sender,id,timestamp,
  ROW_NUMBER() OVER (PARTITION BY sender ORDER BY timestamp DESC,id DESC) AS rn
 FROM messages
) ranked WHERE rn=1 AND NOT EXISTS (SELECT 1 FROM conversation_heads h WHERE h.sender=ranked.sender);
ALTER TABLE messages DROP COLUMN direction;
