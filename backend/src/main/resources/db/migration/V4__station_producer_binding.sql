ALTER TABLE handwash_stations
    ADD COLUMN producer_client_id VARCHAR(128);

CREATE INDEX idx_handwash_stations_producer_client
    ON handwash_stations (producer_client_id);
