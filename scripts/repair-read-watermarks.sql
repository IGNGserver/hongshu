-- Manual, opt-in repair for legacy invalid conversation read watermarks.
-- Run on the selected Hongshu database with a client that stops on SQL errors
-- (never --force). Back up read state first; audit output contains counts only.
-- Use only with writers that lock read_state before changing conversation_reads.
-- This is not a migration or a startup/release hook. No message is changed.
START TRANSACTION;

-- Same first lock as conversation mark-read; never acquire sync_clock here.
SELECT updated_at AS previous_read_revision
FROM read_state WHERE id=1 FOR UPDATE;

SELECT COUNT(*) AS invalid_read_watermarks
FROM conversation_reads cr
LEFT JOIN messages m ON m.id=cr.last_read_message_id AND m.sender=cr.sender
WHERE cr.last_read_message_id<>0 AND m.id IS NULL;

UPDATE conversation_reads cr
LEFT JOIN messages m ON m.id=cr.last_read_message_id AND m.sender=cr.sender
SET cr.last_read_message_id=0,
    cr.read_at=CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS SIGNED)
WHERE cr.last_read_message_id<>0 AND m.id IS NULL;
SET @hongshu_repaired_read_watermarks=ROW_COUNT();

-- Internal logical timestamp: strictly monotonic even within one millisecond
-- or after wall-clock rollback. A second, no-op run leaves it unchanged.
UPDATE read_state
SET updated_at=GREATEST(updated_at+1,CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS SIGNED))
WHERE id=1 AND @hongshu_repaired_read_watermarks>0;

SELECT @hongshu_repaired_read_watermarks AS repaired_rows,
       updated_at AS read_revision, global_watermark_id
FROM read_state WHERE id=1;
COMMIT;
