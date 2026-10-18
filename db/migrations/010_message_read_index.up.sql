-- Unread and conversation-page queries filter on historical messages above a watermark and
-- group by sender; without this index every poll re-scans the message table.
CREATE INDEX messages_unread ON messages(historical,id,sender);
