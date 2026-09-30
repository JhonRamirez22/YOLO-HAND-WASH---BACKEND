package com.handwash.service.evidence;

/** Photo status stored with a REPORTADO attempt; the image itself never enters the database. */
public enum EvidenceStatus {
    /** Capture is switched off, by deployment or by the ADMIN kill switch. */
    DESACTIVADA,
    /** Capture is on and the kiosk has a short window to upload the retained frame. */
    PENDIENTE,
    /** An encrypted object was stored; its reference is in wash_report_evidence. */
    CAPTURADA,
    /** The window passed without an upload (no presence camera, network, kiosk reload). */
    NO_CAPTURADA,
    /** The retention period ended and the object was deleted. */
    EXPIRADA
}
