-- A station whose Mac already holds a device credential counts as linked, so a key-login from another
-- device cannot silently replace it. Stations enrolled the old way were never marked.
UPDATE handwash_stations
SET activated_at_epoch_ms = created_at_epoch_ms
WHERE activated_at_epoch_ms IS NULL AND activation_hash IS NULL;
