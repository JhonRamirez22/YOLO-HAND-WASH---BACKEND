-- Runtime kill switch for photo capture of reported washes. Starts disabled; ADMIN switches it.
CREATE TABLE evidence_settings (
    id SMALLINT PRIMARY KEY,
    capture_enabled BOOLEAN NOT NULL,
    updated_by VARCHAR(128) NOT NULL,
    updated_at_epoch_ms BIGINT NOT NULL CHECK (updated_at_epoch_ms >= 0),
    CONSTRAINT chk_evidence_settings_single CHECK (id = 1)
);

INSERT INTO evidence_settings (id, capture_enabled, updated_by, updated_at_epoch_ms)
VALUES (1, FALSE, 'SYSTEM', 0);
