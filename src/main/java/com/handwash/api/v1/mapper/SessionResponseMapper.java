package com.handwash.api.v1.mapper;

import com.handwash.api.v1.dto.EvaluationResponse;
import com.handwash.api.v1.dto.FailedAttemptResponse;
import com.handwash.api.v1.dto.FailedAttemptsResponse;
import com.handwash.api.v1.dto.ProgressResponse;
import com.handwash.api.v1.dto.ViolationResponse;
import com.handwash.model.EstadoLavadoResponse;
import com.handwash.model.Infraccion;
import com.handwash.model.IntentoLavadoResumen;
import com.handwash.model.Progreso;
import org.springframework.stereotype.Component;

import java.util.List;

/** Projects mutable domain snapshots into immutable versioned HTTP DTOs. */
@Component
public final class SessionResponseMapper {
    public FailedAttemptsResponse toFailedAttempts(String sessionId,
                                                    List<IntentoLavadoResumen> attempts) {
        List<FailedAttemptResponse> mapped = attempts.stream().map(attempt ->
            new FailedAttemptResponse(attempt.numero(), attempt.resultado(),
                attempt.motivoReinicio(), attempt.duracionMs(), attempt.tiempoPorPasoMs(),
                attempt.infracciones().stream().map(this::toViolation).toList())
        ).toList();
        return new FailedAttemptsResponse(sessionId, mapped, true);
    }

    public EvaluationResponse toEvaluation(EstadoLavadoResponse response) {
        if (response == null) return null;
        List<ViolationResponse> history = response.getHistorialInfracciones() == null
            ? null : response.getHistorialInfracciones().stream().map(this::toViolation).toList();
        return new EvaluationResponse(
            response.getSessionId(),
            response.getEstadoIntencion(),
            response.getMotivoIntencion(),
            response.getTiempoConfirmacionIntencionMs(),
            response.getUmbralConfirmacionIntencionMs(),
            response.getManosVisibles(),
            response.getClaseCandidata(),
            response.getConfianzaCandidata(),
            response.getEstadoActual(),
            response.getTiempoAcumuladoMs(),
            toViolation(response.getInfraccion()),
            toProgress(response.getProgreso()),
            response.getManoDetectada(),
            response.getConfianzaDeteccion(),
            response.getManoConfianza(),
            response.getEstadoSesion(),
            response.getMessageType(),
            history,
            response.getIntentosReiniciados(),
            toViolation(response.getUltimoErrorReinicio()),
            response.getModoEvaluacion(),
            response.getCoberturaJabon(),
            response.isCoberturaJabonCompleta(),
            response.isProcedimientoCompletoValidado(),
            response.getTiempoTotalActivoMs(),
            response.getDuracionMinimaObjetivoMs());
    }

    private ViolationResponse toViolation(Infraccion violation) {
        if (violation == null) return null;
        return new ViolationResponse(
            violation.getTipo() == null ? null : violation.getTipo().name(),
            violation.getDetalle(), violation.getPaso(), violation.getTimestamp());
    }

    private ProgressResponse toProgress(Progreso progress) {
        return progress == null ? null
            : new ProgressResponse(progress.getPasosCompletados(), progress.getPasosTotales());
    }
}
