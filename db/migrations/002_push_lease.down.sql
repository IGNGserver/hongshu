-- Manual rollback only. Do not run while the hub is sending push.
ALTER TABLE push_jobs DROP COLUMN leased_until;
