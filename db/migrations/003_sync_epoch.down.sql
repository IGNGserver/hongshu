-- Manual rollback only. Clients that already stored this epoch will resync once.
ALTER TABLE sync_clock DROP COLUMN epoch;
