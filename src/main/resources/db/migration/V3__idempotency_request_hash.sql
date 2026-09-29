-- Fingerprint of the submit request, so an Idempotency-Key reused with a different payload can be rejected.
-- Nullable: rows created before this migration (or without a key) have no fingerprint and always match.
ALTER TABLE notifications ADD COLUMN request_hash VARCHAR(64);
