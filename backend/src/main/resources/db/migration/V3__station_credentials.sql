CREATE TABLE station_enrollment_codes (
    code_hash VARCHAR(64) PRIMARY KEY,
    station_name VARCHAR(80) NOT NULL,
    created_by VARCHAR(128) NOT NULL,
    created_at_epoch_ms BIGINT NOT NULL,
    expires_at_epoch_ms BIGINT NOT NULL,
    consumed_at_epoch_ms BIGINT,
    CONSTRAINT chk_station_enrollment_expiry CHECK (expires_at_epoch_ms > created_at_epoch_ms)
);

CREATE INDEX idx_station_enrollment_expiry
    ON station_enrollment_codes (expires_at_epoch_ms);

CREATE TABLE handwash_stations (
    station_id VARCHAR(36) PRIMARY KEY,
    station_name VARCHAR(80) NOT NULL,
    credential_hash VARCHAR(64) NOT NULL UNIQUE,
    created_at_epoch_ms BIGINT NOT NULL,
    created_by VARCHAR(128) NOT NULL,
    revoked_at_epoch_ms BIGINT,
    last_started_at_epoch_ms BIGINT,
    active_session_id VARCHAR(36),
    active_request_id VARCHAR(36),
    CONSTRAINT chk_station_active_reservation CHECK (
        (active_session_id IS NULL AND active_request_id IS NULL)
        OR (active_session_id IS NOT NULL AND active_request_id IS NOT NULL)
    )
);

CREATE INDEX idx_handwash_stations_created
    ON handwash_stations (created_at_epoch_ms DESC);
