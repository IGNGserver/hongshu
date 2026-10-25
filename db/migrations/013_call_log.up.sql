-- Call log entries uploaded by Android phones. They are a separate stream from messages:
-- their own commit-ordered clock (sync_clock.seq stays message-only, so message cursors
-- and unread watermarks are untouched) and no unread, push or analysis side effects.
-- `receiver` is this phone's confirmed line or '' when the line could not be identified;
-- `number` is the other party, '' only when the network hid it (presentation <> 'allowed').
CREATE TABLE call_clock (id TINYINT PRIMARY KEY, seq BIGINT NOT NULL);
INSERT INTO call_clock VALUES (1,0);
CREATE TABLE calls (
 id BIGINT PRIMARY KEY, device_id VARCHAR(32) NOT NULL, fingerprint CHAR(64) NOT NULL,
 receiver VARCHAR(24) NOT NULL, number VARCHAR(100) NOT NULL, type VARCHAR(24) NOT NULL,
 timestamp BIGINT NOT NULL, duration INT NOT NULL, subscription_id INT NOT NULL,
 video BOOLEAN NOT NULL DEFAULT FALSE, presentation VARCHAR(16) NOT NULL DEFAULT 'allowed',
 location VARCHAR(100) NOT NULL DEFAULT '', historical BOOLEAN NOT NULL DEFAULT FALSE,
 UNIQUE KEY dedup(device_id, fingerprint),
 KEY timeline(timestamp, id), KEY number_history(number, timestamp, id),
 FOREIGN KEY(device_id) REFERENCES devices(id)
);
