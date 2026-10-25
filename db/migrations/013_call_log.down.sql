-- Rollback for the call log (DESTRUCTIVE for uploaded call records; back up first).
-- Messages are untouched. Phones keep their own system call logs.
DROP TABLE calls;
DROP TABLE call_clock;
