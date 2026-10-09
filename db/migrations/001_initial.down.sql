-- Destructive rollback: export a verified backup first. Never run automatically.
DROP TABLE push_jobs;
DROP TABLE push_subscriptions;
DROP TABLE messages;
DROP TABLE contacts;
DROP TABLE sims;
DROP TABLE pairings;
DROP TABLE settings;
DROP TABLE devices;
DROP TABLE sync_clock;
