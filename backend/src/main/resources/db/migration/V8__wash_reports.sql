-- Exact wall-clock start/end of each V2 attempt, its audit outcome and photo status.
ALTER TABLE friction_attempt_validation ADD COLUMN started_at_epoch_ms BIGINT;
ALTER TABLE friction_attempt_validation ADD COLUMN finished_at_epoch_ms BIGINT;
ALTER TABLE friction_attempt_validation ADD COLUMN duration_ms BIGINT CHECK (duration_ms >= 0);
ALTER TABLE friction_attempt_validation ADD COLUMN slow BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE friction_attempt_validation ADD COLUMN outcome VARCHAR(16)
    CHECK (outcome IN ('CORRECTO', 'REPORTADO', 'REINICIADO', 'INTERRUMPIDO'));
ALTER TABLE friction_attempt_validation ADD COLUMN evidence_status VARCHAR(24)
    CHECK (evidence_status IN ('DESACTIVADA', 'PENDIENTE', 'CAPTURADA', 'NO_CAPTURADA', 'EXPIRADA'));

CREATE INDEX idx_friction_attempt_outcome_finished
    ON friction_attempt_validation (outcome, finished_at_epoch_ms);

-- Reference to an encrypted photo object for a REPORTADO attempt. Images never live in
-- the database; capture stays disabled until a presence camera and approval exist.
CREATE TABLE wash_report_evidence (
    session_id VARCHAR(36) NOT NULL,
    attempt_number INTEGER NOT NULL,
    object_key VARCHAR(256) NOT NULL,
    sha256 CHAR(64) NOT NULL,
    captured_at_epoch_ms BIGINT NOT NULL,
    expires_at_epoch_ms BIGINT NOT NULL CHECK (expires_at_epoch_ms > captured_at_epoch_ms),
    PRIMARY KEY (session_id, attempt_number),
    FOREIGN KEY (session_id, attempt_number) REFERENCES friction_attempt_validation(session_id, attempt_number)
);
