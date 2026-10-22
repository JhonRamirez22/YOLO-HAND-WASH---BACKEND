CREATE TABLE station_operator_access_codes (
    code_id VARCHAR(36) PRIMARY KEY,
    code_hash VARCHAR(64) NOT NULL UNIQUE,
    station_id VARCHAR(36) NOT NULL REFERENCES handwash_stations(station_id),
    target_email VARCHAR(254) NOT NULL,
    created_by VARCHAR(128) NOT NULL,
    created_at_epoch_ms BIGINT NOT NULL,
    consumed_at_epoch_ms BIGINT,
    consumed_by VARCHAR(128),
    revoked_at_epoch_ms BIGINT
);

CREATE INDEX idx_operator_codes_station_created
    ON station_operator_access_codes (station_id, created_at_epoch_ms DESC);

CREATE TABLE station_operator_grants (
    cognito_subject VARCHAR(128) PRIMARY KEY,
    email VARCHAR(254) NOT NULL,
    station_id VARCHAR(36) NOT NULL REFERENCES handwash_stations(station_id),
    granted_by VARCHAR(128) NOT NULL,
    granted_at_epoch_ms BIGINT NOT NULL,
    revoked_at_epoch_ms BIGINT
);

CREATE INDEX idx_operator_grants_station
    ON station_operator_grants (station_id, revoked_at_epoch_ms);
