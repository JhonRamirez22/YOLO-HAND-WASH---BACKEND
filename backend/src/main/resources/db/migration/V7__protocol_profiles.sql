CREATE TABLE protocol_profiles (
    version INTEGER PRIMARY KEY CHECK (version > 0),
    step_1_ms BIGINT NOT NULL CHECK (step_1_ms BETWEEN 3000 AND 15000),
    step_2_ms BIGINT NOT NULL CHECK (step_2_ms BETWEEN 3000 AND 15000),
    step_3_ms BIGINT NOT NULL CHECK (step_3_ms BETWEEN 3000 AND 15000),
    step_4_ms BIGINT NOT NULL CHECK (step_4_ms BETWEEN 3000 AND 15000),
    step_5_ms BIGINT NOT NULL CHECK (step_5_ms BETWEEN 3000 AND 15000),
    step_6_ms BIGINT NOT NULL CHECK (step_6_ms BETWEEN 3000 AND 15000),
    slow_threshold_ms BIGINT NOT NULL CHECK (slow_threshold_ms BETWEEN 40000 AND 120000),
    created_by VARCHAR(128) NOT NULL,
    created_at_epoch_ms BIGINT NOT NULL CHECK (created_at_epoch_ms >= 0)
);

INSERT INTO protocol_profiles (version, step_1_ms, step_2_ms, step_3_ms, step_4_ms,
    step_5_ms, step_6_ms, slow_threshold_ms, created_by, created_at_epoch_ms)
VALUES (1, 7000, 7000, 7000, 7000, 7000, 7000, 60000, 'SYSTEM', 0);

ALTER TABLE wash_encounters ADD COLUMN protocol_profile_version INTEGER;
