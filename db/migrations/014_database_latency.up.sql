-- Remote/shared MySQL makes every index scan and queue poll more expensive. These indexes
-- keep the unread aggregate and push claim on narrow, ordered ranges without changing any
-- message content or cursors.
CREATE INDEX messages_unread_v2 ON messages(historical, direction, id, sender);
CREATE INDEX push_jobs_claim ON push_jobs(next_at, leased_until, id);
