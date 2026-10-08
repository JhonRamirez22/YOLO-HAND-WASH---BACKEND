package com.handwash.api.v1.mapper;

import com.handwash.api.v1.dto.DetectionRequest;
import com.handwash.api.v1.dto.MovementEvidenceRequest;
import com.handwash.api.v1.dto.SoapEvidenceRequest;
import com.handwash.model.DeteccionEvento;
import com.handwash.model.EvidenciaJabon;
import com.handwash.model.EvidenciaMovimiento;
import com.handwash.model.EstadoEvidenciaJabon;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class DetectionRequestMapper {
    public DeteccionEvento toDomain(DetectionRequest request) {
        if (request == null) return null;
        DeteccionEvento event = new DeteccionEvento();
        event.setSessionId(request.sessionId());
        event.setClaseDetectada(request.claseDetectada());
        event.setConfianza(request.confianza());
        event.setTimestamp(request.timestamp());
        event.setProducerEpoch(request.producerEpoch());
        event.setEventType(request.eventType());
        event.setFrameSequence(request.frameSequence());
        event.setControlSequence(request.controlSequence());
        event.setFrameWatermark(request.frameWatermark());
        event.setPresenceHandsVisible(request.presenceHandsVisible());
        event.setCaptureAgeMs(request.captureAgeMs());
        event.setEvidenciaMovimiento(toDomain(request.evidenciaMovimiento()));
        if (request.evidenciaMovimiento() != null) {
            var pose = request.evidenciaMovimiento();
            if (pose.poseKeypoints() != null || pose.handBoxes() != null
                || pose.frameWidth() != null || pose.frameHeight() != null) {
                event.setEvidenciaPoseManos(new com.handwash.model.EvidenciaPoseManos(
                    pose.poseKeypoints(), pose.handBoxes(), pose.frameWidth(), pose.frameHeight()));
            }
        }
        event.setEvidenciaJabon(toDomain(request.evidenciaJabon()));
        event.setEvidenciaJabonSecuencia(request.evidenciaJabonSecuencia());
        return event;
    }

    private EvidenciaMovimiento toDomain(MovementEvidenceRequest evidence) {
        if (evidence == null) return null;
        return new EvidenciaMovimiento(evidence.secuencia(), evidence.manosVisibles(),
            evidence.movimientoNormalizado(), evidence.medicionValida(), evidence.antiguedadMs());
    }

    private Map<String, EvidenciaJabon> toDomain(Map<String, SoapEvidenceRequest> evidence) {
        if (evidence == null) return null;
        Map<String, EvidenciaJabon> mapped = new LinkedHashMap<>();
        evidence.forEach((region, value) -> {
            if (value == null) {
                mapped.put(region, null);
            } else {
                EvidenciaJabon domainEvidence = new EvidenciaJabon();
                domainEvidence.setEstado(value.estado() == null
                    ? null : EstadoEvidenciaJabon.valueOf(value.estado().name()));
                domainEvidence.setConfianza(value.confianza());
                mapped.put(region, domainEvidence);
            }
        });
        return mapped;
    }
}
