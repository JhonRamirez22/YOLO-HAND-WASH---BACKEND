CREATE TABLE friction_attempt_validation (
    session_id VARCHAR(36) NOT NULL,
    attempt_number INTEGER NOT NULL CHECK (attempt_number > 0),
    policy_version VARCHAR(48) NOT NULL,
    revision BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    result VARCHAR(48),
    reason VARCHAR(80),
    terminal BOOLEAN NOT NULL,
    created_at_epoch_ms BIGINT NOT NULL,
    updated_at_epoch_ms BIGINT NOT NULL,
    PRIMARY KEY (session_id, attempt_number)
);
CREATE TABLE friction_step_validation (
    session_id VARCHAR(36) NOT NULL,
    attempt_number INTEGER NOT NULL,
    step_code VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    credited_ms BIGINT NOT NULL CHECK (credited_ms >= 0),
    required_ms BIGINT NOT NULL CHECK (required_ms > 0),
    reason VARCHAR(80),
    PRIMARY KEY (session_id, attempt_number, step_code),
    FOREIGN KEY (session_id, attempt_number) REFERENCES friction_attempt_validation(session_id, attempt_number)
);
