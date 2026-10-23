-- Station provisioning: the ADMIN creates the station up front and the Mac redeems a one-time
-- activation token. Additive and nullable, so the previous release keeps working on this schema.
ALTER TABLE handwash_stations ADD COLUMN activation_hash VARCHAR(64);
ALTER TABLE handwash_stations ADD COLUMN activation_expires_at_epoch_ms BIGINT;
ALTER TABLE handwash_stations ADD COLUMN activated_at_epoch_ms BIGINT;

CREATE UNIQUE INDEX uq_handwash_stations_activation
    ON handwash_stations (activation_hash);
