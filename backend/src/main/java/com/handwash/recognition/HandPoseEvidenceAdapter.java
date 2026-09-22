package com.handwash.recognition;

import com.handwash.model.DetectionEvent;

/** Adapter from the existing Python→Java producer DTO to the pose domain contract. */
public interface HandPoseEvidenceAdapter {
    HandPoseObservation adapt(DetectionEvent event, long serverIngressNanos,
                              Double openCvResidualNormalized);
}
