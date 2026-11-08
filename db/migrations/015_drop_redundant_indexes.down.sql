CREATE INDEX messages_unread ON messages(historical, id, sender);
CREATE INDEX pending ON push_jobs(next_at);
