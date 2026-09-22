package com.handwash.recognition;

import com.handwash.model.HandPoseEvidence;

/** One producer frame adapted to Java's server-owned monotonic ingress clock. */
public record HandPoseObservation(String sessionId, String producerEpoch, long frameSequence,
                                  long observedAtNanos, HandPoseEvidence pose,
                                  Double openCvResidualNormalized, String producerCandidate) {}
