-- Rollback for the unread-scan index; messages and watermarks are untouched.
DROP INDEX messages_unread ON messages;
