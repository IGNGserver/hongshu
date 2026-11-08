-- 014 introduced narrower, ordered replacements for two older indexes. Every unread
-- aggregate filters historical=FALSE AND direction='in', so messages_unread
-- (historical, id, sender) is a strict prefix of messages_unread_v2
-- (historical, direction, id, sender); push_jobs.pending(next_at) is likewise a strict
-- prefix of push_jobs_claim(next_at, leased_until, id). Keeping both only doubles index
-- writes on every ingested message and every queued Push, so drop the superseded pair.
DROP INDEX messages_unread ON messages;
DROP INDEX pending ON push_jobs;
