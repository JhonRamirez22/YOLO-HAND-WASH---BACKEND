package com.handwash.recognition;

import com.handwash.model.DetectionEvent;
import com.handwash.model.WashStep;

/** Preserves the producer frame identity; never reads ground-truth labels. */
public final class ProducerPoseEvidenceAdapter implements HandPoseEvidenceAdapter {
    @Override
    public HandPoseObservation adapt(DetectionEvent event, long serverIngressNanos,
                                    Double openCvResidualNormalized) {
        if (event == null) return null;
        WashStep step = event.getPasoLavadoResuelto();
        String producerCandidate = step != null && step.getNumero() >= 1 && step.getNumero() <= 6
            ? step.name() : null;
        return new HandPoseObservation(event.getSessionId(), event.getProducerEpoch(),
            event.getFrameSequence() == null ? -1L : event.getFrameSequence(),
            serverIngressNanos, event.getEvidenciaPoseManos(), openCvResidualNormalized,
            producerCandidate);
    }
}
