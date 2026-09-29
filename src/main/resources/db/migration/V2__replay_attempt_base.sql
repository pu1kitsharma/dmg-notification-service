-- Attempt number at the last replay of a DEAD notification. Attempt numbers stay monotonic (fencing token,
-- attempt history); the retry budget is measured from this base.
ALTER TABLE notifications ADD COLUMN attempt_base INTEGER DEFAULT 0 NOT NULL;
