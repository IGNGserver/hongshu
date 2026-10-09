CREATE TABLE sync_clock (id INT PRIMARY KEY, seq BIGINT NOT NULL);
INSERT INTO sync_clock VALUES (1, 0);
CREATE TABLE devices (
 id VARCHAR(32) PRIMARY KEY, name VARCHAR(100) NOT NULL, kind VARCHAR(16) NOT NULL,
 token_hash CHAR(64) NOT NULL UNIQUE, admin BOOLEAN NOT NULL DEFAULT FALSE,
 upload BOOLEAN NOT NULL DEFAULT FALSE, notify BOOLEAN NOT NULL DEFAULT TRUE,
 revoked BOOLEAN NOT NULL DEFAULT FALSE, created_at BIGINT NOT NULL
);
CREATE TABLE pairings (code_hash CHAR(64) PRIMARY KEY, expires_at BIGINT NOT NULL, admin BOOLEAN NOT NULL DEFAULT FALSE);
CREATE TABLE sims (
 device_id VARCHAR(32) NOT NULL, phone VARCHAR(24) NOT NULL, label VARCHAR(100) NOT NULL,
 subscription_id INT NOT NULL, PRIMARY KEY(device_id, phone),
 KEY device_subscription(device_id, subscription_id),
 FOREIGN KEY(device_id) REFERENCES devices(id)
);
CREATE TABLE contacts (phone VARCHAR(100) PRIMARY KEY, name VARCHAR(100) NOT NULL);
CREATE TABLE messages (
 id BIGINT PRIMARY KEY, device_id VARCHAR(32) NOT NULL, fingerprint CHAR(64) NOT NULL,
 receiver VARCHAR(24) NOT NULL, sender VARCHAR(100) NOT NULL, body TEXT NOT NULL,
 timestamp BIGINT NOT NULL, subscription_id INT NOT NULL, historical BOOLEAN NOT NULL DEFAULT FALSE,
 UNIQUE KEY dedup(device_id, fingerprint),
 KEY conversation(sender, timestamp, id), KEY receiver_history(receiver, timestamp, id), KEY device_history(device_id, timestamp, id), KEY timeline(timestamp,id),
 FOREIGN KEY(device_id) REFERENCES devices(id)
);
CREATE TABLE push_subscriptions (
 device_id VARCHAR(32) PRIMARY KEY, endpoint TEXT NOT NULL, p256dh VARCHAR(200) NOT NULL,
 auth VARCHAR(100) NOT NULL, FOREIGN KEY(device_id) REFERENCES devices(id)
);
CREATE TABLE push_jobs (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, message_id BIGINT NOT NULL, device_id VARCHAR(32) NOT NULL,
 attempts INT NOT NULL DEFAULT 0, next_at BIGINT NOT NULL,
 UNIQUE KEY delivery(message_id, device_id), KEY pending(next_at),
 FOREIGN KEY(message_id) REFERENCES messages(id), FOREIGN KEY(device_id) REFERENCES devices(id)
);
CREATE TABLE settings (id INT PRIMARY KEY, title VARCHAR(100) NOT NULL);
INSERT INTO settings VALUES (1, '鸿枢');
